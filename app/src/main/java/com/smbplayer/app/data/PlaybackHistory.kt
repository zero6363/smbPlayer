package com.smbplayer.app.data

import com.smbplayer.app.util.parentPath

/**
 * 首页「播放记录」里的一条记录。
 *
 * 直接来自 [PlaybackProgressRepository] 的播放进度数据，不额外存一份历史，
 * 因此删除视频 / 目录时记录会自动一起消失，也不会和续播位置不一致。
 * 复合 key 在 [PlaybackProgressRepository.recent] 里拆成这三个定位字段。
 */
data class PlaybackHistory(
    val serverId: String,
    val shareName: String,
    val path: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    /** 文件名（路径最后一段） */
    val name: String get() = path.trim('/').substringAfterLast('/')

    /** 所在目录，供点击记录时直接打开播放页 */
    val directory: String get() = parentPath(path)

    /** 是否看完（保存时位置已归一为总时长） */
    val completed: Boolean get() = durationMs > 0L && positionMs >= durationMs

    /** 卡片底部细进度线的比例：太短的进度至少显示 5%，与目录列表保持一致 */
    val displayFraction: Float
        get() = when {
            durationMs <= 0L || positionMs <= 0L -> 0f
            completed -> 1f
            else -> (positionMs.toDouble() / durationMs).coerceIn(0.05, 1.0).toFloat()
        }
}
