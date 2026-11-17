package com.vtlo.acid303

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import com.vtlo.guide.Guide
import com.vtlo.guide.GuideInput
import com.vtlo.guide.GuideOverlay
import com.vtlo.guide.GuidePen

/**
 * Full-screen overlay showing the French first-launch guide. It swallows every touch while open,
 * animates only while visible (and holds still when system animations are off), and calls [onDone]
 * once the user finishes or skips it.
 */
@android.annotation.SuppressLint("ViewConstructor") // created in code only
class GuideView(context: Context, startPage: Int = 0, private val onDone: () -> Unit) : View(context) {
    private val animate = ValueAnimator.areAnimatorsEnabled()
    val overlay = GuideOverlay(Guide.pages(GuideInput.TOUCH), animate).apply { repeat(startPage) { next() } }
    private val density = resources.displayMetrics.density
    private val coin = CoinPainter(context)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif", Typeface.BOLD)
    private val rect = RectF()
    private var running = false
    private var spokenPage = -1

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            invalidate()
            postOnAnimation(this)
        }
    }

    private val pen = object : GuidePen {
        override fun fillRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
            fill.color = color
            rect.set(x, y, x + w, y + h)
            canvas.drawRoundRect(rect, r, r, fill)
        }

        override fun strokeRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float) {
            stroke.color = color; stroke.strokeWidth = width
            rect.set(x, y, x + w, y + h)
            canvas.drawRoundRect(rect, r, r, stroke)
        }

        override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
            fill.color = color
            canvas.drawCircle(cx, cy, r, fill)
        }

        override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
            stroke.color = color; stroke.strokeWidth = width
            canvas.drawCircle(cx, cy, r, stroke)
        }

        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
            stroke.color = color; stroke.strokeWidth = width
            canvas.drawLine(x1, y1, x2, y2, stroke)
        }

        override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int, width: Float) {
            stroke.color = color; stroke.strokeWidth = width
            rect.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(rect, startDeg, sweepDeg, false, stroke)
        }

        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Int) {
            setFont(size, bold)
            text.color = color
            text.textAlign = when { align < 0 -> Paint.Align.LEFT; align > 0 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            canvas.drawText(s, x, y, text)
        }

        override fun measure(s: String, size: Float, bold: Boolean): Float {
            setFont(size, bold)
            return text.measureText(s)
        }

        override fun coin(cx: Float, cy: Float, r: Float, angle: Float) = coin.draw(canvas, cx, cy, r, if (animate) angle else 0f)
    }

    private lateinit var canvas: Canvas

    private fun setFont(size: Float, isBold: Boolean) {
        text.textSize = size
        text.typeface = if (isBold) bold else regular
    }

    init {
        isClickable = true
        isFocusable = true
        isFocusableInTouchMode = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        updateDescription()
    }

    private fun updateDescription() {
        val p = overlay.current
        contentDescription = "Guide, page ${overlay.page + 1} sur ${overlay.pages.size}. ${p.title}. ${p.body} " +
            "Touchez en bas à droite pour continuer, en bas à gauche pour passer."
        if (spokenPage != overlay.page && isAttachedToWindow) {
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
        }
        spokenPage = overlay.page
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        val on = isVisible && animate
        if (on == running) return
        running = on
        if (on) postOnAnimation(tick) else removeCallbacks(tick)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        running = false
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        this.canvas = canvas
        canvas.save()
        canvas.scale(density, density)
        overlay.paint(pen, 0f, 0f, width / density, height / density, SystemClock.uptimeMillis())
        canvas.restore()
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            overlay.tap(event.x / density, event.y / density)
            afterInput()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        overlay.next()
        afterInput()
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_SPACE -> overlay.next()
            KeyEvent.KEYCODE_DPAD_LEFT -> overlay.previous()
            KeyEvent.KEYCODE_ESCAPE -> overlay.skip()
            else -> return super.onKeyDown(keyCode, event)
        }
        afterInput()
        return true
    }

    private fun afterInput() {
        if (overlay.finished) {
            onDone()
            return
        }
        updateDescription()
        invalidate()
    }
}
