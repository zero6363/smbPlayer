package com.smbplayer.app.player

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.smbplayer.app.R

/**
 * 播放页专用 Toast：用独立透明 Dialog 画一个深色胶囊提示，底板透明度直接复用
 * [PlayerTheme.opacity]（和设置里的「背景透明度」同源）。
 *
 * 系统 Toast 的底板由 SystemUI 绘制，targetSdk 35 下无法改背景 alpha；自定义视图
 * 也会被系统忽略。这里用不抢焦点、不拦截触摸的 Dialog 实现，既能跟随透明度，
 * 又能盖在设置面板 / 倒计时等子弹窗之上。
 *
 * 同一条提示队列里只保留一个窗口：连续快进/切集时直接改 TextView 文案并重置
 * 计时，不反复 dismiss + show 新 Dialog，避免等上一条 2 秒结束才显示下一条。
 */
object PlayerToast {
    private const val DURATION_MS = 2_000L
    private const val BOTTOM_MARGIN_DP = 120

    private val handler = Handler(Looper.getMainLooper())
    private var currentDialog: Dialog? = null
    private var currentText: TextView? = null
    private var currentActivity: Activity? = null
    private var hideRunnable: Runnable? = null

    fun show(activity: Activity, message: CharSequence) {
        if (activity.isFinishing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed) return
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { show(activity, message) }
            return
        }

        // 同一个播放页：复用现有 Dialog，立即换字并重新计时
        val dialog = currentDialog
        if (dialog != null && currentActivity === activity && dialog.isShowing) {
            currentText?.text = message
            restartHideTimer(activity, dialog)
            return
        }

        dismiss()

        val text = TextView(activity).apply {
            this.text = message
            setTextColor(ContextCompat.getColor(activity, R.color.player_control_text))
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(dp(activity, 18), dp(activity, 10), dp(activity, 18), dp(activity, 10))
            maxWidth = dp(activity, 320)
            background = ContextCompat.getDrawable(activity, R.drawable.bg_control_panel)?.mutate()
            PlayerTheme.applyBackground(this)
        }
        val container = FrameLayout(activity).apply {
            setPadding(dp(activity, 24), 0, dp(activity, 24), 0)
            addView(
                text,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER
                )
            )
        }

        val newDialog = Dialog(activity, R.style.PlayerToastDialog)
        newDialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        newDialog.setContentView(container)
        newDialog.setCancelable(false)
        newDialog.setCanceledOnTouchOutside(false)
        newDialog.window?.let { w ->
            w.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            w.setDimAmount(0f)
            w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            )
            w.setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            w.setLayout(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT
            )
            w.attributes = w.attributes.apply { y = dp(activity, BOTTOM_MARGIN_DP) }
        }
        runCatching { newDialog.show() }
            .onFailure { return }
        currentDialog = newDialog
        currentText = text
        currentActivity = activity
        restartHideTimer(activity, newDialog)
    }

    private fun restartHideTimer(activity: Activity, dialog: Dialog) {
        hideRunnable?.let { handler.removeCallbacks(it) }
        val runnable = Runnable {
            if (currentDialog === dialog) {
                runCatching { dialog.dismiss() }
                currentDialog = null
                currentText = null
                currentActivity = null
                hideRunnable = null
            }
        }
        hideRunnable = runnable
        handler.postDelayed(runnable, DURATION_MS)
        // 文案长短变化后让窗口重新按 WRAP_CONTENT 量一次，避免旧宽度裁掉新提示
        dialog.window?.decorView?.post {
            if (currentDialog === dialog && dialog.isShowing) {
                runCatching {
                    dialog.window?.setLayout(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT
                    )
                }
            }
        }
        if (activity.isFinishing || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed)) {
            dismiss()
        }
    }

    /** Activity 销毁 / 旋转重建前必须收起，否则会报 WindowLeaked */
    fun dismiss() {
        hideRunnable?.let { handler.removeCallbacks(it) }
        hideRunnable = null
        currentDialog?.let { runCatching { it.dismiss() } }
        currentDialog = null
        currentText = null
        currentActivity = null
    }

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
