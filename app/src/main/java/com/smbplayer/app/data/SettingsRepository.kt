package com.smbplayer.app.data

import android.content.Context
import android.content.SharedPreferences

/**
 * 播放器设置持久化。
 *
 * 存储后台播放开关、长按临时倍速、播放页常驻倍速、手势灵敏度等用户偏好。
 */
object SettingsRepository {

    private const val PREFS_NAME = "smb_settings"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_SWIPE_SENSITIVITY = "swipe_sensitivity"
    private const val KEY_BACKGROUND_PLAY = "background_play"
    private const val KEY_NORMAL_SPEED = "normal_speed"

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
}
