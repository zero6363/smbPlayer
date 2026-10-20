package com.smbplayer.app.smb

import com.hierynomus.mserref.NtStatus
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.mssmb2.SMBApiException
import com.hierynomus.protocol.commons.socket.ProxySocketFactory
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.common.SMBRuntimeException
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskEntry
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import com.smbplayer.app.data.SmbServer
import com.smbplayer.app.util.joinPath
import com.smbplayer.app.util.logError
import com.smbplayer.app.util.logInfo
import com.smbplayer.app.util.parentPath
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 目录列表中的一项。
 *
 * [createdAt] 是服务端返回的创建时间；部分设备/共享不提供或返回 0，
 * 排序时由 [SortOptions] 回退到 [lastModified]。
 */
data class SmbEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val createdAt: Long = 0L,
)

/**
 * 到单台服务器的一条 SMB 连接（连接 + 会话 + 各共享的 TreeConnect 缓存）。
 *
 * 所有方法都是**阻塞**的，必须在 IO 线程调用。
 * 传输层断开时会自动重连并重试一次。
 */
class SmbSession(private val server: SmbServer) : Closeable {

    private val lock = ReentrantLock()

    private var client: SMBClient? = null
    private var connection: Connection? = null
    private var session: Session? = null
    private val shareCache = ConcurrentHashMap<String, DiskShare>()

    @Volatile
    private var connected = false

    // ---------------------------------------------------------------- 连接管理

