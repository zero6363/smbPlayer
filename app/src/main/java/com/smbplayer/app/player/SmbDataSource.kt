package com.smbplayer.app.player

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSource
import com.smbplayer.app.smb.SmbReadHandle
import com.smbplayer.app.smb.SmbSession
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * 直接从 SMB 共享流式读取的 [DataSource]。
 *
 * 句柄跨 open/close 复用：ExoPlayer 在 seek 时会 close() 再 open() 同一个 DataSource 实例。
 * 只在首次 openFile()（一次 SMB Create 网络往返），之后 seek 用 [SmbReadHandle.reposition]
 * 在同一 file 句柄上零握手定位——TS 的二分查找 PCR 一次要几十次定位，这样才不会卡。
 *
 * 顺序读走 [SmbReadHandle.read]（FileInputStream 带异步预读），**不能用裸随机读替代**，
 * 否则每块数据都同步等一次网络往返，正常播放会明显变卡。
 *
 * close() 延迟回收句柄：seek 的 close→open 间隔是毫秒级，期间新的 open 会让排队中的
 * 关闭任务失效，从而复用同一句柄；真正不再使用（切集/退出）时才释放远程文件。
 */
@OptIn(markerClass = [UnstableApi::class])
class SmbDataSource(
    private val session: SmbSession,
    private val shareName: String,
    private val remotePath: String,
    private val displayUri: Uri,
) : BaseDataSource(/* isNetwork= */ true) {

    private var handle: SmbReadHandle? = null
    private var bytesRemaining: Long = 0L
    private var opened = false

    /** close 后排队中的句柄回收；seek 的 close→open 会让它失效 */
    private var pendingClose: ScheduledFuture<*>? = null
    private var generation = 0

    override fun open(dataSpec: DataSpec): Long {
        if (opened) throw IOException("SmbDataSource 已打开")

        transferInitializing(dataSpec)

        // seek 的 close→open：取消排队中的回收，复用已打开的 SMB 句柄
        synchronized(this) {
            pendingClose?.cancel(false)
            pendingClose = null
            generation++
        }

        val h = handle ?: SmbReadHandle(session, shareName, remotePath).also { handle = it }
        if (!h.isOpen) {
            try {
                h.openAt(dataSpec.position)
            } catch (t: Throwable) {
                runCatching { h.close() }
                handle = null
                throw IOException("无法打开 smb://$shareName/$remotePath", t)
            }
        } else {
            // 句柄已在：新建 stream + skip 定位，零网络握手
            h.reposition(dataSpec.position)
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            h.length - dataSpec.position
        }
        if (bytesRemaining < 0) {
            throw IOException("读取位置超出文件范围：${dataSpec.position} > ${h.length}")
        }

        opened = true
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val h = handle ?: throw IOException("SmbDataSource 未打开")
        val toRead = minOf(bytesRemaining, length.toLong()).toInt()
        val bytesRead = try {
            // 顺序读：走 FileInputStream 的预读缓冲，性能最好
            h.read(target, offset, toRead)
        } catch (t: Throwable) {
            throw IOException("读取 smb://$shareName/$remotePath 失败", t)
        }
        if (bytesRead > 0) {
            bytesRemaining -= bytesRead
            bytesTransferred(bytesRead)
        }
        return bytesRead
    }

    override fun getUri(): Uri = displayUri

    override fun close() {
        // 不立即关句柄：seek 会 close→open 同一实例，立即关闭 = 每次 seek 重新握手。
        // 延迟一小段时间后若没有新的 open 认领，才真正释放远程文件。
        if (opened) {
            opened = false
            transferEnded()
        }
        val myGeneration = synchronized(this) { ++generation }
        val h = handle ?: return
        pendingClose = CLOSE_EXECUTOR.schedule({
            synchronized(this) {
                if (myGeneration == generation) {
                    runCatching { h.close() }
                    if (handle === h) handle = null
                    pendingClose = null
                }
            }
        }, HANDLE_IDLE_MS, TimeUnit.MILLISECONDS)
    }

    /** 供 [Factory] 使用的工厂。 */
    @OptIn(markerClass = [UnstableApi::class])
    class Factory(
        private val session: SmbSession,
        private val shareName: String,
        private val remotePath: String,
        private val displayUri: Uri,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SmbDataSource(session, shareName, remotePath, displayUri)
    }

    companion object {
        /** 句柄空闲多久后回收；需覆盖 seek 的 close→open 间隔 */
        private const val HANDLE_IDLE_MS = 1500L

        private val CLOSE_EXECUTOR = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "smb-ds-close").apply { isDaemon = true }
        }
    }
}
