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
 * - 超过宽度：匀速横向滚动，到末尾/回到开头各停顿一下，循环往复；
 * - 控件离屏（RecyclerView 回收）时自动停止，重新出现再启动。
 */
class MarqueeTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    private var marqueeAnimator: ValueAnimator? = null
    private val scrollSpeedPxPerMs: Float =
        32f * resources.displayMetrics.density / 1000f // 约 32dp/秒，贴近系统跑马灯速度

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
        scrollTo(0, 0)
        stopMarquee()
        startMarqueeIfNeeded()
    }

    private fun startMarqueeIfNeeded() {
        if (visibility != VISIBLE || width == 0) return
        val layout = layout ?: return
        if (layout.lineCount == 0) return

        // 横向滚动模式下 layout 宽度即文本完整宽度
        val textWidth = layout.getLineWidth(0).toInt() + paddingLeft + paddingRight
        val distance = textWidth - width
        if (distance <= 0) return // 放得下，不用滚

        val duration = (distance / scrollSpeedPxPerMs).toLong().coerceAtLeast(1500L)
        marqueeAnimator = ValueAnimator.ofInt(0, distance).apply {
            this.duration = duration
            interpolator = MarqueeInterpolator()
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            addUpdateListener { animator ->
                scrollTo(animator.animatedValue as Int, 0)
            }
            start()
        }
    }

    private fun stopMarquee() {
        marqueeAnimator?.cancel()
        marqueeAnimator = null
    }

    /** 两端各停顿约 15% 的时间，读起来更舒服 */
    private class MarqueeInterpolator : TimeInterpolator {
        override fun getInterpolation(input: Float): Float {
            val hold = 0.15f
            return when {
                input <= hold -> 0f
                input >= 1f - hold -> 1f
                else -> (input - hold) / (1f - 2f * hold)
            }
        }
    }
}
