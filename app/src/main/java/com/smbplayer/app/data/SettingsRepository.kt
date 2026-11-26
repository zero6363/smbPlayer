package com.smbplayer.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放器设置持久化。
 *
 * 存储后台播放开关、长按临时倍速、播放页常驻倍速、快退/快进步长、手势灵敏度、
 * 首页播放记录显示开关与显示条数、导出帧保存位置等用户偏好。
 */
object SettingsRepository {

    private const val PREFS_NAME = "smb_settings"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_SWIPE_SENSITIVITY = "swipe_sensitivity"
    private const val KEY_BACKGROUND_PLAY = "background_play"
    private const val KEY_NORMAL_SPEED = "normal_speed"
    private const val KEY_SORT_FIELD = "sort_field"
    private const val KEY_SORT_ASCENDING = "sort_ascending"
    private const val KEY_HISTORY_VISIBLE = "history_visible"
    private const val KEY_HISTORY_COUNT = "history_count"
    private const val KEY_FRAME_SAVE_TO_FOLDER = "frame_save_to_folder"
    private const val KEY_FRAME_SCREENSHOT_SUBTITLES = "frame_screenshot_subtitles"
    private const val KEY_ZONE_VOLUME_BRIGHTNESS = "zone_volume_brightness"
    private const val KEY_SHOW_IMAGES = "show_images"
    private const val KEY_SHOW_OTHER_FILES = "show_other_files"
    private const val KEY_UI_OPACITY = "ui_background_opacity"
    private const val KEY_FRAME_STEP = "frame_step_enabled"
    private const val KEY_SHOW_SEEK_BUTTONS = "show_seek_buttons"
    private const val KEY_SHOW_PREV_NEXT_BUTTONS = "show_prev_next_buttons"
    private const val KEY_SEEK_STEP = "seek_step_seconds"
    private const val KEY_AUTO_HIDE = "auto_hide_controls"
    private const val KEY_AUTO_HIDE_SECONDS = "auto_hide_seconds"
    private const val KEY_AUTO_LOAD_SUBS = "auto_load_subtitles"
    private const val KEY_SUB_FONT = "subtitle_font"
    private const val KEY_SUB_FONT_SIZE = "subtitle_font_size"
    private const val KEY_SUB_COLOR = "subtitle_color"
    private const val KEY_SUB_BORDER_COLOR = "subtitle_border_color"
    private const val KEY_SUB_BORDER_SIZE = "subtitle_border_size"
    private const val KEY_SUB_BOLD = "subtitle_bold"
    private const val KEY_SUB_ITALIC = "subtitle_italic"
    private const val KEY_SUB_IGNORE_ASS = "subtitle_ignore_ass"
    private const val KEY_HWDEC = "hwdec_mode"

    /** 首页播放记录：默认显示、默认最多 12 条 */
    const val DEFAULT_HISTORY_COUNT = 12
    const val MIN_HISTORY_COUNT = 1
    const val MAX_HISTORY_COUNT = 100

    /** 快退/快进步长：默认 5 秒，可在播放设置里改成 1~60 秒 */
    const val DEFAULT_SEEK_STEP_SECONDS = 5
    const val MIN_SEEK_STEP_SECONDS = 1
    const val MAX_SEEK_STEP_SECONDS = 60

    private val lock = Any()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    private fun store(): SharedPreferences =
        prefs ?: throw IllegalStateException("SettingsRepository.init() 未调用")

    /** 长按倍速，默认 2.0 */
    var playbackSpeed: Float
        get() = store().getFloat(KEY_SPEED, 2.0f)
        set(value) {
            store().edit().putFloat(KEY_SPEED, value).apply()
        }

    /** 滑动调进度灵敏度（像素/秒），默认 1000 */
    var swipeSensitivity: Int
        get() = store().getInt(KEY_SWIPE_SENSITIVITY, 1000)
        set(value) {
            store().edit().putInt(KEY_SWIPE_SENSITIVITY, value).apply()
        }

    /**
     * 后台播放：离开播放页（息屏 / 切到后台）时是否继续播放，默认 true。
     * 关闭后，播放页 onStop 会暂停，避免离开时后台出声。
     */
    var backgroundPlay: Boolean
        get() = store().getBoolean(KEY_BACKGROUND_PLAY, true)
        set(value) {
            store().edit().putBoolean(KEY_BACKGROUND_PLAY, value).apply()
        }

    /**
     * 播放页常驻倍速（区别于「长按临时倍速」playbackSpeed）。
     * 默认 1.0f，每次 playAt 时应用到播放器，从而继承到下一集。
     */
    var normalSpeed: Float
        get() = store().getFloat(KEY_NORMAL_SPEED, 1.0f)
        set(value) {
            store().edit().putFloat(KEY_NORMAL_SPEED, value).apply()
        }

    /** 设置面板与控制栏的背景不透明度（reex 的「背景透明度」，1.0 = 原始不透明） */
    var uiBackgroundOpacity: Float
        get() = store().getFloat(KEY_UI_OPACITY, 0.78f)
        set(value) {
            store().edit().putFloat(KEY_UI_OPACITY, value.coerceIn(0.1f, 1.0f)).apply()
        }

