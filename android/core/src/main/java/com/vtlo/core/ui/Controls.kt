package com.vtlo.core.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import kotlin.math.max
import kotlin.math.min

/** Hardware-style push button with an optional status LED. */
@SuppressLint("ViewConstructor")
open class PadButton(
    context: Context,
    var text: String,
    private val hasLed: Boolean = false,
    private val dark: Boolean = true,
) : View(context) {

    var active = false
        set(v) {
            if (field != v) {
                field = v
                if (Build.VERSION.SDK_INT >= 30) stateDescription = if (v) "on" else "off"
                invalidate()
            }
        }

    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Palette.panelEdge
    }
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = textPaint(if (dark) Palette.buttonText else Palette.ink, sp(12f))

    init {
        isClickable = true
        isFocusable = true
        contentDescription = text
        minimumHeight = dpi(48)
        minimumWidth = dpi(48)
    }

    fun setLabel(t: String) {
        text = t
        contentDescription = t
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            resolveSize(dpi(64), widthMeasureSpec),
            resolveSize(dpi(if (hasLed) 56 else 48), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(2f)
        rect.set(inset, inset, width - inset, height - inset)
        bg.color = when {
            isPressed -> if (dark) Palette.buttonDarkPressed else Palette.panelShade
            else -> if (dark) Palette.buttonDark else Palette.panel
        }
        val radius = dp(6f)
        canvas.drawRoundRect(rect, radius, radius, bg)
        canvas.drawRoundRect(rect, radius, radius, edge)
        var textY = height / 2f + label.textSize * 0.35f
        if (hasLed) {
            val lr = dp(4f)
            val ly = dp(12f)
            if (active) {
                glow.color = Palette.ledOn
                glow.alpha = 70
                canvas.drawCircle(width / 2f, ly, lr * 2.2f, glow)
            }
            led.color = if (active) Palette.ledOn else Palette.ledOff
            canvas.drawCircle(width / 2f, ly, lr, led)
            textY += dp(7f)
        } else if (active) {
            edge.color = Palette.ledOn
            edge.strokeWidth = dp(2f)
            canvas.drawRoundRect(rect, radius, radius, edge)
            edge.color = Palette.panelEdge
            edge.strokeWidth = dp(1f)
        }
        label.textSize = min(sp(12f), width / max(4f, text.length * 0.75f))
        canvas.drawText(text, width / 2f, textY, label)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return super.performClick()
    }

    override fun getAccessibilityClassName(): CharSequence =
        if (hasLed) "android.widget.ToggleButton" else Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        if (hasLed) {
            info.isCheckable = true
            info.isChecked = active
        }
    }
}

/** One step of the sequencer: playhead LED, note, accent/slide flags. */
@SuppressLint("ViewConstructor")
class StepCell(context: Context, val index: Int) : View(context) {
    var note = 36
    var gate = false
    var accent = false
    var slide = false
    var selectedStep = false
    var playing = false
    var inLength = true

    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = textPaint(Palette.inkMuted, sp(9f))
    private val noteText = textPaint(Palette.ink, sp(13f))
    private val flag = textPaint(Palette.red, sp(9f))

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
    }

    fun refresh() {
        contentDescription = buildString {
            append("Step ").append(index + 1).append(", ")
            if (gate) append(noteName(note)) else append("rest")
            if (gate && accent) append(", accent")
            if (gate && slide) append(", slide")
            if (!inLength) append(", beyond pattern length")
        }
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(resolveSize(dpi(48), widthMeasureSpec), resolveSize(dpi(72), heightMeasureSpec))
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(2f)
        rect.set(inset, inset, width - inset, height - inset)
        bg.color = when {
            isPressed -> Palette.panelShade
            !inLength -> Palette.panelEdge
            else -> Palette.panel
        }
        val radius = dp(5f)
        canvas.drawRoundRect(rect, radius, radius, bg)
        border.color = if (selectedStep) Palette.red else Palette.panelEdge
        border.strokeWidth = dp(if (selectedStep) 3f else 1f)
        canvas.drawRoundRect(rect, radius, radius, border)

        val cx = width / 2f
        led.color = if (playing) Palette.ledOn else Palette.ledOff
        // Rows scale with the cell height so short (landscape) cells stay legible.
        val h = height.toFloat()
        canvas.drawCircle(cx, h * 0.15f, min(dp(4f), h * 0.06f), led)
        num.textSize = min(sp(9f), h * 0.14f)
        canvas.drawText((index + 1).toString(), cx, h * 0.38f, num)

        noteText.color = if (gate) Palette.ink else Palette.inkMuted
        noteText.textSize = min(min(sp(13f), width / 3.2f), h * 0.22f)
        canvas.drawText(if (gate) noteName(note) else "—", cx, h * 0.66f, noteText)
        if (gate && (accent || slide)) {
            flag.textSize = min(min(sp(9f), width / 5.5f), h * 0.14f)
            val tag = if (accent && slide) "ACC SLD" else if (accent) "ACC" else "SLD"
            canvas.drawText(tag, cx, h - dp(5f), flag)
        }
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name
}

/**
 * Lays out equally sized children in as many columns as fit [minCellWidth]
 * (capped at [maxColumns]), wrapping to more rows on narrow screens.
 */
