package com.vtlo.acid303

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Two-faced emblem that keeps flipping around its vertical axis: the robot on one side, the winking smiley on the other.
 * Purely decorative — it never takes touches, focus or accessibility attention, and it only animates while visible
 * (and stands still on its front face when the system has animations turned off).
 */
class CoinView(context: Context) : View(context) {
    private val painter = CoinPainter(context)
    private var running = false
    private val start = SystemClock.uptimeMillis()

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    init {
        isClickable = false
        isLongClickable = false
        isFocusable = false
        isSoundEffectsEnabled = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }

    // Touches fall through to whatever is underneath.
    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?) = false

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val animate = isVisible && ValueAnimator.areAnimatorsEnabled()
        if (animate == running) return
        running = animate
        if (animate) postOnAnimation(tick) else removeCallbacks(tick)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val angle = if (running) 2.0 * PI * ((SystemClock.uptimeMillis() - start) % PERIOD_MS) / PERIOD_MS else 0.0
        val size = min(width - paddingLeft - paddingRight, height - paddingTop - paddingBottom).toFloat()
        if (size <= 0f) return
        val cx = paddingLeft + (width - paddingLeft - paddingRight) / 2f
        val cy = paddingTop + (height - paddingTop - paddingBottom) / 2f
        painter.draw(canvas, cx, cy, size / 2f, angle.toFloat())
    }

    private companion object {
        const val PERIOD_MS = 4000L
    }
}

/** Draws the coin turned by an angle: horizontal squash, back face past 90°, rim and shading. Shared with the guide. */
class CoinPainter(context: Context) {
    private val front: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.coin_front)
    private val back: Bitmap = BitmapFactory.decodeResource(context.resources, R.drawable.coin_back)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF46220F.toInt() }
    private val shade = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    fun draw(canvas: Canvas, cx: Float, cy: Float, r: Float, angle: Float) {
        val c = cos(angle)
        val s = sin(angle)
        val half = r * max(abs(c), 0.04f)
        // Edge thickness shows on the trailing side as the coin turns.
        val edge = 2f * r * 0.035f * s
        dst.set(cx - half + edge, cy - r, cx + half + edge, cy + r)
        canvas.drawOval(dst, rim)
        dst.set(cx - half, cy - r, cx + half, cy + r)
        canvas.drawBitmap(if (c >= 0f) front else back, null, dst, paint)
        // Darken the face as it turns away from the viewer.
        val dim = ((1f - abs(c)) * 120f).toInt()
        if (dim > 0) {
            shade.color = dim shl 24
            canvas.drawOval(dst, shade)
        }
    }
}
