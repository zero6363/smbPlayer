package com.smbplayer.app.player

import android.view.View
import com.smbplayer.app.data.SettingsRepository
import kotlin.math.roundToInt

/**
 * 播放页浮层主题：统一按「背景透明度」缩放控制栏 / 设置面板 / 弹窗底板的 alpha。
 * reex 的主题透明度作用域是整个播放器 UI，这里保持一致，默认 0.78。
 */
object PlayerTheme {
    fun opacity(): Float = SettingsRepository.uiBackgroundOpacity.coerceIn(0.1f, 1.0f)

    /** 按当前透明度重新着色背景；mutate() 避免影响同资源命名的其它 View */
    fun applyBackground(view: View?) {
        val bg = view?.background?.mutate() ?: return
        bg.alpha = (opacity() * 255f).roundToInt().coerceIn(0, 255)
    }
}