    /** 帧步进：开启后在导出帧左侧显示逐帧后退/前进按钮，快退/快进保持秒级跳转 */
    var frameStepEnabled: Boolean
        get() = store().getBoolean(KEY_FRAME_STEP, false)
        set(value) {
            store().edit().putBoolean(KEY_FRAME_STEP, value).apply()
        }

    /** 快退/快进每次跳转的秒数，默认 [DEFAULT_SEEK_STEP_SECONDS] 秒 */
    var seekStepSeconds: Int
        get() = store().getInt(KEY_SEEK_STEP, DEFAULT_SEEK_STEP_SECONDS)
            .coerceIn(MIN_SEEK_STEP_SECONDS, MAX_SEEK_STEP_SECONDS)
        set(value) {
            store().edit()
                .putInt(KEY_SEEK_STEP, value.coerceIn(MIN_SEEK_STEP_SECONDS, MAX_SEEK_STEP_SECONDS))
                .apply()
        }

    /** 播放页是否显示快退/快进按钮，默认显示；关闭后仅隐藏按钮，手势和进度条仍可 seek */
    var showSeekButtons: Boolean
        get() = store().getBoolean(KEY_SHOW_SEEK_BUTTONS, true)
        set(value) {
            store().edit().putBoolean(KEY_SHOW_SEEK_BUTTONS, value).apply()
        }

    /** 播放页是否显示上一个/下一个视频按钮，默认显示；关闭不影响播放列表和自动连播 */
    var showPrevNextButtons: Boolean
        get() = store().getBoolean(KEY_SHOW_PREV_NEXT_BUTTONS, true)
        set(value) {
            store().edit().putBoolean(KEY_SHOW_PREV_NEXT_BUTTONS, value).apply()
        }

    /** 控制栏/顶部菜单自动隐藏，默认开启 */
    var autoHideControls: Boolean
        get() = store().getBoolean(KEY_AUTO_HIDE, true)
        set(value) {
            store().edit().putBoolean(KEY_AUTO_HIDE, value).apply()
        }

    /** 自动隐藏延迟秒数，默认 4 秒（reex 默认 4s） */
    var autoHideSeconds: Int
        get() = store().getInt(KEY_AUTO_HIDE_SECONDS, 4)
        set(value) {
            store().edit().putInt(KEY_AUTO_HIDE_SECONDS, value.coerceIn(1, 20)).apply()
        }

    /** 自动加载同名字幕：默认开启（播放时自动找 srt/ass/ssa/vtt） */
    var autoLoadSubtitles: Boolean
        get() = store().getBoolean(KEY_AUTO_LOAD_SUBS, true)
        set(value) {
            store().edit().putBoolean(KEY_AUTO_LOAD_SUBS, value).apply()
        }

    /** 字幕字体：default / sans / serif / mono */
    var subtitleFont: String
        get() = store().getString(KEY_SUB_FONT, "default") ?: "default"
        set(value) {
            store().edit().putString(KEY_SUB_FONT, value).apply()
        }

    /** 字幕字号（reex 默认 55） */
    var subtitleFontSize: Int
        get() = store().getInt(KEY_SUB_FONT_SIZE, 55)
        set(value) {
            store().edit().putInt(KEY_SUB_FONT_SIZE, value.coerceIn(24, 120)).apply()
        }

    /** 字幕字体颜色 #RRGGBB */
    var subtitleColor: String
        get() = store().getString(KEY_SUB_COLOR, "#FFFFFF") ?: "#FFFFFF"
        set(value) {
            store().edit().putString(KEY_SUB_COLOR, value).apply()
        }

    /** 字幕边框颜色 #RRGGBB */
    var subtitleBorderColor: String
        get() = store().getString(KEY_SUB_BORDER_COLOR, "#000000") ?: "#000000"
        set(value) {
            store().edit().putString(KEY_SUB_BORDER_COLOR, value).apply()
        }

    /** 字幕边框粗细，reex 默认 3 */
    var subtitleBorderSize: Int
        get() = store().getInt(KEY_SUB_BORDER_SIZE, 3)
        set(value) {
            store().edit().putInt(KEY_SUB_BORDER_SIZE, value.coerceIn(0, 10)).apply()
        }

    var subtitleBold: Boolean
        get() = store().getBoolean(KEY_SUB_BOLD, false)
        set(value) {
            store().edit().putBoolean(KEY_SUB_BOLD, value).apply()
        }

    var subtitleItalic: Boolean
        get() = store().getBoolean(KEY_SUB_ITALIC, false)
        set(value) {
            store().edit().putBoolean(KEY_SUB_ITALIC, value).apply()
        }

    /** 忽略 ASS 样式：开启后用上面的字体/颜色/边框强制覆盖 ASS 字幕 */
    var subtitleIgnoreAss: Boolean
        get() = store().getBoolean(KEY_SUB_IGNORE_ASS, false)
        set(value) {
            store().edit().putBoolean(KEY_SUB_IGNORE_ASS, value).apply()
        }