    private fun newConfig(): SmbConfig = SmbConfig.builder()
        .withSocketFactory(ProxySocketFactory(CONNECT_TIMEOUT_MS))
        .withTimeout(IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .withReadBufferSize(READ_BUFFER_SIZE)
        // 关闭目录租约缓存，走最稳妥的 open-enumerate-close 路径
        .withDirectoryLeasingEnabled(false)
        .build()

    private fun authContext(): AuthenticationContext {
        if (server.anonymous) return AuthenticationContext.anonymous()
        val raw = server.username
        val sep = raw.indexOf('\\')
        return if (sep > 0) {
            AuthenticationContext(raw.substring(sep + 1), server.password.toCharArray(), raw.substring(0, sep))
        } else {
            AuthenticationContext(raw, server.password.toCharArray(), "")
        }
    }

    private fun isAlive(): Boolean {
        val conn = connection
        return connected && conn != null && conn.isConnected && session != null
    }

    /** 建立连接；已连接则直接返回。阻塞。 */
    fun ensureConnected() {
        if (isAlive()) return
        lock.withLock {
            if (isAlive()) return
            disconnectLocked()
            val c = SMBClient(newConfig())
            try {
                val conn = c.connect(server.host, server.port)
                val sess = conn.authenticate(authContext())
                client = c
                connection = conn
                session = sess
                connected = true
                shareCache.clear()
            } catch (t: Throwable) {
                runCatching { c.close() }
                throw t
            }
        }
    }

    /** 断开并释放全部资源。 */
    override fun close() {
        lock.withLock { disconnectLocked() }
    }

    /** 作废当前连接（下次操作会重连）。 */
    fun invalidate() {
        lock.withLock { disconnectLocked() }
    }

    private fun disconnectLocked() {
        connected = false
        shareCache.values.forEach { s -> runCatching { s.close() } }
        shareCache.clear()
        runCatching { session?.close() }
        session = null
        runCatching { connection?.close() }
        connection = null
        runCatching { client?.close() }
        client = null
    }

    /** 取得（必要时建立）到某个共享的连接。阻塞。 */
    fun shareBlocking(name: String): DiskShare {
        shareCache[name]?.let { if (it.isConnected) return it }
        return lock.withLock {
            shareCache[name]?.let { if (it.isConnected) return@withLock it }
            ensureConnected()
            val sess = session ?: throw IOException("SMB 未连接：${server.host}")
            val share = sess.connectShare(name)
            if (share !is DiskShare) {
                runCatching { share.close() }
                throw IOException("「$name」不是磁盘共享")
            }
            shareCache[name] = share
            share
        }
    }

    /**
     * 执行一次 SMB 操作；若是传输层错误则重连并重试一次。
     */
    private fun <T> withRetry(shareName: String?, block: (DiskShare?) -> T): T {
        var lastError: Exception? = null
        for (attempt in 0 until 2) {
            try {
                val share = shareName?.let { shareBlocking(it) }
                return block(share)
            } catch (e: Exception) {
                lastError = e
                if (attempt == 0 && isConnectionError(e)) {
                    invalidate()
                } else {
                    throw e
                }
            }
        }
        throw lastError ?: IOException("SMB 操作失败")
    }

    // ---------------------------------------------------------------- 具体操作

    /** 连接并校验凭据，用于「正在连接」提示。 */
    fun ping() = ensureConnected()

    /**
     * 逐个尝试候选共享名，返回实际可连接的那些。
     *
     * smbj 没有提供共享枚举 API，所以用这种「试连」的方式发现共享。
     */
    fun scanShares(candidates: List<String>): List<String> {
        ensureConnected()
        val found = ArrayList<String>()
        for (name in candidates) {
            if (name.isBlank()) continue
            // IPC$ 能连上但列不了目录，扫出来只会误导
            if (name.equals("IPC$", ignoreCase = true)) continue
            try {
                val s = session?.connectShare(name) ?: continue
                found.add(name)
                runCatching { s.close() }
            } catch (ignored: Exception) {
                // 这个共享不存在或无权限，继续试下一个
            }
        }
        return found
    }

    /** 列出 [path] 下的目录项（不含 . 和 ..），目录在前。阻塞。 */
    fun list(shareName: String, path: String): List<SmbEntry> = withRetry(shareName) { sh ->
        val share = sh ?: throw IOException("未连接到共享「$shareName」")
        share.list(path)
            .asSequence()
            .filter { info -> info.fileName != "." && info.fileName != ".." }
            .map { info -> info.toEntry() }
            .toList()
            .sortedWith(ENTRY_ORDER)
    }

    /**
     * 删除文件或目录（目录递归删除）。阻塞。
     *
     * 兜底顺序：
     * 1. 按调用方给的路径删一次；
     * 2. 服务端按「名称不存在/名称非法」拒绝时，列出父目录、用**服务端返回的
     *    原始名字**重拼路径再删一次（兜住前后空格、Unicode 规范化、大小写差异）；
     * 3. smbj 原生 rm/rmdir 失败（典型：部分 NAS / 路由器 USB 共享只认宽松权限）
     *    时，自动换**兼容模式**重删（见 [deleteEntryCompatible]）；
     * 4. 仍失败时抛出带**中文原因**的 IOException，界面可直接展示。
     */
    fun delete(shareName: String, path: String, isDirectory: Boolean) {
        withRetry(shareName) { sh ->
            val share = sh ?: throw IOException("未连接到共享「$shareName」")
            try {
                deleteEntry(share, path, isDirectory)
            } catch (e: SMBApiException) {
                if (!isNameError(e)) throw deleteFailed(path, e)
                val retryPath = resolveExactPath(share, path) ?: throw deleteFailed(path, e)
                if (retryPath == path) throw deleteFailed(path, e)
                logInfo("删除重试（服务端原始名字）: $retryPath")
                try {
                    deleteEntry(share, retryPath, isDirectory)
                } catch (e2: SMBApiException) {
                    throw deleteFailed(retryPath, e2)
                }
            }
        }
    }

    /** 删除一个条目：文件走 [deleteFileEntry]，目录递归走 [deleteDirectoryRecursive] */
    private fun deleteEntry(share: DiskShare, path: String, isDirectory: Boolean) {
        if (isDirectory) deleteDirectoryRecursive(share, path) else deleteFileEntry(share, path)
    }

    /** 删文件：先 smbj 原生 rm，失败按错误类型走兼容模式 */
    private fun deleteFileEntry(share: DiskShare, path: String) {
        try {
            share.rm(path)
            return
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_DELETE_PENDING) return
            if (isNameError(e)) throw e // 名称类错误交给上层按服务端原始名字重试
            logInfo("rm 失败(${e.status.name})，改用兼容模式删除: $path")
        }
        deleteEntryCompatible(share, path, isDirectory = false)
    }

