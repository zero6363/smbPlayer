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

/**
 * 直接从 SMB 共享流式读取的 [DataSource]。
 *
 * 每次 [open] 都按 [DataSpec.position] 重新定位（smbj 的 skip 只改本地偏移，不走网络），
 * 所以 ExoPlayer 的前进 / 后退 seek 都是可用的。
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

    override fun open(dataSpec: DataSpec): Long {
        if (opened) throw IOException("SmbDataSource 已打开")

        transferInitializing(dataSpec)

        val h = SmbReadHandle(session, shareName, remotePath)
        try {
            h.openAt(dataSpec.position)
        } catch (t: Throwable) {
            runCatching { h.close() }
            throw IOException("无法打开 smb://$shareName/$remotePath", t)
        }

        bytesRemaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            h.length - dataSpec.position
        }
        if (bytesRemaining < 0) {
            runCatching { h.close() }
            throw IOException("读取位置超出文件范围：${dataSpec.position} > ${h.length}")
        }

        handle = h
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
        handle?.let { h -> runCatching { h.close() } }
        handle = null
        bytesRemaining = 0
        if (opened) {
            opened = false
            transferEnded()
        }
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
}
