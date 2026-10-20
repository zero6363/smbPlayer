package com.smbplayer.app.data

/**
 * 倍速档位：播放页「常驻倍速」与设置页「长按倍速」共用同一套选项，
 * 保证两处可选值完全一致（新增加档位只需改这里）。
 */
object SpeedOptions {

    /** 可选倍速 */
    val VALUES: List<Float> = listOf(
        0.3f, 0.5f, 0.7f, 1.0f, 2.0f, 3.0f, 4.0f, 5.0f, 6.0f,
    )

    /** 兜底高亮的下标：当前值不在列表里时高亮 1.0x */
    val DEFAULT_INDEX: Int = VALUES.indexOf(1.0f)

    fun indexOf(speed: Float): Int = VALUES.indexOf(speed)
}