    /**
     * 递归删除目录：先逐个删子项（子路径一律用服务端返回的原始名字拼），
     * 再删目录本体。
     *
     * 不用 smbj 的 `rmdir(path, true)`：它一旦有子项删不掉就直接中断抛错，
     * 而且看不出目录里还剩什么。这里改成尽量多删、收集失败子项；
     * 若目录仍非空（典型：文件正被播放器占用、处于「删除挂起」状态，
     * 或目录里有客户端枚举不到的隐藏条目），把「还剩哪些」写进错误信息。
     */
    private fun deleteDirectoryRecursive(share: DiskShare, path: String) {
        val failures = ArrayList<String>()
        failures += deleteChildren(share, path)
        var lastError: SMBApiException? = null
        // 清空后再删目录本体；仍报「非空」就再清一遍（可能刚有删除挂起的文件被释放）
        for (attempt in 0..1) {
            try {
                deleteDirectoryEntry(share, path)
                if (failures.isNotEmpty()) {
                    logInfo("目录已删除，但有 ${failures.size} 个子项没删掉: $path")
                }
                return
            } catch (e: SMBApiException) {
                if (e.status == NtStatus.STATUS_DELETE_PENDING) return
                if (e.status != NtStatus.STATUS_DIRECTORY_NOT_EMPTY) throw e
                lastError = e
                if (attempt == 0) failures += deleteChildren(share, path)
            }
        }
        val leftovers = listNames(share, path)
        throw IOException(
            buildString {
                append("目录非空，无法删除：").append(path)
                if (failures.isNotEmpty()) {
                    append("\n删不掉的子项：")
                    failures.take(6).forEach { append("\n· ").append(it) }
                    if (failures.size > 6) append("\n…等共 ").append(failures.size).append(" 项")
                }
                if (leftovers.isNotEmpty()) {
                    append("\n目录里还剩：")
                    append(leftovers.take(6).joinToString("、"))
                    if (leftovers.size > 6) append(" 等")
                }
                append("\n常见原因：文件正被占用（比如还在播放），或目录里有客户端看不到的隐藏文件。")
            },
            lastError,
        )
    }

    /** 删目录本体：先 smbj 原生 rmdir，失败走兼容模式 */
    private fun deleteDirectoryEntry(share: DiskShare, path: String) {
        try {
            share.rmdir(path, false)
            return
        } catch (e: SMBApiException) {
            if (e.status == NtStatus.STATUS_DELETE_PENDING) return
            if (isNameError(e)) throw e
            logInfo("rmdir 失败(${e.status.name})，改用兼容模式删除: $path")
        }
        deleteEntryCompatible(share, path, isDirectory = true)
    }