    /** 硬件解码：no / auto / mediacodec-copy（默认 no，保证当前 AAR 兼容性） */
    var hwdecMode: String
        get() = store().getString(KEY_HWDEC, "no") ?: "no"
        set(value) {
            store().edit().putString(KEY_HWDEC, value).apply()
        }

    /**
     * 目录列表排序字段：见 [SortOptions.FIELD_NAME] / [FIELD_SIZE] / [FIELD_CREATED]。
     * 默认按文件名。
     */
    var sortField: Int
        get() = store().getInt(KEY_SORT_FIELD, SortOptions.DEFAULT_FIELD)
        set(value) {
            store().edit().putInt(KEY_SORT_FIELD, value).apply()
        }

    /** 目录列表排序方向：true 升序，false 降序。默认升序。 */
    var sortAscending: Boolean
        get() = store().getBoolean(KEY_SORT_ASCENDING, SortOptions.DEFAULT_ASCENDING)
        set(value) {
            store().edit().putBoolean(KEY_SORT_ASCENDING, value).apply()
        }

    /**
     * 首页是否显示播放记录。默认显示。
     * 关闭只是隐藏首页卡片，播放进度仍照常保存，不影响播放页续播。
     */
    var historyVisible: Boolean
        get() = store().getBoolean(KEY_HISTORY_VISIBLE, true)
        set(value) {
            store().edit().putBoolean(KEY_HISTORY_VISIBLE, value).apply()
        }

    /**
     * 首页播放记录最多显示多少条，默认 [DEFAULT_HISTORY_COUNT]（12 条）。
     * 只控制首页展示数量；底层播放进度记录仍按 [PlaybackProgressRepository] 的上限保留，
     * 避免条数调小后其它视频的续播位置被丢掉。
     */
    var historyCount: Int
        get() = store().getInt(KEY_HISTORY_COUNT, DEFAULT_HISTORY_COUNT)
            .coerceIn(MIN_HISTORY_COUNT, MAX_HISTORY_COUNT)
        set(value) {
            store().edit()
                .putInt(KEY_HISTORY_COUNT, value.coerceIn(MIN_HISTORY_COUNT, MAX_HISTORY_COUNT))
                .apply()
        }

    /**
     * 播放页「导出当前帧」的保存位置：true = 保存到手机相册 Pictures/SMBPlayer（默认），
     * false = 写到视频所在的 SMB 文件夹。
     * 底层 key 沿用旧的 frame_save_to_folder 并取反，老配置自动迁移：
     * 旧的 false（相册）→ 取反 true（相册）；旧的 true（视频目录）→ 取反 false（视频目录）。
     */
    var frameSaveToGallery: Boolean
        get() = !store().getBoolean(KEY_FRAME_SAVE_TO_FOLDER, false)
        set(value) {
            store().edit().putBoolean(KEY_FRAME_SAVE_TO_FOLDER, !value).apply()
        }

    /**
     * 导出当前帧时是否把字幕一起截进去，默认 true（保持原来 PixelCopy 抓到的画面）。
     * 关闭时只在抓帧瞬间隐藏 mpv 的 sub-visibility，抓完立即恢复，不影响当前播放的字幕显示。
     */
    var frameScreenshotWithSubtitles: Boolean
        get() = store().getBoolean(KEY_FRAME_SCREENSHOT_SUBTITLES, true)
        set(value) {
            store().edit().putBoolean(KEY_FRAME_SCREENSHOT_SUBTITLES, value).apply()
        }

    /**
     * 播放页「左右分区纵滑调音量/亮度」：左半屏纵滑调亮度、右半屏纵滑调音量。
     * 默认关闭；关闭时纵滑手势不生效（横滑调进度不受影响）。
     */
    var zoneVolumeBrightness: Boolean
        get() = store().getBoolean(KEY_ZONE_VOLUME_BRIGHTNESS, false)
        set(value) {
            store().edit().putBoolean(KEY_ZONE_VOLUME_BRIGHTNESS, value).apply()
        }

    /**
     * 目录列表是否显示图片类文件（jpg/png/gif 等）。默认显示（保持原行为）。
     * 与 showOtherFiles 都关闭时，列表只显示视频（含 ts/flv/avi 等）。
     */
    var showImages: Boolean
        get() = store().getBoolean(KEY_SHOW_IMAGES, true)
        set(value) {
            store().edit().putBoolean(KEY_SHOW_IMAGES, value).apply()
        }

    /**
     * 目录列表是否显示「其他类」文件（既非视频也非图片，如字幕/文档/压缩包）。
     * 默认显示；与 showImages 都关闭时列表只显示视频。
     */
    var showOtherFiles: Boolean
        get() = store().getBoolean(KEY_SHOW_OTHER_FILES, true)
        set(value) {
            store().edit().putBoolean(KEY_SHOW_OTHER_FILES, value).apply()
        }
}
