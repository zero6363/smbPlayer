package com.smbplayer.app.player

import android.util.Log
import com.smbplayer.app.smb.SmbReadHandle
import com.smbplayer.app.smb.SmbSession
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [mpv 实验分支] 把 SMB 文件暴露成本地 HTTP 流，供 libmpv 播放。
 *
 * libmpv 的 AAR 不带 libsmbclient，无法直接 loadfile smb://；MPVLib 也没有开放
 * stream_cb 给 Java 层。所以这里用 smbj（项目已有的 SMB 读取能力）把文件读出来，
 * 通过 127.0.0.1 上的 HTTP 服务喂给 mpv——架构与 reex 的 libsmb2 + stream_cb 等价。
 *
 * - 只绑定 127.0.0.1，不对外暴露。
 * - 支持 Range 请求（206 Partial Content），这是 mpv 拖动进度/断点续播的前提。
 * - 按 token 缓存 [SmbReadHandle] 池：seek 时 reposition 复用已打开的文件句柄，
 *   不重复走 SMB Create 握手。
 */
class SmbHttpProxy private constructor() {

    private val serverSocket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
    private val sources = ConcurrentHashMap<String, StreamSource>()
    private val handlePools = ConcurrentHashMap<String, ConcurrentLinkedQueue<SmbReadHandle>>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "smb-http-${threadSeq++}").apply { isDaemon = true }
    }
    private val running = AtomicBoolean(true)

    /** 本地监听端口（构造时由系统分配） */
    val port: Int get() = serverSocket.localPort

    data class StreamSource(
        val session: SmbSession,
        val share: String,
        val path: String,
        val length: Long,
        val contentType: String,
    )

    fun start() {
        executor.execute {
            Log.i(TAG, "SMB HTTP proxy started on 127.0.0.1:$port")
            while (running.get()) {
                try {
                    val client = serverSocket.accept()
                    executor.execute { serve(client) }
                } catch (e: IOException) {
                    if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                    break
                }
            }
        }
    }

    /** 注册一个 SMB 文件，返回访问 token */
    fun register(session: SmbSession, share: String, path: String): String {
        val handle = SmbReadHandle(session, share, path)
        handle.openAt(0L)
        val source = StreamSource(
            session = session,
            share = share,
            path = path,
            length = handle.length,
            contentType = contentTypeFor(path),
        )
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        sources[token] = source
        handlePools[token] = ConcurrentLinkedQueue<SmbReadHandle>().apply { add(handle) }
        Log.i(TAG, "register $token -> smb://$share/$path (${source.length} bytes)")
        return token
    }

    /** 供 mpv loadfile 的 URL */
    fun url(token: String): String = "http://127.0.0.1:$port/stream/$token"

    fun unregister(token: String) {
        sources.remove(token)
        handlePools.remove(token)?.forEach { runCatching { it.close() } }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket.close() }
        sources.keys.toList().forEach { unregister(it) }
        executor.shutdownNow()
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = 30_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.US_ASCII))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                sendError(client, 400, "Bad Request")
                return
            }
            val method = parts[0]
            val target = parts[1]
            var rangeHeader: String? = null
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substring(6).trim()
                }
            }
            if (method != "GET" && method != "HEAD") {
                sendError(client, 405, "Method Not Allowed")
                return
            }
            val token = target.removePrefix("/stream/").substringBefore("?")
            val source = sources[token]
            if (source == null) {
                sendError(client, 404, "Not Found")
                return
            }

            val total = source.length
            var start = 0L
            var end = total - 1L
            var partial = false
            if (!rangeHeader.isNullOrEmpty()) {
                val spec = rangeHeader.removePrefix("bytes=").trim()
                val dash = spec.indexOf('-')
                if (dash < 0 || total <= 0) {
                    sendError(client, 416, "Range Not Satisfiable")
                    return
                }
                val startText = spec.substring(0, dash).trim()
                val endText = spec.substring(dash + 1).trim()
                if (startText.isEmpty()) {
                    // 后缀范围：bytes=-500 表示最后 500 字节
                    val suffix = endText.toLongOrNull() ?: 0L
                    start = (total - suffix).coerceAtLeast(0L)
                } else {
                    start = startText.toLongOrNull() ?: 0L
                    if (endText.isNotEmpty()) end = endText.toLongOrNull() ?: (total - 1L)
                }
                if (start >= total || start < 0) {
                    sendError(client, 416, "Range Not Satisfiable")
                    return
                }
                end = end.coerceAtMost(total - 1L)
                partial = true
            }
            if (total <= 0) {
                sendError(client, 416, "Range Not Satisfiable")
                return
            }

            val length = end - start + 1
            android.util.Log.i(TAG, "serve $token Range=$rangeHeader -> bytes $start-$end/$total (len=$length)")
            val out = client.getOutputStream()
            val status = if (partial) 206 else 200
            val header = StringBuilder()
                .append("HTTP/1.1 $status ${if (partial) "Partial Content" else "OK"}\r\n")
                .append("Content-Type: ${source.contentType}\r\n")
                .append("Content-Length: $length\r\n")
                .append("Accept-Ranges: bytes\r\n")
            if (partial) header.append("Content-Range: bytes $start-$end/$total\r\n")
            header.append("Connection: close\r\n")
                .append("Cache-Control: no-store\r\n\r\n")
            out.write(header.toString().toByteArray(Charsets.US_ASCII))
            out.flush()
            if (method == "HEAD") return

            val pool = handlePools[token] ?: run {
                sendError(client, 404, "Not Found")
                return
            }
            val handle = pool.poll() ?: SmbReadHandle(source.session, source.share, source.path).also {
                it.openAt(0L)
            }
            try {
                handle.reposition(start)
                val buffer = ByteArray(64 * 1024)
                var remaining = length
                var written = 0L
                while (remaining > 0) {
                    val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                    val n = handle.read(buffer, 0, toRead)
                    if (n <= 0) break
                    out.write(buffer, 0, n)
                    remaining -= n
                    written += n
                }
                out.flush()
                android.util.Log.i(TAG, "served $token: wrote $written of $length bytes, short=${remaining > 0}")
            } finally {
                // 句柄保持打开归还池，下次 Range 请求 reposition 复用，不重开文件。
                pool.offer(handle)
            }
        } catch (e: Exception) {
            Log.w(TAG, "serve failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            runCatching { client.close() }
        }
    }

    private fun sendError(socket: Socket, code: Int, reason: String) {
        try {
            val body = "$code $reason".toByteArray(Charsets.UTF_8)
            val header = "HTTP/1.1 $code $reason\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            val out = socket.getOutputStream()
            out.write(header.toByteArray(Charsets.US_ASCII))
            out.write(body)
            out.flush()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "SmbHttpProxy"
        private var threadSeq = 0

        fun create(): SmbHttpProxy = SmbHttpProxy()

        private fun contentTypeFor(path: String): String = when {
            path.endsWith(".ts", true) || path.endsWith(".m2ts", true) -> "video/mp2t"
            path.endsWith(".mp4", true) -> "video/mp4"
            path.endsWith(".mkv", true) -> "video/x-matroska"
            path.endsWith(".avi", true) -> "video/x-msvideo"
            path.endsWith(".m4v", true) -> "video/mp4"
            path.endsWith(".webm", true) -> "video/webm"
            else -> "application/octet-stream"
        }
    }
}