    /** 删除目录下的子项（先目录后文件）；返回删不掉的子项说明。尽量多删，不中途放弃 */
    private fun deleteChildren(share: DiskShare, path: String): List<String> {
        val children = share.list(path)
        val failures = ArrayList<String>()
        val sorted = children.sortedByDescending { (it.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L }
        for (c in sorted) {
            val name = c.fileName
            if (name == "." || name == "..") continue
            val childPath = joinPath(path, name)
            val childIsDir = (c.fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L
            try {
                deleteEntry(share, childPath, childIsDir)
            } catch (e: Exception) {
                logError("删除子项失败: $childPath", e)
                val reason = (if (e is SMBApiException) e.status.toString() else e.message)
                    ?.lineSequence()?.firstOrNull().orEmpty()
                failures += "$name：$reason"
            }
        }
        return failures
    }

    /**
     * 兼容模式删除。
     *
     * smbj 原生 rm/rmdir 只用「DELETE」访问掩码打开句柄再设置删除挂起，这是规范
     * 做法，但不少设备（NAS、路由器 USB 共享、老版本 Samba）对这种最小权限打开
     * 直接返回 STATUS_ACCESS_DENIED。这里逐级放宽权限重试；仍被拒时再兜底
     * 「只读属性」：清掉只读后重删。
     */
    private fun deleteEntryCompatible(share: DiskShare, path: String, isDirectory: Boolean) {
        try {
            deleteWithAccessLadder(share, path, isDirectory)
            return
        } catch (e: SMBApiException) {
            if (e.status != NtStatus.STATUS_ACCESS_DENIED && e.status != NtStatus.STATUS_CANNOT_DELETE) throw e
            logInfo("删除被拒绝(${e.status.name})，尝试清除只读属性后重试: $path")
        }
        clearReadOnly(share, path, isDirectory)
        deleteWithAccessLadder(share, path, isDirectory)
    }

    /**
     * 逐级放宽访问掩码尝试删除（共享模式一律放开 READ/WRITE/DELETE）。
     *
     * STATUS_SHARING_VIOLATION 说明目标还被占用（常见：刚看完视频，本机播放
     * 句柄还没释放完），换权限组合也没用，睡一小段整轮重来。
     */
    private fun deleteWithAccessLadder(share: DiskShare, path: String, isDirectory: Boolean) {
        var last: SMBApiException? = null
        for (round in 0 until SHARING_RETRY_ROUNDS) {
            var sharingViolation = false
            for (mask in DELETE_ACCESS_MASKS) {
                try {
                    openForDelete(share, path, mask, isDirectory).use { it.deleteOnClose() }
                    return
                } catch (e: SMBApiException) {
                    last = e
                    if (e.status == NtStatus.STATUS_DELETE_PENDING) return
                    if (e.status == NtStatus.STATUS_SHARING_VIOLATION) {
                        sharingViolation = true
                        break
                    }
                    // 其它错误：换下一个（更宽松的）权限组合继续试
                }
            }
            // 权限类错误：宽松组合都已试过，没必要重轮
            if (!sharingViolation) break
            logInfo("删除被占用，稍后重试(${round + 1}/$SHARING_RETRY_ROUNDS): $path")
            if (round < SHARING_RETRY_ROUNDS - 1) Thread.sleep(SHARING_RETRY_DELAY_MS)
        }
        throw last ?: IOException("删除失败：$path")
    }

    /** 按给定访问掩码打开目标（兼容模式用），调用方负责 close（close 即删除） */
    private fun openForDelete(
        share: DiskShare,
        path: String,
        accessMask: EnumSet<AccessMask>,
        isDirectory: Boolean,
    ): DiskEntry {
        val shareAccess = EnumSet.allOf(SMB2ShareAccess::class.java)
        return if (isDirectory) {
            share.openDirectory(
                path,
                accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_DIRECTORY),
                shareAccess,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_DIRECTORY_FILE),
            )
        } else {
            share.openFile(
                path,
                accessMask,
                EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                shareAccess,
                SMB2CreateDisposition.FILE_OPEN,
                EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
            )
        }
    }

    /** 清除只读属性（删除被拒时的兜底；目录要保留目录位，否则会变成普通文件） */
    private fun clearReadOnly(share: DiskShare, path: String, isDirectory: Boolean) {
        val attributes = if (isDirectory) FILE_ATTRIBUTE_DIRECTORY else 0L
        share.setFileInformation(
            path,
            FileBasicInformation(
                FileBasicInformation.DONT_SET,
                FileBasicInformation.DONT_SET,
                FileBasicInformation.DONT_SET,
                FileBasicInformation.DONT_SET,
                attributes,
            ),
        )
    }

    /** 目录下可见的名字（用于失败提示） */
    private fun listNames(share: DiskShare, path: String): List<String> = try {
        share.list(path)
            .map { it.fileName }
            .filter { it != "." && it != ".." }
    } catch (t: Throwable) {
        emptyList()
    }

