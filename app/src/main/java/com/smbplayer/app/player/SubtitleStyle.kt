package com.smbplayer.app.player

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import com.smbplayer.app.data.SettingsRepository
import dev.jdtech.mpv.MPVLib

/**
 * 字幕样式与横竖屏适配。
 *
 * 关键点：sub-scale-with-window=no 让字号/边距按视频尺寸缩放，而不是按整个窗口高度缩放。
 * 竖屏 16:9 视频只占屏幕中间一条，如果按窗口缩放，55 号字会被放大到接近 100px；
 * 改成按视频缩放后，横屏 720p 视频约 55px，竖屏同一条视频约 31px，视觉大小一致。
 *
 * sub-margin-x 同时是「长句到右边多远强制换行」的边界；给 64 个缩放像素，
 * 在横屏约等于屏宽 5%，竖屏约 36px，长中文句会自动折行，不会顶到屏幕边缘。
 */
object SubtitleStyle {
    data class FontOption(val key: String, val label: String, val family: String)

    val FONTS = listOf(
        FontOption("default", "默认", "sans-serif"),
        FontOption("sans", "无衬线", "sans-serif"),
        FontOption("serif", "衬线", "serif"),
        FontOption("mono", "等宽", "monospace"),
    )

    fun fontFamily(key: String): String =
        FONTS.firstOrNull { it.key == key }?.family ?: "sans-serif"

    fun fontLabel(key: String): String =
        FONTS.firstOrNull { it.key == key }?.label ?: "默认"

    fun typeface(): Typeface = Typeface.create(fontFamily(SettingsRepository.subtitleFont), Typeface.NORMAL)

    private fun setS(mpv: MPVLib, name: String, value: String) {
        runCatching { mpv.setPropertyString(name, value) }
    }

    private fun setD(mpv: MPVLib, name: String, value: Double) {
        runCatching { mpv.setPropertyDouble(name, value) }
    }

    /** init 之前设置初始值，避免第一帧字幕闪一下默认样式 */
    fun applyOptions(mpv: MPVLib, context: Context) {
        runCatching { mpv.setOptionString("sub-auto", if (SettingsRepository.autoLoadSubtitles) "fuzzy" else "no") }
        runCatching { mpv.setOptionString("sub-visibility", "yes") }
        // 默认字幕语言顺序；没有语言标记的轨道也会按 sid 兜底选中
        runCatching { mpv.setOptionString("slang", "chi,zh,zho,eng,en") }
        // 关键：关掉 sub-use-margins，字幕才会贴在「视频内容区」底部；
        // 竖屏上下有黑边时不会掉到屏幕最下方，横屏全屏时自然贴底。
        runCatching { mpv.setOptionString("sub-use-margins", "no") }
        runCatching { mpv.setOptionString("sub-scale-with-window", "no") }
        runCatching { mpv.setOptionString("sub-font", fontFamily(SettingsRepository.subtitleFont)) }
        runCatching { mpv.setOptionString("sub-font-size", SettingsRepository.subtitleFontSize.toString()) }
        runCatching { mpv.setOptionString("sub-color", SettingsRepository.subtitleColor) }
        runCatching { mpv.setOptionString("sub-border-color", SettingsRepository.subtitleBorderColor) }
        runCatching { mpv.setOptionString("sub-border-size", SettingsRepository.subtitleBorderSize.toString()) }
        runCatching { mpv.setOptionString("sub-shadow-offset", "0") }
        runCatching { mpv.setOptionString("sub-bold", if (SettingsRepository.subtitleBold) "yes" else "no") }
        runCatching { mpv.setOptionString("sub-italic", if (SettingsRepository.subtitleItalic) "yes" else "no") }
        runCatching {
            mpv.setOptionString("sub-ass-override", if (SettingsRepository.subtitleIgnoreAss) "strip" else "no")
        }
        val (mx, my) = margins(context)
        runCatching { mpv.setOptionString("sub-margin-x", mx.toString()) }
        runCatching { mpv.setOptionString("sub-margin-y", my.toString()) }
    }

    /** 运行中改样式；reload=true 时重新载入当前字幕让字体/覆盖模式立即生效 */
    fun apply(mpv: MPVLib, context: Context, reload: Boolean) {
        setS(mpv, "sub-auto", if (SettingsRepository.autoLoadSubtitles) "fuzzy" else "no")
        setS(mpv, "sub-visibility", "yes")
        setS(mpv, "sub-use-margins", "no")
        setS(mpv, "sub-scale-with-window", "no")
        setS(mpv, "sub-font", fontFamily(SettingsRepository.subtitleFont))
        setD(mpv, "sub-font-size", SettingsRepository.subtitleFontSize.toDouble())
        setS(mpv, "sub-color", SettingsRepository.subtitleColor)
        setS(mpv, "sub-border-color", SettingsRepository.subtitleBorderColor)
        setD(mpv, "sub-border-size", SettingsRepository.subtitleBorderSize.toDouble())
        setD(mpv, "sub-shadow-offset", 0.0)
        setS(mpv, "sub-bold", if (SettingsRepository.subtitleBold) "yes" else "no")
        setS(mpv, "sub-italic", if (SettingsRepository.subtitleItalic) "yes" else "no")
        setS(mpv, "sub-ass-override", if (SettingsRepository.subtitleIgnoreAss) "strip" else "no")
        // sub-margin-x/y 是 init 前选项，运行中不可写；横竖屏边距差异靠 sub-use-margins 自动适配
        if (reload) runCatching { mpv.command(arrayOf("sub-reload")) }
    }

    /** 横竖屏给出不同上下边距：竖屏视频只占中间一条，边距不宜太大 */
    private fun margins(context: Context): Pair<Double, Double> {
        val portrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        // x 边距同时决定长句折行宽度；40 像素在 720p 竖/横屏都留出约 5% 安全边
        return if (portrait) 40.0 to 26.0 else 40.0 to 34.0
    }

    fun summary(): String = "${fontLabel(SettingsRepository.subtitleFont)} · ${SettingsRepository.subtitleFontSize}"
}
