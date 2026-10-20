package com.smbplayer.app.smb

import com.smbplayer.app.data.SmbServer
import java.util.concurrent.ConcurrentHashMap

/**
 * 按服务器 ID 复用 [SmbSession]。
 *
 * 编辑服务器配置或删除记录时要调用 [invalidate]，否则会继续用旧的连接。
 */
object SmbGateway {

    private val sessions = ConcurrentHashMap<String, SmbSession>()

    fun session(server: SmbServer): SmbSession =
        sessions.computeIfAbsent(server.id) { SmbSession(server) }

    fun invalidate(serverId: String) {
        sessions.remove(serverId)?.let { s -> runCatching { s.close() } }
    }

    fun closeAll() {
        sessions.values.forEach { s -> runCatching { s.close() } }
        sessions.clear()
    }
}