    /** 名字/路径解析类错误：值得按服务端原始名字再试一次 */
    private fun isNameError(e: SMBApiException): Boolean = when (e.status) {
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND,
        NtStatus.STATUS_OBJECT_NAME_INVALID,
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND,
        NtStatus.STATUS_NO_SUCH_FILE,
        -> true
        else -> false
    }

    /** 把删除失败的 NT 状态翻译成用户看得懂的原因 */
    private fun statusHint(status: NtStatus): String = when (status) {
        NtStatus.STATUS_ACCESS_DENIED -> "没有删除权限（确认账号对该共享有删除权限，且文件不是只读）"
        NtStatus.STATUS_CANNOT_DELETE -> "文件设置了只读属性，不允许删除"
        NtStatus.STATUS_SHARING_VIOLATION -> "文件正被其他程序占用（比如还在播放）"
        NtStatus.STATUS_DIRECTORY_NOT_EMPTY -> "目录里还有删不掉的子项"
        NtStatus.STATUS_OBJECT_NAME_NOT_FOUND -> "名称不存在（可能已被删除，或名称已变化）"
        NtStatus.STATUS_OBJECT_PATH_NOT_FOUND -> "路径不存在（可能已被删除，或名称已变化）"
        NtStatus.STATUS_NO_SUCH_FILE -> "文件不存在（可能已被删除）"
        NtStatus.STATUS_NOT_A_DIRECTORY -> "中间路径不是目录"
        NtStatus.STATUS_FILE_IS_A_DIRECTORY -> "这是目录，请按目录删除"
        else -> status.name
    }

    /** 删除彻底失败时包一层中文原因，方便界面直接展示 */
    private fun deleteFailed(path: String, e: SMBApiException): IOException =
        IOException("$path：${statusHint(e.status)}（${e.status.name}）", e)

    /**
     * 列出父目录，找出服务端真实存在的那个名字，重拼完整路径。
     * 找不到（或父目录本身列不出来）返回 null。
     */
    private fun resolveExactPath(share: DiskShare, path: String): String? {
        return try {
            val parent = parentPath(path)
            val want = path.substringAfterLast('/')
            if (want.isEmpty()) return null
            val entries = share.list(parent)
            val actual = entries.firstOrNull { it.fileName == want }
                ?: entries.firstOrNull { it.fileName.equals(want, ignoreCase = true) }
                ?: return null
            if (parent.isEmpty()) actual.fileName else joinPath(parent, actual.fileName)
        } catch (t: Throwable) {
            null
        }
    }

    /** 判断路径是否存在（用于分享名探测）。阻塞。 */
    fun shareExists(name: String): Boolean = try {
        shareBlocking(name)
        true
    } catch (e: Exception) {
        false
    }

    private fun FileIdBothDirectoryInformation.toEntry(): SmbEntry = SmbEntry(
        name = fileName,
        isDirectory = (fileAttributes and FILE_ATTRIBUTE_DIRECTORY) != 0L,
        size = endOfFile,
        lastModified = try {
            lastWriteTime.toEpochMillis()
        } catch (t: Throwable) {
            0L
        },
        createdAt = try {
            creationTime.toEpochMillis()
        } catch (t: Throwable) {
            0L
        },
    )

