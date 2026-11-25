package com.smbplayer.app.player

import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.smbplayer.app.R
import com.smbplayer.app.data.SettingsRepository
import dev.jdtech.mpv.MPVLib
import kotlin.math.roundToInt

/**
 * reex 风格播放设置面板。
 * - 横屏：右侧 62% 宽半透明抽屉，图标竖排网格 + 循环 / 画面尺寸 / 定时器 / 倍速
 * - 竖屏：全宽底部半透明面板（约 53% 高），图标横排 + 分组标题/分隔线 + 铺开的选项
 *
 * 面板只负责把用户选择映射到 mpv 属性；需要持久化的项（倍速）通过回调交回 Activity。
 */
class MpvSettingsPanel(
    private val activity: Activity,
    private val mpv: MPVLib,
    private val onSpeedChanged: (Float) -> Unit,
    private val getSpeed: () -> Float,
    private val onRepeatModeChanged: (Int) -> Unit = {},
    private val onPanModeChanged: (Boolean) -> Unit = {},
    private val onFrameStepChanged: (Boolean) -> Unit = {},
    private val onAutoHideChanged: (Boolean, Int) -> Unit = { _, _ -> },
    private val onThemeOpacityChanged: () -> Unit = {},
    private val onClosed: () -> Unit = {},
) {
    private var dialog: Dialog? = null
    private val handler = Handler(Looper.getMainLooper())

    // 面板状态
    private var mirror = false
    private var hwdecMode = "no"
    private var backgroundPlay = false
    private var repeatMode = 1 // 0=单曲 1=顺序 2=随机 3=播完停止（不自动下一个）
    private var aspectMode = 0 // 0=自动 1=1:1 2=16:9 3=4:3 4=裁剪 5=禁用缩放
    private var panMode = false // 缩放平移开关
    private var frameStep = false // 帧步进：在导出帧左侧显示逐帧按钮
    private var autoHide = true // 控制栏自动隐藏
    private var autoHideSeconds = 4
    private var sleepEndAt = 0L // 定时关闭的结束时刻（elapsedRealtime），0 表示未设置
    private var sleepTimerDialog: Dialog? = null
    private val sleepTicker = object : Runnable {
        override fun run() {
            val remain = remainingSleepSeconds()
            refreshSleepText()
            if (remain <= 0) {
                sleepEndAt = 0L
                runCatching { mpv.command(arrayOf("quit")) }
                runCatching { activity.finish() }
                return
            }
            handler.postDelayed(this, 1000)
        }
    }

    fun show() {
        if (dialog?.isShowing == true) return
        val d = Dialog(activity, R.style.MpvSettingsDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_mpv_settings)
        d.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setDimAmount(0f)
            // Dialog 会新建自己的窗口，默认会带状态栏/导航栏；这里同步沉浸式，避免面板打开时冒出系统栏
            applyImmersive(w)
        }
        dialog = d

        // 面板尺寸：横屏右侧 62% 宽抽屉；竖屏全宽底部面板（高度约 53%，内容多时可滚动）
        val dm = android.util.DisplayMetrics()
        activity.resources.displayMetrics.let { dm.setTo(it) }
        val portrait = activity.resources.configuration.orientation ==
            android.content.res.Configuration.ORIENTATION_PORTRAIT
        val panel = d.findViewById<android.view.View>(R.id.settings_panel)
        panel?.layoutParams?.let { lp ->
            if (portrait) {
                lp.width = WindowManager.LayoutParams.MATCH_PARENT
                lp.height = (dm.heightPixels * 0.53f).toInt()
            } else {
                lp.width = (dm.widthPixels * 0.62f).toInt()
                lp.height = WindowManager.LayoutParams.MATCH_PARENT
            }
            panel.layoutParams = lp
        }
        PlayerTheme.applyBackground(panel)

        // 主题：背景透明度（作用于控制栏、本面板和所有深色弹窗）
        bindThemeControls(d)
        // 播放行为：帧步进、自动隐藏界面及延迟
        bindPlaybackBehavior(d)
        // 字幕：自动加载 + 字幕样式入口
        bindSubtitleControls(d)
        // 硬件解码：no / auto / mediacodec-copy
        bindHwdecRow(d)

        // 点空白关闭
        d.findViewById<android.view.View>(R.id.settings_scrim).setOnClickListener { d.dismiss() }

        // 顶部功能图标
        bindTile(d, R.id.tile_hwdec, {
            showHwdecPicker(d)
        }, { SettingsRepository.hwdecMode != "no" })

        bindTile(d, R.id.tile_mirror, {
            mirror = !mirror
            val rot = if (mirror) 180 else 0
            mpv.setPropertyInt("video-rotate", rot)
        }, { mirror })

        bindTile(d, R.id.tile_background, {
            backgroundPlay = !backgroundPlay
            Toast.makeText(activity, if (backgroundPlay) "后台播放开启" else "后台播放关闭", Toast.LENGTH_SHORT).show()
        }, { backgroundPlay })

        bindTile(d, R.id.tile_pan, {
            panMode = !panMode
            onPanModeChanged(panMode)
            Toast.makeText(
                activity,
                if (panMode) "缩放平移已开启：双指捏合缩放、拖动平移" else "缩放平移已关闭，画面已复位",
                Toast.LENGTH_SHORT
            ).show()
        }, { panMode })

        // 循环（第 4 项：播完停止，不自动播放下一个）
        val repeatIds = listOf(R.id.repeat_one, R.id.repeat_all, R.id.repeat_shuffle, R.id.repeat_none)
        repeatIds.forEachIndexed { idx, id ->
            bindChoice(d, id, idx, repeatIds) { repeatMode = idx; onRepeatModeChanged(idx) }
        }
        highlightChoice(d, repeatIds, repeatMode)

        // 画面尺寸：自动 / 1:1 / 16:9 / 4:3 / 裁剪 / 禁用缩放
        val aspectIds = listOf(
            R.id.aspect_auto, R.id.aspect_11, R.id.aspect_169, R.id.aspect_43,
            R.id.aspect_crop, R.id.aspect_noscale
        )
        aspectIds.forEachIndexed { idx, id ->
            bindChoice(d, id, idx, aspectIds) { aspectMode = idx; applyAspect() }
        }
        highlightChoice(d, aspectIds, aspectMode)

        // 定时器：点击时间显示弹「时/分/秒」设置框
        d.findViewById<TextView>(R.id.tv_sleep)?.setOnClickListener { showTimerPicker() }
        refreshSleepText()

        // 倍速：0.25 步进（index 0 = 0.25x ... index 39 = 10.0x），−/+ 每次 ±0.25x
        val speedStep = 0.25f
        val speedMaxIdx = 39 // 0.25 * (39+1) = 10.0
        val presets = floatArrayOf(0.3f, 0.5f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f)
        val presetIds = listOf(
            R.id.speed_preset_0, R.id.speed_preset_1, R.id.speed_preset_2, R.id.speed_preset_3,
            R.id.speed_preset_4, R.id.speed_preset_5, R.id.speed_preset_6,
        )
        val seek = d.findViewById<SeekBar>(R.id.seek_speed)
        val tvSpeed = d.findViewById<TextView>(R.id.tv_speed_value)
        val currentIdx = ((getSpeed() / speedStep).roundToInt() - 1).coerceIn(0, speedMaxIdx)
        seek.progress = currentIdx

        // 直接按倍速值应用（滑块 index 与快捷值都走这里）
        fun applySpeedValue(spd: Float, idx: Int) {
            val i = idx.coerceIn(0, speedMaxIdx)
            seek.progress = i
            tvSpeed.text = String.format("%.2f", spd)
            mpv.setPropertyDouble("speed", spd.toDouble())
            onSpeedChanged(spd)
            presetIds.forEachIndexed { pi, id ->
                val tv = d.findViewById<TextView>(id) ?: return@forEachIndexed
                val hit = kotlin.math.abs(spd - presets[pi]) < 0.01f
                tv.setTextColor(if (hit) activity.getColor(R.color.player_accent) else Color.WHITE)
            }
        }
        fun applySpeed(idx: Int) {
            val i = idx.coerceIn(0, speedMaxIdx)
            applySpeedValue(speedStep * (i + 1), i)
        }
        applySpeed(currentIdx) // 初始化高亮

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) applySpeed(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        d.findViewById<android.view.View>(R.id.speed_minus).setOnClickListener { applySpeed(seek.progress - 1) }
        d.findViewById<android.view.View>(R.id.speed_plus).setOnClickListener { applySpeed(seek.progress + 1) }
        // 常用倍速快捷切换（0.3 不在 0.25 网格上，直接设值；滑块位置取最接近的档）
        presetIds.forEachIndexed { i, id ->
            d.findViewById<android.view.View>(id)?.setOnClickListener {
                applySpeedValue(presets[i], (presets[i] / speedStep).roundToInt() - 1)
            }
        }

        d.setOnDismissListener { dialog = null; onClosed() }
        d.show()
    }

    /** 背景透明度：0.1~1.0，实时改控制栏 / 面板 alpha，并写回设置 */
    private fun bindThemeControls(d: Dialog) {
        val seek = d.findViewById<SeekBar>(R.id.seek_opacity) ?: return
        val tv = d.findViewById<TextView>(R.id.tv_theme_opacity) ?: return
        val panel = d.findViewById<android.view.View>(R.id.settings_panel)

        fun render(progress: Int) {
            val opacity = (progress / 100f).coerceIn(0.1f, 1.0f)
            tv.text = String.format("背景透明度 %.2f", opacity)
            SettingsRepository.uiBackgroundOpacity = opacity
            PlayerTheme.applyBackground(panel)
            onThemeOpacityChanged()
        }

        seek.progress = (SettingsRepository.uiBackgroundOpacity * 100f).roundToInt().coerceIn(10, 100)
        render(seek.progress)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser) render(p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    /** 帧步进开关（控制播放页导出帧左侧的逐帧按钮）+ 自动隐藏界面开关/延迟（reex 默认 4s，可 1~20s 自定义） */
    private fun bindPlaybackBehavior(d: Dialog) {
        frameStep = SettingsRepository.frameStepEnabled
        autoHide = SettingsRepository.autoHideControls
        autoHideSeconds = SettingsRepository.autoHideSeconds

        val swFrame = d.findViewById<Switch>(R.id.switch_frame_step)
        swFrame?.isChecked = frameStep
        swFrame?.setOnCheckedChangeListener { _, checked ->
            frameStep = checked
            SettingsRepository.frameStepEnabled = checked
            onFrameStepChanged(checked)
        }

        val swAuto = d.findViewById<Switch>(R.id.switch_auto_hide)
        val seek = d.findViewById<SeekBar>(R.id.seek_auto_hide)
        val tvValue = d.findViewById<TextView>(R.id.tv_auto_hide_value)
        val tvDelay = d.findViewById<TextView>(R.id.tv_auto_hide_delay)

        fun renderDelay() {
            tvValue?.text = "${autoHideSeconds}s"
            tvDelay?.text = "隐藏延迟 ${autoHideSeconds}s"
            seek?.isEnabled = autoHide
            seek?.alpha = if (autoHide) 1f else 0.4f
            tvDelay?.alpha = if (autoHide) 1f else 0.4f
        }

        seek?.progress = (autoHideSeconds - 1).coerceIn(0, 19)
        swAuto?.isChecked = autoHide
        renderDelay()

        swAuto?.setOnCheckedChangeListener { _, checked ->
            autoHide = checked
            SettingsRepository.autoHideControls = checked
            renderDelay()
            onAutoHideChanged(autoHide, autoHideSeconds)
        }
        seek?.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                autoHideSeconds = (p + 1).coerceIn(1, 20)
                SettingsRepository.autoHideSeconds = autoHideSeconds
                renderDelay()
                onAutoHideChanged(autoHide, autoHideSeconds)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
    }

    private fun applyAspect() {
        // 自动 = 完全按视频原始比例（mpv 的 no override）；其余为强制比例/裁剪/拉伸
        when (aspectMode) {
            1 -> {
                mpv.setPropertyString("video-aspect-override", "1:1")
                mpv.setPropertyString("keepaspect", "yes")
                mpv.setPropertyDouble("panscan", 0.0)
            }
            2 -> {
                mpv.setPropertyString("video-aspect-override", "16:9")
                mpv.setPropertyString("keepaspect", "yes")
                mpv.setPropertyDouble("panscan", 0.0)
            }
            3 -> {
                mpv.setPropertyString("video-aspect-override", "4:3")
                mpv.setPropertyString("keepaspect", "yes")
                mpv.setPropertyDouble("panscan", 0.0)
            }
            4 -> {
                mpv.setPropertyString("video-aspect-override", "no")
                mpv.setPropertyString("keepaspect", "yes")
                mpv.setPropertyDouble("panscan", 1.0)
            }
            5 -> {
                mpv.setPropertyString("video-aspect-override", "no")
                mpv.setPropertyString("keepaspect", "no")
                mpv.setPropertyDouble("panscan", 0.0)
            }
            else -> {
                mpv.setPropertyString("video-aspect-override", "no")
                mpv.setPropertyString("keepaspect", "yes")
                mpv.setPropertyDouble("panscan", 0.0)
            }
        }
    }

    /** 定时关闭：参考 reex，弹「时 / 分 / 秒」设置框（0:00:00 表示取消） */
    private fun showTimerPicker() {
        val dlg = Dialog(activity, R.style.MpvSettingsDialog)
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(R.layout.dialog_mpv_timer)
        val dm = android.util.DisplayMetrics()
        activity.resources.displayMetrics.let { dm.setTo(it) }
        dlg.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setDimAmount(0f)
            applyImmersive(w)
        }
        dlg.findViewById<android.view.View>(R.id.timer_panel)?.layoutParams?.let { lp ->
            lp.width = minOf((dm.widthPixels * 0.84f).toInt(), (340 * dm.density).toInt())
            dlg.findViewById<android.view.View>(R.id.timer_panel)?.layoutParams = lp
        }
        PlayerTheme.applyBackground(dlg.findViewById(R.id.timer_panel))
        dlg.findViewById<android.view.View>(R.id.timer_scrim)?.setOnClickListener { dlg.dismiss() }

        val remain = remainingSleepSeconds()
        var hh = remain / 3600
        var mm = (remain % 3600) / 60
        var ss = remain % 60
        val tvH = dlg.findViewById<TextView>(R.id.tv_hour)
        val tvM = dlg.findViewById<TextView>(R.id.tv_minute)
        val tvS = dlg.findViewById<TextView>(R.id.tv_second)

        fun render() {
            tvH?.text = String.format("%02d", hh)
            tvM?.text = String.format("%02d", mm)
            tvS?.text = String.format("%02d", ss)
        }
        fun step(unit: Int, delta: Int) {
            when (unit) {
                0 -> hh = (hh + delta + 24) % 24
                1 -> mm = (mm + delta + 60) % 60
                else -> ss = (ss + delta + 60) % 60
            }
            render()
        }
        fun setTotal(total: Int) {
            hh = total / 3600; mm = (total % 3600) / 60; ss = total % 60
            render()
        }
        render()

        // ▲▼ 支持长按连续调节：按住 0.3s 后每 90ms 走一步，松开即停
        val repeatHandler = android.os.Handler(android.os.Looper.getMainLooper())
        var repeatRunnable: Runnable? = null
        fun stopRepeat() {
            repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
            repeatRunnable = null
        }
        fun bindRepeat(view: android.view.View?, unit: Int, delta: Int) {
            if (view == null) return
            view.setOnClickListener { step(unit, delta) }
            view.setOnLongClickListener {
                step(unit, delta)
                val r = object : Runnable {
                    override fun run() {
                        step(unit, delta)
                        repeatHandler.postDelayed(this, 90L)
                    }
                }
                repeatRunnable = r
                repeatHandler.postDelayed(r, 320L)
                true
            }
            view.setOnTouchListener { _, ev ->
                if (ev.actionMasked == android.view.MotionEvent.ACTION_UP ||
                    ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL
                ) stopRepeat()
                false
            }
        }
        bindRepeat(dlg.findViewById(R.id.btn_hour_up), 0, 1)
        bindRepeat(dlg.findViewById(R.id.btn_hour_down), 0, -1)
        bindRepeat(dlg.findViewById(R.id.btn_minute_up), 1, 1)
        bindRepeat(dlg.findViewById(R.id.btn_minute_down), 1, -1)
        bindRepeat(dlg.findViewById(R.id.btn_second_up), 2, 1)
        bindRepeat(dlg.findViewById(R.id.btn_second_down), 2, -1)

        // 数字本身支持上下拖拽调节（reex 滚筒手感），窗口内点击不再穿透到 scrim
        fun bindDrag(view: TextView?, unit: Int) {
            if (view == null) return
            val stepPx = 32f * activity.resources.displayMetrics.density
            var lastY = 0f
            view.setOnTouchListener { v, ev ->
                when (ev.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        lastY = ev.rawY
                        true
                    }
                    android.view.MotionEvent.ACTION_MOVE -> {
                        val dy = ev.rawY - lastY
                        if (dy > stepPx) {
                            step(unit, -1)
                            lastY = ev.rawY
                        } else if (dy < -stepPx) {
                            step(unit, 1)
                            lastY = ev.rawY
                        }
                        true
                    }
                    android.view.MotionEvent.ACTION_UP -> true
                    else -> true
                }
            }
            view.isClickable = true
        }
        bindDrag(tvH, 0)
        bindDrag(tvM, 1)
        bindDrag(tvS, 2)
        dlg.findViewById<android.view.View>(R.id.chip_timer_15)?.setOnClickListener { setTotal(15 * 60) }
        dlg.findViewById<android.view.View>(R.id.chip_timer_30)?.setOnClickListener { setTotal(30 * 60) }
        dlg.findViewById<android.view.View>(R.id.chip_timer_60)?.setOnClickListener { setTotal(60 * 60) }
        dlg.findViewById<android.view.View>(R.id.chip_timer_off)?.setOnClickListener { setTotal(0) }

        dlg.findViewById<android.view.View>(R.id.btn_timer_cancel)?.setOnClickListener { dlg.dismiss() }
        dlg.findViewById<android.view.View>(R.id.btn_timer_ok)?.setOnClickListener {
            startSleepTimer(hh * 3600 + mm * 60 + ss)
            dlg.dismiss()
        }
        dlg.setOnDismissListener {
            stopRepeat()
            sleepTimerDialog = null
            // 关闭子弹窗后系统栏可能被系统恢复，这里把面板和 Activity 窗口重新压回沉浸式
            applyImmersive(dialog?.window)
            applyImmersive(activity.window)
        }
        sleepTimerDialog = dlg
        dlg.show()
    }

    /** 让 Dialog 窗口保持沉浸式（隐藏状态栏 / 导航栏） */
    private fun applyImmersive(w: Window?) {
        if (w == null) return
        @Suppress("DEPRECATION")
        w.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    /** 设置倒计时；0 秒 = 关闭定时 */
    private fun startSleepTimer(totalSeconds: Int) {
        handler.removeCallbacks(sleepTicker)
        sleepEndAt = if (totalSeconds > 0) {
            android.os.SystemClock.elapsedRealtime() + totalSeconds * 1000L
        } else 0L
        refreshSleepText()
        if (totalSeconds > 0) {
            handler.post(sleepTicker)
            Toast.makeText(activity, "${hms(totalSeconds)} 后停止播放", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(activity, "定时关闭已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private fun remainingSleepSeconds(): Int {
        if (sleepEndAt <= 0L) return 0
        val left = sleepEndAt - android.os.SystemClock.elapsedRealtime()
        return if (left <= 0) 0 else (left / 1000).toInt()
    }

    private fun hms(seconds: Int) = String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)

    /** 把剩余时间写回面板的 00:00:00（面板没打开时忽略） */
    private fun refreshSleepText() {
        dialog?.findViewById<TextView>(R.id.tv_sleep)?.text = hms(remainingSleepSeconds())
    }

    /** 图标 tile：点击切换选中态（蓝色高亮） */
    /** 字幕分组：自动加载同名字幕 + 字幕样式入口 */
    private fun bindSubtitleControls(d: Dialog) {
        val sw = d.findViewById<Switch>(R.id.switch_auto_sub)
        sw?.isChecked = SettingsRepository.autoLoadSubtitles
        sw?.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.autoLoadSubtitles = checked
            SubtitleStyle.apply(mpv, activity, reload = false)
            Toast.makeText(
                activity,
                if (checked) "自动加载字幕开启" else "自动加载字幕关闭（下次播放生效）",
                Toast.LENGTH_SHORT
            ).show()
        }
        d.findViewById<View>(R.id.row_sub_style)?.setOnClickListener { showSubtitleStyleDialog() }
        refreshSubStyleSummary()
    }

    private fun refreshSubStyleSummary() {
        dialog?.findViewById<TextView>(R.id.tv_sub_style_summary)?.text = SubtitleStyle.summary()
    }

    /** 硬件解码行：和 reex 一样三选一，当前值直接显示在行尾 */
    private fun bindHwdecRow(d: Dialog) {
        hwdecMode = SettingsRepository.hwdecMode
        d.findViewById<TextView>(R.id.tv_hwdec_mode)?.text = hwdecMode
        d.findViewById<View>(R.id.row_hwdec)?.setOnClickListener { showHwdecPicker(d) }
    }

    private fun showHwdecPicker(parent: Dialog) {
        val options = listOf(
            "no" to "no（软解）",
            "auto" to "auto（自动）",
            "mediacodec-copy" to "mediacodec-copy",
        )
        showChoiceDialog("硬件解码", options, SettingsRepository.hwdecMode) { key ->
            hwdecMode = key
            SettingsRepository.hwdecMode = key
            runCatching { mpv.setOptionString("hwdec", key) }
            parent.findViewById<TextView>(R.id.tv_hwdec_mode)?.text = key
            refreshTileIfPresent(parent, R.id.tile_hwdec, key != "no")
            Toast.makeText(activity, "硬件解码已切换为 $key，重新打开视频生效", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshTileIfPresent(d: Dialog, id: Int, on: Boolean) {
        d.findViewById<View>(id)?.let { refreshTile(it, on) }
    }

    /** 字幕样式页：逐项即时生效，长句靠 sub-margin-x 自动折行 */
    private fun showSubtitleStyleDialog() {
        val d = Dialog(activity, R.style.MpvSettingsDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val view = activity.layoutInflater.inflate(R.layout.dialog_mpv_subtitle_style, null)
        d.setContentView(view)
        val dm = android.util.DisplayMetrics()
        activity.resources.displayMetrics.let { dm.setTo(it) }
        val panel = view.findViewById<View>(R.id.sub_style_panel)
        panel?.layoutParams?.let { lp ->
            lp.width = minOf((dm.widthPixels * 0.9f).toInt(), dp(560))
            lp.height = (dm.heightPixels * 0.86f).toInt()
            panel.layoutParams = lp
        }
        d.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setDimAmount(0.25f)
            applyImmersive(w)
        }
        view.findViewById<View>(R.id.sub_style_scrim)?.setOnClickListener { d.dismiss() }

        val tvFont = view.findViewById<TextView>(R.id.tv_sub_font)
        val tvFontSize = view.findViewById<TextView>(R.id.tv_sub_font_size)
        val tvBorderSize = view.findViewById<TextView>(R.id.tv_sub_border_size)
        val fontSwatch = view.findViewById<View>(R.id.sub_font_color_swatch)
        val borderSwatch = view.findViewById<View>(R.id.sub_border_color_swatch)
        val swBold = view.findViewById<SwitchCompat>(R.id.sw_sub_bold)
        val swItalic = view.findViewById<SwitchCompat>(R.id.sw_sub_italic)
        val swIgnore = view.findViewById<SwitchCompat>(R.id.sw_sub_ignore_ass)
        val preview = view.findViewById<TextView>(R.id.tv_sub_preview)

        // Android 7 的 Switch 在 ScrollView 里方向会反：显式 LTR，并给关/开不同配色，
        // 保证一眼能看出状态（开启为蓝色轨道，关闭为灰色）。
        val accent = ContextCompat.getColor(activity, R.color.player_accent)
        val accentTrack = Color.argb(150, Color.red(accent), Color.green(accent), Color.blue(accent))
        val switchStates = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        fun paintToggle(sw: SwitchCompat?) {
            sw?.layoutDirection = View.LAYOUT_DIRECTION_LTR
            sw?.thumbTintList = ColorStateList(switchStates, intArrayOf(accent, 0x88FFFFFF.toInt()))
            sw?.trackTintList = ColorStateList(switchStates, intArrayOf(accentTrack, 0x33FFFFFF))
        }
        paintToggle(swBold)
        paintToggle(swItalic)
        paintToggle(swIgnore)

        fun paintSwatch(v: View?, hex: String) {
            val color = runCatching { Color.parseColor(hex) }.getOrDefault(Color.WHITE)
            v?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                setStroke(dp(1), 0x66FFFFFF)
            }
        }

        fun refreshPreview() {
            preview?.let {
                it.textSize = (SettingsRepository.subtitleFontSize * 0.4f).coerceIn(12f, 36f)
                it.setTextColor(runCatching { Color.parseColor(SettingsRepository.subtitleColor) }
                    .getOrDefault(Color.WHITE))
                val style = when {
                    SettingsRepository.subtitleBold && SettingsRepository.subtitleItalic -> Typeface.BOLD_ITALIC
                    SettingsRepository.subtitleBold -> Typeface.BOLD
                    SettingsRepository.subtitleItalic -> Typeface.ITALIC
                    else -> Typeface.NORMAL
                }
                it.typeface = Typeface.create(SubtitleStyle.fontFamily(SettingsRepository.subtitleFont), style)
                if (SettingsRepository.subtitleBorderSize > 0) {
                    val bc = runCatching { Color.parseColor(SettingsRepository.subtitleBorderColor) }
                        .getOrDefault(Color.BLACK)
                    it.setShadowLayer(SettingsRepository.subtitleBorderSize.toFloat(), 0f, 0f, bc)
                } else {
                    it.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
                }
            }
        }

        fun applyNow() {
            SubtitleStyle.apply(mpv, activity, reload = true)
            refreshPreview()
        }

        fun refreshAll() {
            tvFont?.text = SubtitleStyle.fontLabel(SettingsRepository.subtitleFont)
            tvFontSize?.text = SettingsRepository.subtitleFontSize.toString()
            tvBorderSize?.text = SettingsRepository.subtitleBorderSize.toString()
            paintSwatch(fontSwatch, SettingsRepository.subtitleColor)
            paintSwatch(borderSwatch, SettingsRepository.subtitleBorderColor)
            if (swBold?.isChecked != SettingsRepository.subtitleBold) {
                swBold?.isChecked = SettingsRepository.subtitleBold
            }
            if (swItalic?.isChecked != SettingsRepository.subtitleItalic) {
                swItalic?.isChecked = SettingsRepository.subtitleItalic
            }
            if (swIgnore?.isChecked != SettingsRepository.subtitleIgnoreAss) {
                swIgnore?.isChecked = SettingsRepository.subtitleIgnoreAss
            }
            refreshPreview()
        }

        view.findViewById<View>(R.id.row_sub_font)?.setOnClickListener {
            showChoiceDialog(
                "字体",
                SubtitleStyle.FONTS.map { it.key to it.label },
                SettingsRepository.subtitleFont
            ) { key ->
                SettingsRepository.subtitleFont = key
                applyNow()
                refreshAll()
            }
        }
        view.findViewById<View>(R.id.row_sub_font_color)?.setOnClickListener {
            showColorDialog("字体颜色", SettingsRepository.subtitleColor) { hex ->
                SettingsRepository.subtitleColor = hex
                applyNow()
                refreshAll()
            }
        }
        view.findViewById<View>(R.id.row_sub_font_size)?.setOnClickListener {
            showValueDialog("字体大小", 24, 120, SettingsRepository.subtitleFontSize) { value ->
                SettingsRepository.subtitleFontSize = value
                applyNow()
                refreshAll()
            }
        }
        view.findViewById<View>(R.id.row_sub_border_color)?.setOnClickListener {
            showColorDialog("边框颜色", SettingsRepository.subtitleBorderColor) { hex ->
                SettingsRepository.subtitleBorderColor = hex
                applyNow()
                refreshAll()
            }
        }
        view.findViewById<View>(R.id.row_sub_border_size)?.setOnClickListener {
            showValueDialog("边框大小", 0, 10, SettingsRepository.subtitleBorderSize) { value ->
                SettingsRepository.subtitleBorderSize = value
                applyNow()
                refreshAll()
            }
        }
        swBold?.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.subtitleBold = checked
            applyNow()
        }
        swItalic?.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.subtitleItalic = checked
            applyNow()
        }
        swIgnore?.setOnCheckedChangeListener { _, checked ->
            SettingsRepository.subtitleIgnoreAss = checked
            applyNow()
        }
        view.findViewById<View>(R.id.btn_sub_style_reset)?.setOnClickListener {
            SettingsRepository.subtitleFont = "default"
            SettingsRepository.subtitleFontSize = 55
            SettingsRepository.subtitleColor = "#FFFFFF"
            SettingsRepository.subtitleBorderColor = "#000000"
            SettingsRepository.subtitleBorderSize = 3
            SettingsRepository.subtitleBold = false
            SettingsRepository.subtitleItalic = false
            SettingsRepository.subtitleIgnoreAss = false
            applyNow()
            refreshAll()
        }
        view.findViewById<View>(R.id.btn_sub_style_done)?.setOnClickListener { d.dismiss() }
        d.setOnDismissListener {
            refreshSubStyleSummary()
            applyImmersive(dialog?.window)
            applyImmersive(activity.window)
        }
        refreshAll()
        d.show()
    }

    /** 深色单选列表（硬件解码/字体） */
    private fun showChoiceDialog(
        title: String,
        options: List<Pair<String, String>>,
        current: String,
        onPick: (String) -> Unit,
    ) {
        val d = Dialog(activity, R.style.MpvSettingsDialog)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.bg_dialog_panel)
            setPadding(dp(18), dp(14), dp(18), dp(8))
        }
        panel.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(10))
        })
        options.forEach { (key, label) ->
            val on = key == current
            panel.addView(TextView(activity).apply {
                text = if (on) "$label   ✓" else label
                setTextColor(if (on) activity.getColor(R.color.player_accent) else Color.WHITE)
                textSize = 14f
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(16), dp(8), dp(16))
                isClickable = true
                setOnClickListener {
                    onPick(key)
                    d.dismiss()
                }
            })
        }
        panel.addView(TextView(activity).apply {
            text = "取消"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            setOnClickListener { d.dismiss() }
        })
        d.setContentView(panel)
        d.window?.let { w ->
            w.setDimAmount(0f)
            applyImmersive(w)
            w.setLayout(minOf((activity.resources.displayMetrics.widthPixels * 0.8f).toInt(), dp(360)),
                WindowManager.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    /** 颜色面板：8 个常用字幕颜色，点选即生效 */
    private fun showColorDialog(title: String, current: String, onPick: (String) -> Unit) {
        val colors = listOf(
            "#FFFFFF" to "白色", "#FFF44F" to "黄色", "#4FFBFF" to "青色", "#7CFF7C" to "绿色",
            "#FF9ECF" to "粉色", "#FFB35C" to "橙色", "#7CA8FF" to "蓝色", "#000000" to "黑色",
        )
        val d = Dialog(activity, R.style.MpvSettingsDialog)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.bg_dialog_panel)
            setPadding(dp(18), dp(14), dp(18), dp(16))
        }
        panel.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(12))
        })
        val grid = GridLayout(activity).apply { columnCount = 4 }
        colors.forEach { (hex, name) ->
            val color = Color.parseColor(hex)
            val swatch = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(dp(if (hex == current) 3 else 1), if (hex == current) activity.getColor(R.color.player_accent) else 0x66FFFFFF)
                }
                contentDescription = name
                setOnClickListener {
                    onPick(hex)
                    d.dismiss()
                }
            }
            val lp = GridLayout.LayoutParams().apply {
                width = dp(48)
                height = dp(48)
                setMargins(dp(10), dp(6), dp(10), dp(6))
            }
            grid.addView(swatch, lp)
        }
        panel.addView(grid)
        d.setContentView(panel)
        d.window?.let { w ->
            w.setDimAmount(0f)
            applyImmersive(w)
            w.setLayout(minOf((activity.resources.displayMetrics.widthPixels * 0.82f).toInt(), dp(330)),
                WindowManager.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    /** 数字调节弹层：字体大小 / 边框大小 */
    private fun showValueDialog(title: String, min: Int, max: Int, current: Int, onSet: (Int) -> Unit) {
        val d = Dialog(activity, R.style.MpvSettingsDialog)
        val panel = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = activity.getDrawable(R.drawable.bg_dialog_panel)
            setPadding(dp(18), dp(14), dp(18), dp(10))
        }
        panel.addView(TextView(activity).apply {
            text = title
            setTextColor(Color.WHITE)
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(4), dp(4), dp(4), dp(6))
        })
        val tvValue = TextView(activity).apply {
            text = current.toString()
            setTextColor(activity.getColor(R.color.player_accent))
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(0, dp(2), 0, dp(8))
        }
        panel.addView(tvValue)
        val seek = SeekBar(activity).apply {
            this.max = max - min
            progress = (current - min).coerceIn(0, max - min)
            progressDrawable = activity.getDrawable(R.drawable.bg_seek_progress)
            thumb = activity.getDrawable(R.drawable.bg_seek_thumb)
            splitTrack = false
        }
        panel.addView(seek, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(34)
        ))
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                tvValue.text = (min + p).toString()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        val actions = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(TextView(activity).apply {
            text = "取消"
            setTextColor(0xAAFFFFFF.toInt())
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            setOnClickListener { d.dismiss() }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        actions.addView(TextView(activity).apply {
            text = "确定"
            setTextColor(activity.getColor(R.color.player_accent))
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
            isClickable = true
            setOnClickListener {
                onSet(min + seek.progress)
                d.dismiss()
            }
        }, LinearLayout.LayoutParams(0, dp(48), 1f))
        panel.addView(actions)
        d.setContentView(panel)
        d.window?.let { w ->
            w.setDimAmount(0f)
            applyImmersive(w)
            w.setLayout(minOf((activity.resources.displayMetrics.widthPixels * 0.82f).toInt(), dp(340)),
                WindowManager.LayoutParams.WRAP_CONTENT)
        }
        d.show()
    }

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    private fun bindTile(d: Dialog, id: Int, onClick: () -> Unit, isOn: () -> Boolean) {
        val tile = d.findViewById<android.view.View>(id) ?: return
        tile.setOnClickListener { onClick(); refreshTile(tile, isOn()) }
        refreshTile(tile, isOn())
    }

    private fun refreshTile(tile: android.view.View, on: Boolean) {
        val color = if (on) activity.getColor(R.color.player_accent) else Color.WHITE
        for (i in 0 until (tile as android.view.ViewGroup).childCount) {
            val child = tile.getChildAt(i)
            when (child) {
                is android.widget.ImageView -> child.setColorFilter(color)
                is TextView -> child.setTextColor(color)
            }
        }
    }

    /** 分组内的选项：点击高亮当前项 */
    private fun bindChoice(d: Dialog, id: Int, index: Int, ids: List<Int>, onPick: () -> Unit) {
        d.findViewById<android.view.View>(id)?.setOnClickListener {
            onPick()
            highlightChoice(d, ids, index)
        }
    }

    /** 选中项用白色加粗标示（参考 reex 的单色极简风格，避免蓝字压在蓝画面上看不清） */
    private fun highlightChoice(d: Dialog, ids: List<Int>, selected: Int) {
        ids.forEachIndexed { i, id ->
            val tv = d.findViewById<TextView>(id) ?: return@forEachIndexed
            val on = i == selected
            tv.setTextColor(if (on) Color.WHITE else 0xCCFFFFFF.toInt())
            tv.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            tv.background = null
        }
    }

    /** 供播放页控制栏显示倒计时提醒 */
    fun remainingTimerSeconds(): Int = remainingSleepSeconds()

    /** 控制栏倒计时点击时直接调起定时设置框 */
    fun openTimerPicker() = showTimerPicker()

    fun dismiss() { dialog?.dismiss() }
}
