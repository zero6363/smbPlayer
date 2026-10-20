package com.smbplayer.app.util

import java.io.ByteArrayOutputStream

/** 一条完整 SMB 路径拆解后的各个部分。 */
data class SmbPathParts(
    val host: String,
    val port: Int = 445,
    val share: String,
    /** 共享内的子目录：`/` 分隔、无首尾斜杠；空 = 共享根本身 */
    val path: String = "",
    /** `smb://user:pass@host/...` 形式自带的凭据 */
    val username: String? = null,
    val password: String? = null,
)

/**
 * SMB 完整路径解析。
 *
 * 支持三种常见写法（Windows 资源管理器地址栏里复制出来的就是第一种）：
 * - UNC：`\\192.168.1.5\video\纪录片`、`//nas/public/movies`
 * - URL：`smb://nas/video`、`smb://user:pass@nas:1445/video/纪录片`
 * - 共享字段里粘了子路径：`video/纪录片`
 *
 * 存在的意义：SMB 协议里共享之间互相不可见，光靠「扫描常见共享名」
 * 覆盖不到自定义名字，用户手里往往直接有一条完整路径——能解析路径，
 * 就不存在「扫不到」的目录，粘贴即达。
 */
object SmbPath {

    /** 是完整路径吗（UNC 或 smb:// URL，而不是裸共享名） */
    fun isFullPath(text: String): Boolean {
        val t = text.trim()
        return t.startsWith("\\\\") || t.startsWith("//") ||
            t.startsWith("smb:", ignoreCase = true)
    }

    /** 解析完整路径；不是完整路径或解析失败返回 null */
    fun parse(raw: String): SmbPathParts? {
        var s = raw.trim()
        if (s.isEmpty()) return null
        val isUrl = s.startsWith("smb:", ignoreCase = true)
        when {
            isUrl -> s = s.substring(s.indexOf(':') + 1).trimStart('/')
            s.startsWith("\\\\") || s.startsWith("//") -> s = s.trimStart('\\', '/')
            else -> return null
        }

        // user:pass@host
        var user: String? = null
        var pass: String? = null
        val at = s.lastIndexOf('@')
        if (at in 1 until s.length) {
            val cred = s.substring(0, at)
            s = s.substring(at + 1)
            val colon = cred.indexOf(':')
            if (colon >= 0) {
                user = cred.substring(0, colon)
                pass = cred.substring(colon + 1)
            } else {
                user = cred
            }
        }

        // host[:port] 之后必须是 share[/path...]，只有 host 视为解析失败
        val slash = s.indexOfFirst { it == '\\' || it == '/' }
        if (slash <= 0) return null
        var host = s.substring(0, slash).trim()
        var port = 445
        val colon = host.lastIndexOf(':')
        if (colon > 0) {
            val p = host.substring(colon + 1).trim().toIntOrNull()
            if (p != null && p in 1..65535) {
                port = p
                host = host.substring(0, colon).trim()
            }
        }
        if (host.isEmpty()) return null
        val rest = s.substring(slash + 1).trim('\\', '/', ' ')
        if (rest.isEmpty()) return null
        val sep = rest.indexOfFirst { it == '\\' || it == '/' }
        val share = (if (sep < 0) rest else rest.substring(0, sep)).trim()
        val path = if (sep < 0) "" else rest.substring(sep + 1).replace('\\', '/').trim('/').trim()
        if (share.isEmpty()) return null

        if (!isUrl) return SmbPathParts(host, port, share, path, user, pass)
        // smb:// 形式的中文/特殊字符常是百分号编码
        return SmbPathParts(
            host = decodePercent(host),
            port = port,
            share = decodePercent(share),
            path = decodePercent(path),
            username = user?.let { decodePercent(it) },
            password = pass?.let { decodePercent(it) },
        )
    }

    /**
     * 共享输入框里粘了子路径时拆分：
     * `video` → ("video", "")；`video/纪录片` → ("video", "纪录片")；
     * 不含分隔符时原样返回。
     */
    fun splitShareInput(raw: String): Pair<String, String> {
        val s = raw.trim().trim('/').trim()
        if (s.isEmpty()) return "" to ""
        val idx = s.indexOfFirst { it == '\\' || it == '/' }
        if (idx < 0) return s to ""
        val share = s.substring(0, idx).trim()
        val path = s.substring(idx + 1).replace('\\', '/').trim('/').trim()
        return share to path
    }

    /** 只解 %XX，不动 `+`（文件名里的 + 不能变成空格） */
    private fun decodePercent(text: String): String {
        if (!text.contains('%')) return text
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '%' && i + 2 < text.length) {
                val v = text.substring(i + 1, i + 3).toIntOrNull(16)
                if (v != null) {
                    out.write(v)
                    i += 3
                    continue
                }
            }
            out.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