    companion object {
        private const val FILE_ATTRIBUTE_DIRECTORY = 0x10L
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val IO_TIMEOUT_SECONDS = 30L
        private const val READ_BUFFER_SIZE = 256 * 1024

        /**
         * 兼容模式删除的访问掩码阶梯：从规范最小权限逐级放宽。
         *
         * 不少 NAS / 路由器 USB 共享对「只有 DELETE」的打开直接返回 ACCESS_DENIED，
         * 更宽的权限（读写属性 / 读数据 / MAXIMUM_ALLOWED / GENERIC_ALL）反而能删掉。
         */
        private val DELETE_ACCESS_MASKS: List<EnumSet<AccessMask>> = listOf(
            EnumSet.of(AccessMask.DELETE, AccessMask.SYNCHRONIZE),
            EnumSet.of(
                AccessMask.DELETE, AccessMask.SYNCHRONIZE,
                AccessMask.FILE_READ_ATTRIBUTES, AccessMask.FILE_WRITE_ATTRIBUTES,
            ),
            EnumSet.of(AccessMask.GENERIC_READ, AccessMask.DELETE, AccessMask.SYNCHRONIZE),
            EnumSet.of(AccessMask.MAXIMUM_ALLOWED),
            EnumSet.of(AccessMask.GENERIC_ALL),
        )

        /**
         * 占用（STATUS_SHARING_VIOLATION）重试：轮数与每轮间隔。
         * 刚看完视频时本机播放句柄还没释放完，等几秒往往就能删掉。
         */
        private const val SHARING_RETRY_ROUNDS = 6
        private const val SHARING_RETRY_DELAY_MS = 400L

        private val ENTRY_ORDER: Comparator<SmbEntry> =
            compareByDescending<SmbEntry> { it.isDirectory }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }

        /** 判断异常是否属于连接/传输层故障（可重连重试）。 */
        fun isConnectionError(e: Throwable): Boolean = when (e) {
            is IOException -> true
            is SMBRuntimeException -> e.cause is IOException
            else -> false
        }
    }
}

/**
 * 一个已打开的远程文件读句柄，支持按任意偏移开始读。
 *
 * [openAt] 会先关闭上一次的句柄；smbj 的 `FileInputStream.skip()` 只调整本地偏移，
 * 不产生网络请求，因此定位是「零成本」的。
 */
class SmbReadHandle(
    private val session: SmbSession,
    private val shareName: String,
    private val path: String,
) : Closeable {

    private var share: DiskShare? = null
    private var file: File? = null
    private var stream: InputStream? = null

    private var fileLength: Long = 0L

    /** 当前读到的绝对偏移 */
    var position: Long = 0L
        private set

    /** 文件总长度 */
    val length: Long get() = fileLength

    /** 剩余可读字节数 */
    val remaining: Long get() = (fileLength - position).coerceAtLeast(0L)

    @Throws(IOException::class)
    fun openAt(startPosition: Long) {
        cleanup()
        var attempts = 0
        while (true) {
            try {
                doOpen(startPosition)
                return
            } catch (e: Exception) {
                cleanup()
                attempts++
                if (attempts >= 2 || !SmbSession.isConnectionError(e)) {
                    throw if (e is IOException) e else IOException("打开 $path 失败", e)
                }
                session.invalidate()
            }
        }
    }

    private fun doOpen(startPosition: Long) {
        val sh = session.shareBlocking(shareName)
        val f = sh.openFile(
            path,
            EnumSet.of(AccessMask.GENERIC_READ),
            EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
            // 不允许 DELETE 共享：否则播放中的文件可被设成「删除挂起」，
            // 名字立刻从列表消失但数据仍占着目录，导致所在文件夹删除时
            // 报 STATUS_DIRECTORY_NOT_EMPTY。被占用时让删除明确报「占用」错误。
            EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
            SMB2CreateDisposition.FILE_OPEN,
            EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE),
        )
        // 先登记，保证异常时也能被 cleanup 关闭
        share = sh
        file = f
        val st = f.getInputStream()
        stream = st

        fileLength = f.length
        position = startPosition
        if (startPosition > 0) {
            var remainingSkip = startPosition
            while (remainingSkip > 0) {
                val skipped = st.skip(remainingSkip)
                if (skipped <= 0) break
                remainingSkip -= skipped
            }
        }
    }

    @Throws(IOException::class)
    fun read(target: ByteArray, offset: Int, length: Int): Int {
        val st = stream ?: throw IOException("文件未打开：$path")
        val n = st.read(target, offset, length)
        if (n > 0) position += n
        return n
    }

    override fun close() = cleanup()

    private fun cleanup() {
        runCatching { stream?.close() }
        runCatching { file?.close() }
        stream = null
        file = null
        share = null
    }
}
