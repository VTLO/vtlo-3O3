package com.vtlo.acid303.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.os.Bundle
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityNodeInfo.RangeInfo
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Rotary knob. Drag vertically (or horizontally) to turn; double-tap resets to
 * the default. Fully operable with TalkBack via scroll actions.
 */
@SuppressLint("ViewConstructor")
class KnobView(
    context: Context,
    private val label: String,
    private val default: Float,
    private val format: (Float) -> String,
    private val onChange: (Float) -> Unit,
    /** Detented knobs snap to this many positions (0 = continuous). */
    private val detents: Int = 0,
) : View(context) {

    var value: Float = default
        private set

    private var dragging = false
    private var accum = 0f
    private var lastX = 0f
    private var lastY = 0f

    private val labelPaint = textPaint(Palette.ink, sp(10.5f))
    private val valuePaint = textPaint(Palette.red, sp(11f))
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.knobBody }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.knobRim; style = Paint.Style.STROKE; strokeWidth = dp(2.5f)
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.panelEdge; style = Paint.Style.STROKE; strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.red; style = Paint.Style.STROKE; strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }
    private val pointerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.knobPointer; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Palette.inkMuted; strokeWidth = dp(1.2f)
    }
    private val arcRect = RectF()

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            setValue(default, fromUser = true)
            performHapticFeedback(
                if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
            )
            return true
        }
    })

    init {
        isFocusable = true
        contentDescription = label
        minimumWidth = dpi(64)
        minimumHeight = dpi(96)
    }

    fun setValue(v: Float, fromUser: Boolean = false) {
        var nv = v.coerceIn(0f, 1f)
        if (detents > 1) nv = Math.round(nv * (detents - 1)).toFloat() / (detents - 1)
        if (nv == value) return
        value = nv
        invalidate()
        if (fromUser) onChange(nv)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(dpi(72), widthMeasureSpec)
        val h = resolveSize((w * 1.3f).toInt().coerceIn(dpi(92), dpi(124)), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val labelH = sp(15f)
        val valueH = sp(15f)
        val avail = height - labelH - valueH
        val r = min(w * 0.36f, avail * 0.42f)
        val cx = w / 2f
        val cy = labelH + avail / 2f

        canvas.drawText(label, cx, sp(11f), labelPaint)

        val start = 135f
        val sweep = 270f
        arcRect.set(cx - r * 1.18f, cy - r * 1.18f, cx + r * 1.18f, cy + r * 1.18f)
        canvas.drawArc(arcRect, start, sweep, false, trackPaint)
        canvas.drawArc(arcRect, start, sweep * value, false, arcPaint)

        // Scale ticks
        for (i in 0..10) {
            val a = Math.toRadians((start + sweep * i / 10f).toDouble())
            val r1 = r * 1.32f
            val r2 = r * 1.42f
            canvas.drawLine(
                cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(),
                cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), tickPaint
            )
        }

        canvas.drawCircle(cx, cy, r, bodyPaint)
        canvas.drawCircle(cx, cy, r - dp(1.5f), rimPaint)
        val a = Math.toRadians((start + sweep * value).toDouble())
        canvas.drawLine(
            cx + (cos(a) * r * 0.25f).toFloat(), cy + (sin(a) * r * 0.25f).toFloat(),
            cx + (cos(a) * r * 0.85f).toFloat(), cy + (sin(a) * r * 0.85f).toFloat(), pointerPaint
        )

        valuePaint.color = if (dragging) Palette.red else Palette.inkMuted
        canvas.drawText(format(value), cx, height - sp(4f), valuePaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = true
                lastX = event.x
                lastY = event.y
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = lastY - event.y
                val dx = event.x - lastX
                lastX = event.x
                lastY = event.y
                // ~220dp of travel covers the full range
                val delta = (dy + dx) / dp(220f)
                if (detents > 1) {
                    accum += delta
                    val stepSize = 1f / (detents - 1)
                    while (accum >= stepSize * 0.6f) { setValue(value + stepSize, true); accum -= stepSize * 0.6f }
                    while (accum <= -stepSize * 0.6f) { setValue(value - stepSize, true); accum += stepSize * 0.6f }
                } else {
                    setValue(value + delta, fromUser = true)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                dragging = false
                accum = 0f
                invalidate()
            }
        }
        return true
    }

    override fun getAccessibilityClassName(): CharSequence = "android.widget.SeekBar"

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.rangeInfo = RangeInfo.obtain(RangeInfo.RANGE_TYPE_FLOAT, 0f, 1f, value)
        if (Build.VERSION.SDK_INT >= 30) info.stateDescription = format(value)
        else info.text = format(value)
        info.addAction(AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityAction.ACTION_SCROLL_BACKWARD)
        info.addAction(AccessibilityAction.ACTION_SET_PROGRESS)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val step = if (detents > 1) 1f / (detents - 1) else 0.05f
        when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> setValue(value + step, true)
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> setValue(value - step, true)
            android.R.id.accessibilityActionSetProgress -> {
                val v = arguments?.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE) ?: return false
                setValue(v, true)
            }
            else -> return super.performAccessibilityAction(action, arguments)
        }
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED)
        return true
    }
}
