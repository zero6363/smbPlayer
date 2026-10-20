package com.smbplayer.app.widget

import android.animation.TimeInterpolator
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 长文本自动横向滚动（跑马灯）的 TextView。
 *
 * 为什么不用系统的 `ellipsize="marquee"`：系统跑马灯依赖 TextView 内部状态机
 * （selected/focused 时机），在 RecyclerView 列表项里经常不滚动。这里自己用
 * ValueAnimator 驱动 scrollX，行为确定：
 * - 文本不超过控件宽度：不滚动，正常显示；
 * - 超过宽度：匀速横向滚动，首/尾各停顿一小会，循环往复；
 * - 控件离屏（RecyclerView 回收）时自动停止，重新出现再启动。
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var marqueeAnimator: ValueAnimator? = null

    /** 已设置的滚动距离；相同距离且动画仍在跑就不重启，避免反复 measure 打断滚动 */
    private var marqueeDistance = -1

    /** 基础速度：约 60dp/秒 */
    private val baseSpeedPxPerMs: Float = 60f * resources.displayMetrics.density / 1000f

    init {
        // 单行 + 横向滚动 + 不省略：超出部分靠 scrollX 滚动展示，不用省略号截断
        setSingleLine(true)
        ellipsize = null
        setHorizontallyScrolling(true)
        isHorizontalFadingEdgeEnabled = true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        restartMarquee()
    }

    override fun onTextChanged(
        text: CharSequence?,
        start: Int,
        lengthBefore: Int,
        lengthAfter: Int,
    ) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter)
        restartMarquee()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        restartMarquee()
    }

    override fun onDetachedFromWindow() {
        stopMarquee()
        super.onDetachedFromWindow()
    }

    private fun restartMarquee() {
        val distance = scrollDistance()
        // 同一距离且动画还在跑：别重启（RecyclerView 会反复 measure，
        // 一重启就永远停在起点，表现为「滚不动」）
        if (distance == marqueeDistance && marqueeAnimator?.isRunning == true) return
        marqueeDistance = distance
        scrollTo(0, 0)
        stopMarquee()
        if (distance <= 0) return // 放得下，不用滚

        // 速度：基础 60dp/秒；超长文本兜底加速，保证一趟不超过 6 秒，不会「半天不动」
        val speed = maxOf(baseSpeedPxPerMs, distance / MAX_TRAVERSE_MS)
        val traverseMs = distance / speed
        // 两端各停 HOLD_MS；占比过高（文本很短）时压到 25%
        val holdFraction = (HOLD_MS / traverseMs).coerceIn(0f, 0.25f)
        marqueeAnimator = ValueAnimator.ofInt(0, distance).apply {
            duration = traverseMs.toLong().coerceAtLeast(600L)
            interpolator = HoldInterpolator(holdFraction)
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animator ->
                scrollTo(animator.animatedValue as Int, 0)
            }
            start()
        }
    }

    /** 文本宽度超出控件宽度的像素数；<=0 表示放得下 */
    private fun scrollDistance(): Int {
        if (visibility != VISIBLE || width == 0) return -1
        val layout = layout ?: return -1
        if (layout.lineCount == 0) return -1
        // 横向滚动模式下 layout 宽度即文本完整宽度
        val textWidth = layout.getLineWidth(0).toInt() + paddingLeft + paddingRight
        return textWidth - width
    }

    private fun stopMarquee() {
        marqueeAnimator?.cancel()
        marqueeAnimator = null
    }

    /** 两端各停顿 [holdFraction] 比例的时间，读起来更舒服 */
    private class HoldInterpolator(private val holdFraction: Float) : TimeInterpolator {
        override fun getInterpolation(input: Float): Float = when {
            input <= holdFraction -> 0f
            input >= 1f - holdFraction -> 1f
            else -> (input - holdFraction) / (1f - 2f * holdFraction)
        }
    }

    private companion object {
        /** 首/尾停顿时长（毫秒） */
        const val HOLD_MS = 500f

        /** 一趟滚动最长时长（毫秒），超过则提速 */
        const val MAX_TRAVERSE_MS = 6000f
    }
}
