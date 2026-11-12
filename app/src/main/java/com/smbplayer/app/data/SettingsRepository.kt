package com.smbplayer.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放器设置持久化。
 *
 * 存储后台播放开关、长按临时倍速、播放页常驻倍速、手势灵敏度、
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

    /** 首页播放记录：默认显示、默认最多 12 条 */
    const val DEFAULT_HISTORY_COUNT = 12
    const val MIN_HISTORY_COUNT = 1
    const val MAX_HISTORY_COUNT = 100

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
     * 播放页「导出当前帧」的保存位置：true = 写到视频所在的 SMB 文件夹，
     * false = 保存到手机相册 Pictures/SMBPlayer（默认，保持原有行为）。
     */
    var frameSaveToVideoFolder: Boolean
        get() = store().getBoolean(KEY_FRAME_SAVE_TO_FOLDER, false)
        set(value) {
            store().edit().putBoolean(KEY_FRAME_SAVE_TO_FOLDER, value).apply()
        }
}
