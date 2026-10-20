package com.smbplayer.app.player

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.min

/**
 * 支持双击放大、双指缩放、放大后拖拽平移的图片视图。
 *
 * - 未放大时不消费水平拖动，方便外层 ViewPager2 左右翻页；
 * - 放大后按下即请求父容器不拦截，保证拖动平移的是图片而不是翻页；
 * - 单击通过 [onTap] 回调给外部（显示/隐藏控制栏）。
 */
class ZoomableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    /** 单击（区别于双击与拖拽） */
    var onTap: (() -> Unit)? = null

    private val drawableRect = RectF()
    private val displayRect = RectF()
    private val matrixValues = FloatArray(9)

    /** 当前总缩放系数（相对图片原始尺寸；初始值 = fitCenter 基准） */
    private var currentScale = 1f

    /** fitCenter 基准缩放 */
    private var minScale = 1f
    private val maxScale = 4f
    private var pinching = false
    private var animator: ValueAnimator? = null

    init {
        super.setScaleType(ScaleType.MATRIX)
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                animator?.cancel()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (factor == 0f) return true
                val target = (currentScale * factor).coerceIn(minScale, maxScale)
                val applied = target / currentScale
                if (applied == 1f) return true
                currentScale = target
                // 以双指中点为缩放中心
                imageMatrix.postScale(applied, applied, detector.focusX, detector.focusY)
                clampMatrix()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                pinching = false
            }
        },
    )

    private val tapDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDoubleTap(e: MotionEvent): Boolean {
                toggleZoom(e.x, e.y)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke()
                return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                // 放大状态下拖动平移；未放大不消费，让 ViewPager2 翻页
                if (pinching || !isZoomed()) return false
                imageMatrix.postTranslate(-distanceX, -distanceY)
                clampMatrix()
                return true
            }
        },
    )

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN ->
                // 已放大时按下就禁用父容器拦截，保证本次手势是平移图片
                if (isZoomed()) parent?.requestDisallowInterceptTouchEvent(true)
            // 第二根手指落下 = 双指缩放，同样不允许翻页
            MotionEvent.ACTION_POINTER_DOWN ->
                parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        tapDetector.onTouchEvent(event)
        return true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        resetMatrix()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resetMatrix()
    }

    /** 当前是否处于放大状态 */
    fun isZoomed(): Boolean = currentScale > minScale * 1.01f

    /** 双击：放大 <-> 复位，围绕点击点缩放 */
    private fun toggleZoom(cx: Float, cy: Float) {
        val d = drawable ?: return
        animator?.cancel()
        val start = Matrix(imageMatrix)
        val end: Matrix
        if (isZoomed()) {
            end = fitMatrix(d)
        } else {
            val target = (minScale * 2.5f).coerceAtMost(maxScale)
            end = Matrix(start)
            end.postScale(target / currentScale, target / currentScale, cx, cy)
            clampMatrix(end)
        }
        animateMatrix(start, end)
    }

    private fun fitMatrix(d: Drawable): Matrix {
        val iw = d.intrinsicWidth.toFloat()
        val ih = d.intrinsicHeight.toFloat()
        if (iw <= 0f || ih <= 0f || width == 0 || height == 0) return Matrix(imageMatrix)
        val scale = min(width.toFloat() / iw, height.toFloat() / ih)
        return Matrix().apply {
            setScale(scale, scale)
            postTranslate((width - iw * scale) / 2f, (height - ih * scale) / 2f)
        }
    }

    /** 回到 fitCenter 基准 */
    private fun resetMatrix() {
        val d = drawable ?: return
        animator?.cancel()
        val m = fitMatrix(d)
        minScale = matrixScale(m).coerceAtLeast(0.01f)
        currentScale = minScale
        imageMatrix = m
        invalidate()
    }

    private fun matrixScale(m: Matrix): Float {
        m.getValues(matrixValues)
        return matrixValues[Matrix.MSCALE_X]
    }

    /** 限制图片不露出视图外的空白：比视图大时贴边，比视图小时居中 */
    private fun clampMatrix(m: Matrix = imageMatrix) {
        val d = drawable ?: return
        drawableRect.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        m.mapRect(displayRect, drawableRect)
        val dx = when {
            displayRect.width() <= width -> (width - displayRect.width()) / 2f - displayRect.left
            displayRect.left > 0 -> -displayRect.left
            displayRect.right < width -> width - displayRect.right
            else -> 0f
        }
        val dy = when {
            displayRect.height() <= height -> (height - displayRect.height()) / 2f - displayRect.top
            displayRect.top > 0 -> -displayRect.top
            displayRect.bottom < height -> height - displayRect.bottom
            else -> 0f
        }
        m.postTranslate(dx, dy)
        if (m === imageMatrix) {
            currentScale = matrixScale(m)
            invalidate()
        }
    }

    private fun animateMatrix(start: Matrix, end: Matrix) {
        val startV = FloatArray(9).also { start.getValues(it) }
        val endV = FloatArray(9).also { end.getValues(it) }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220L
            addUpdateListener { a ->
                val f = a.animatedFraction
                val v = FloatArray(9) { i -> startV[i] + (endV[i] - startV[i]) * f }
                imageMatrix = Matrix().apply { setValues(v) }
                currentScale = matrixScale(imageMatrix)
                invalidate()
            }
            start()
        }
    }
}
