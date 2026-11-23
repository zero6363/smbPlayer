package com.smbplayer.app.data

/**
 * 倍速档位：播放页「常驻倍速」与设置页「长按倍速」共用同一套选项，
 * 保证两处可选值完全一致（新增加档位只需改这里）。
 */
object SpeedOptions {

    /**
     * 可选倍速。
     *
     * 1x 以上按 **0.5x** 一档（1.5 / 2.5 / 3.5 …），避免「1x 直接跳 2x」跨度过大；
     * 1x 以下保留 0.3 / 0.5 / 0.7 这几个慢放档（慢放本来就需要更细的粒度）。
     */
    val VALUES: List<Float> = listOf(
        0.3f, 0.5f, 0.7f,
        1.0f, 1.5f, 2.0f, 2.5f, 3.0f, 3.5f, 4.0f, 4.5f, 5.0f, 5.5f, 6.0f,
    )

    /** 兜底高亮的下标：当前值不在列表里时高亮 1.0x */
    val DEFAULT_INDEX: Int = VALUES.indexOf(1.0f)

    fun indexOf(speed: Float): Int = VALUES.indexOf(speed)
}
