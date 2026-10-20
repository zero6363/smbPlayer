package com.smbplayer.app.data

import java.util.UUID

/**
 * 一条收藏路径：记录某台服务器的某个共享下的某个目录。
 *
 * 收藏后，在服务器列表页点击即可直接进入该目录，无需从根目录逐层点开。
 */
data class FavoritePath(
    val id: String = UUID.randomUUID().toString(),
    val serverId: String,
    val serverName: String,
    val shareName: String,
    /** 共享下的相对路径，根目录为空字符串 */
    val path: String,
    val label: String,
    val createdAt: Long = System.currentTimeMillis(),
)
