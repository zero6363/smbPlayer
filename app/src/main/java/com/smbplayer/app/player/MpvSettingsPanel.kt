package com.smbplayer.app.player

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Window
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import com.smbplayer.app.R
import com.smbplayer.app.data.SpeedOptions
import dev.jdtech.mpv.MPVLib
import kotlin.math.roundToInt

/**
 * reex 风格播放设置面板：右侧半透明抽屉。
 * 顶部功能图标网格 + 循环 / 画面尺寸 / 定时器 / 倍速滑块。
 *
 * 面板只负责把用户选择映射到 mpv 属性；需要持久化的项（倍速）通过回调交回 Activity。
 */
class MpvSettingsPanel(
    private val activity: Activity,
    private val mpv: MPVLib,
    private val onSpeedChanged: (Float) -> Unit,
    private val getSpeed: () -> Float,
    private val onClosed: () -> Unit = {},
) {
    private var dialog: Dialog? = null
    private val handler = Handler(Looper.getMainLooper())

    // 面板状态
    private var mirror = false
    private var hwdec = false
    private var backgroundPlay = false
    private var repeatMode = 1 // 0=单曲 1=顺序 2=随机
    private var aspectMode = 0 // 0=适应 1=16:9 2=4:3 3=填充 4=裁剪
    private var panMode = false // 缩放平移开关
    private var sleepMinutes = 0
    private val sleepRunnable = Runnable { mpv.command(arrayOf("quit")) }

    fun show() {
        if (dialog?.isShowing == true) return
        val d = Dialog(activity, R.style.MpvSettingsDialog)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(R.layout.dialog_mpv_settings)
        d.window?.let { w ->
            w.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            w.setDimAmount(0f)
        }
        dialog = d

        // 面板宽度 = 屏幕 62%，右对齐
        val dm = android.util.DisplayMetrics()
        activity.resources.displayMetrics.let { dm.setTo(it) }
        d.findViewById<android.view.View>(R.id.settings_panel)?.layoutParams?.let { lp ->
            lp.width = (dm.widthPixels * 0.62f).toInt()
            d.findViewById<android.view.View>(R.id.settings_panel)?.layoutParams = lp
        }

        // 点空白关闭
        d.findViewById<android.view.View>(R.id.settings_scrim).setOnClickListener { d.dismiss() }

        // 顶部功能图标
        bindTile(d, R.id.tile_hwdec, {
            hwdec = !hwdec
            mpv.setOptionString("hwdec", if (hwdec) "auto-safe" else "no")
            Toast.makeText(activity, if (hwdec) "硬解开启（需重启播放生效）" else "软解", Toast.LENGTH_SHORT).show()
        }, { hwdec })

        bindTile(d, R.id.tile_mirror, {
            mirror = !mirror
            val rot = if (mirror) 180 else 0
            mpv.setPropertyInt("video-rotate", rot)
        }, { mirror })

        bindTile(d, R.id.tile_background, {
            backgroundPlay = !backgroundPlay
            Toast.makeText(activity, if (backgroundPlay) "后台播放开启" else "后台播放关闭", Toast.LENGTH_SHORT).show()
        }, { backgroundPlay })

        bindTile(d, R.id.tile_timer, {
            // 循环切换睡眠定时：关 → 15 → 30 → 60 → 关
            sleepMinutes = when (sleepMinutes) { 0 -> 15; 15 -> 30; 30 -> 60; else -> 0 }
            applySleepTimer()
        }, { sleepMinutes > 0 })

        bindTile(d, R.id.tile_pan, {
            panMode = !panMode
            mpv.setOptionString("keepaspect-window", if (panMode) "no" else "yes")
        }, { panMode })

        bindTile(d, R.id.tile_display, {
            Toast.makeText(activity, "显示设置：当前 ${aspectLabel(aspectMode)}", Toast.LENGTH_SHORT).show()
        }, { false })

        bindTile(d, R.id.tile_pip, {
            Toast.makeText(activity, "画中画暂未接入", Toast.LENGTH_SHORT).show()
        }, { false })

        // 循环
        bindChoice(d, R.id.repeat_one, R.id.group_repeat, 0) { repeatMode = 0 }
        bindChoice(d, R.id.repeat_all, R.id.group_repeat, 1) { repeatMode = 1 }
        bindChoice(d, R.id.repeat_shuffle, R.id.group_repeat, 2) { repeatMode = 2 }
        highlight(d, R.id.group_repeat, listOf(R.id.repeat_one, R.id.repeat_all, R.id.repeat_shuffle)[repeatMode])

        // 画面尺寸
        val aspectIds = listOf(R.id.aspect_fit, R.id.aspect_169, R.id.aspect_43, R.id.aspect_fill, R.id.aspect_crop)
        aspectIds.forEachIndexed { idx, id ->
            bindChoice(d, id, R.id.group_aspect, idx) { aspectMode = idx; applyAspect() }
        }
        highlight(d, R.id.group_aspect, aspectIds[aspectMode])

        // 定时器快捷
        d.findViewById<TextView>(R.id.sleep_off).setOnClickListener { sleepMinutes = 0; applySleepTimer() }
        d.findViewById<TextView>(R.id.sleep_15).setOnClickListener { sleepMinutes = 15; applySleepTimer() }
        d.findViewById<TextView>(R.id.sleep_30).setOnClickListener { sleepMinutes = 30; applySleepTimer() }

        // 倍速：1:1 复刻 reex 的 5 档滑块（0.5/1.0/1.75/2.0/4.0）
        val speeds = floatArrayOf(0.5f, 1.0f, 1.75f, 2.0f, 4.0f)
        val ticks = listOf(R.id.tick_0, R.id.tick_1, R.id.tick_2, R.id.tick_3, R.id.tick_4)
        val seek = d.findViewById<SeekBar>(R.id.seek_speed)
        val tvSpeed = d.findViewById<TextView>(R.id.tv_speed_value)
        // 找到最接近当前倍速的档位
        var currentIdx = 1
        speeds.forEachIndexed { i, sp -> if (kotlin.math.abs(sp - getSpeed()) < kotlin.math.abs(speeds[currentIdx] - getSpeed())) currentIdx = i }
        seek.progress = currentIdx
        tvSpeed.text = String.format("%.2f", speeds[currentIdx])

        fun applySpeed(idx: Int) {
            val i = idx.coerceIn(0, speeds.lastIndex)
            seek.progress = i
            val spd = speeds[i]
            tvSpeed.text = String.format("%.2f", spd)
            mpv.setPropertyDouble("speed", spd.toDouble())
            onSpeedChanged(spd)
            ticks.forEachIndexed { ti, id ->
                val tv = d.findViewById<TextView>(id) ?: return@forEachIndexed
                tv.setTextColor(if (ti == i) activity.getColor(R.color.player_accent) else Color.WHITE)
                tv.background = if (ti == i) activity.getDrawable(R.drawable.bg_hint_pill) else null
            }
        }
        applySpeed(currentIdx) // 初始化高亮

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) { if (fromUser) applySpeed(p) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        d.findViewById<android.view.View>(R.id.speed_minus).setOnClickListener { applySpeed(seek.progress - 1) }
        d.findViewById<android.view.View>(R.id.speed_plus).setOnClickListener { applySpeed(seek.progress + 1) }
        ticks.forEachIndexed { i, id ->
            d.findViewById<android.view.View>(id)?.setOnClickListener { applySpeed(i) }
        }

        d.setOnDismissListener { dialog = null; onClosed() }
        d.show()
    }

    private fun aspectLabel(mode: Int) = when (mode) {
        0 -> "适应"; 1 -> "16:9"; 2 -> "4:3"; 3 -> "填充"; 4 -> "裁剪"; else -> "适应"
    }

    private fun applyAspect() {
        val prop = when (aspectMode) {
            0 -> "-1"      // 适应：保持比例
            1 -> "16:9"
            2 -> "4:3"
            3 -> "-2"      // 填充：拉伸填满
            4 -> "-1"      // 裁剪：交给 mpv keepaspect
            else -> "-1"
        }
        mpv.setPropertyString("video-aspect-override", prop)
        mpv.setOptionString("keepaspect", if (aspectMode == 3) "no" else "yes")
    }

    private fun applySleepTimer() {
        handler.removeCallbacks(sleepRunnable)
        val tv = dialog?.findViewById<TextView>(R.id.tv_sleep)
        if (sleepMinutes > 0) {
            tv?.text = String.format("%02d:00:00", sleepMinutes)
            handler.postDelayed(sleepRunnable, sleepMinutes * 60_000L)
            Toast.makeText(activity, "${sleepMinutes} 分钟后停止播放", Toast.LENGTH_SHORT).show()
        } else {
            tv?.text = "00:00:00"
        }
    }

    /** 图标 tile：点击切换选中态（蓝色高亮） */
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
    private fun bindChoice(d: Dialog, id: Int, groupId: Int, index: Int, onPick: () -> Unit) {
        d.findViewById<android.view.View>(id)?.setOnClickListener {
            onPick()
            val group = d.findViewById<android.view.ViewGroup>(groupId) ?: return@setOnClickListener
            for (i in 0 until group.childCount) highlightChild(group.getChildAt(i), i == index)
        }
    }

    private fun highlight(d: Dialog, groupId: Int, selectedId: Int) {
        val group = d.findViewById<android.view.ViewGroup>(groupId) ?: return
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            highlightChild(child, child.id == selectedId)
        }
    }

    private fun highlightChild(child: android.view.View, selected: Boolean) {
        if (child !is TextView) return
        child.setTextColor(if (selected) activity.getColor(R.color.player_accent) else Color.WHITE)
        child.background = if (selected) activity.getDrawable(R.drawable.bg_hint_pill) else null
    }

    fun dismiss() { dialog?.dismiss(); handler.removeCallbacks(sleepRunnable) }
}