@SuppressLint("ViewConstructor")
class FlowGrid(
    context: Context,
    private val minCellWidth: Int,
    private val maxColumns: Int = Int.MAX_VALUE,
    private val cellHeight: Int = 0,
    /** If set, the column count is restricted to these values (e.g. 4, 8, 16). */
    private val allowedColumns: IntArray? = null,
) : ViewGroup(context) {

    private var columns = 1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val n = childCount
        if (n == 0) {
            setMeasuredDimension(width, 0); return
        }
        val fit = (width / minCellWidth).coerceIn(1, min(n, maxColumns))
        if (allowedColumns != null) {
            columns = allowedColumns.filter { it <= fit }.maxOrNull() ?: allowedColumns.min()
        } else {
            // Balance rows so the last row is not nearly empty.
            val r = (n + fit - 1) / fit
            columns = (n + r - 1) / r
        }
        val rows = (n + columns - 1) / columns
        val cellW = width / columns
        var cellH = cellHeight
        if (cellH == 0) {
            for (i in 0 until n) {
                val c = getChildAt(i)
                c.measure(
                    MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                cellH = max(cellH, c.measuredHeight)
            }
        }
        for (i in 0 until n) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cellW, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(cellH, MeasureSpec.EXACTLY)
            )
        }
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            rows * cellH + paddingTop + paddingBottom
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        val cellW = width / columns
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            val col = i % columns
            val row = i / columns
            val x = paddingLeft + col * cellW
            val y = paddingTop + row * c.measuredHeight
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
        }
    }
}

/**
 * One-octave (13 key) keyboard. Reports key index 0..12 on press and release,
 * supports glissando by dragging across keys.
 */
class KeyboardView(context: Context) : View(context) {
    var onKeyDown: ((Int) -> Unit)? = null
    var onKeyUp: (() -> Unit)? = null
    /** Key index highlighted as the selected step's note, or -1. */
    var highlighted = -1
        set(v) { field = v; invalidate() }
    var octaveLabel = ""
        set(v) { field = v; invalidate() }

    private var pressed = -1
    private val white = Paint(Paint.ANTI_ALIAS_FLAG)
    private val black = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.panelEdge; strokeWidth = dp(1f) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ledOn }
    private val keyLabel = textPaint(Palette.inkMuted, sp(10f))
    private val rect = RectF()

    private val whiteKeys = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
    private val blackKeys = intArrayOf(1, 3, 6, 8, 10)
    private val blackPos = floatArrayOf(1f, 2f, 4f, 5f, 6f) // boundary index between white keys
    private val names = arrayOf("C", "D", "E", "F", "G", "A", "B", "C")

    init {
        isFocusable = true
        contentDescription = "Keyboard"
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = resolveSize(min(dpi(150), max(dpi(110), w / 4)), heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    private fun keyAt(x: Float, y: Float): Int {
        val ww = width / 8f
        if (y < height * 0.6f) {
            for (i in blackKeys.indices) {
                val cx = blackPos[i] * ww
                if (x >= cx - ww * 0.32f && x <= cx + ww * 0.32f) return blackKeys[i]
            }
        }
        val wi = (x / ww).toInt().coerceIn(0, 7)
        return whiteKeys[wi]
    }

    override fun onDraw(canvas: Canvas) {
        val ww = width / 8f
        val r = dp(4f)
        for (i in 0 until 8) {
            val k = whiteKeys[i]
            rect.set(i * ww + dp(1f), 0f, (i + 1) * ww - dp(1f), height.toFloat())
            white.color = if (pressed == k) Palette.keyWhitePressed else Palette.keyWhite
            canvas.drawRoundRect(rect, r, r, white)
            if (highlighted == k) canvas.drawCircle(rect.centerX(), height - dp(30f), dp(5f), mark)
            canvas.drawText(
                if (i == 0 || i == 7) names[i] + octaveLabel.let { if (i == 7) nextOctave(it) else it } else names[i],
                rect.centerX(), height - dp(10f), keyLabel
            )
        }
        for (i in blackKeys.indices) {
            val k = blackKeys[i]
            val cx = blackPos[i] * ww
            rect.set(cx - ww * 0.3f, 0f, cx + ww * 0.3f, height * 0.6f)
            black.color = if (pressed == k) Palette.keyBlackPressed else Palette.keyBlack
            canvas.drawRoundRect(rect, r, r, black)
            if (highlighted == k) canvas.drawCircle(cx, height * 0.6f - dp(12f), dp(4f), mark)
        }
        canvas.drawLine(0f, 0f, width.toFloat(), 0f, line)
    }

    private fun nextOctave(o: String) = o.toIntOrNull()?.plus(1)?.toString() ?: ""

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
                val k = keyAt(event.x.coerceIn(0f, width - 1f), event.y)
                if (k != pressed) {
                    pressed = k
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    onKeyDown?.invoke(k)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                pressed = -1
                onKeyUp?.invoke()
                invalidate()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}

/**
 * Lays out its single child at a fixed design width and scales it uniformly
 * (touch included) so the whole child fits inside this view, centred.
 */
@SuppressLint("ViewConstructor")
class FitScaleLayout(context: Context, private val designWidth: Int) : ViewGroup(context) {
    private var scale = 1f

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val child = getChildAt(0)
        if (child != null) {
            child.measure(
                MeasureSpec.makeMeasureSpec(designWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            scale = min(w / designWidth.toFloat(), h / child.measuredHeight.toFloat().coerceAtLeast(1f))
        }
        setMeasuredDimension(w, h)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val child = getChildAt(0) ?: return
        val left = ((width - child.measuredWidth * scale) / 2f).toInt()
        val top = ((height - child.measuredHeight * scale) / 2f).toInt()
        child.layout(left, top, left + child.measuredWidth, top + child.measuredHeight)
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
    }
}
