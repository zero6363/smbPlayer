package com.smbplayer.app.data

import java.util.UUID

/**
 * 一台 SMB 服务器的连接配置。
 *
 * [anonymous] 为 true 时以访客身份连接，忽略用户名密码。
 */
data class SmbServer(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val port: Int = 445,
    val share: String = "",
    /** 打开时直达的共享内子目录（粘贴完整路径时解析出来的，空 = 共享根） */
    val startPath: String = "",
    val username: String = "",
    val password: String = "",
    val anonymous: Boolean = true,
) {
    /** 展示用，例如 `192.168.1.10` 或 `nas:1445` */
    val displayHost: String
        get() = if (port == 445) host else "$host:$port"

    /** 展示用的认证描述 */
    val displayAuth: String
        get() = if (anonymous) "匿名访问" else "账号：$username"
}
