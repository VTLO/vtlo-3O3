package com.vtlo.sampler

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.vtlo.core.ui.Palette
import com.vtlo.core.ui.dp
import com.vtlo.core.ui.sp
import com.vtlo.core.ui.textPaint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The selected pad's sample: waveform, trim markers (drag either one), playhead.
 * While recording it draws the incoming audio instead, growing from the left.
 */
class WaveformView(context: Context) : View(context) {
    /** Called while a trim marker is dragged, with normalized start and end. */
    var onTrimChanged: ((Float, Float) -> Unit)? = null

    private var data: FloatArray? = null
    private var sampleRate = 48000
    private var columns = FloatArray(0) // min/max pairs per pixel column
    var start = 0f
        private set
    var end = 1f
        private set
    var playhead = -1f
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var label = ""
        set(v) {
            field = v
            invalidate()
        }

    private var recOverview: FloatArray? = null
    private var recCount = 0
    private var recCapacity = 1

    private var dragging = 0 // 0 none, 1 start, 2 end

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E0C0B.toInt() }
    private val wave = Paint().apply { color = Palette.panelText; strokeWidth = 1f }
    private val waveDim = Paint().apply { color = Palette.knobRim; strokeWidth = 1f }
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.ledOn; strokeWidth = dp(2f) }
    private val head = Paint().apply { color = Palette.keyWhite; strokeWidth = dp(1.5f) }
    private val text = textPaint(Palette.panelText, sp(10f), bold = false).apply { textAlign = Paint.Align.LEFT }
    private val recText = textPaint(Palette.ledOn, sp(11f)).apply { textAlign = Paint.Align.LEFT }
    private val rect = RectF()

    init {
        isFocusable = true
    }

    fun setSample(d: FloatArray?, rate: Int, start: Float, end: Float) {
        data = d
        sampleRate = rate
        this.start = min(start, end)
        this.end = max(start, end)
        recOverview = null
        rebuildColumns()
        updateDescription()
        invalidate()
    }

    fun setTrim(start: Float, end: Float) {
        this.start = min(start, end)
        this.end = max(start, end)
        updateDescription()
        invalidate()
    }

    /** Shows a recording in progress: [overview] holds [count] block peaks of [capacity]. */
    fun showRecording(overview: FloatArray, count: Int, capacity: Int) {
        recOverview = overview
        recCount = count
        recCapacity = max(1, capacity)
        invalidate()
    }

    private fun updateDescription() {
        val d = data
        contentDescription = if (d == null) "Empty pad" else
            "Waveform, %.2f seconds, start %d%%, end %d%%".format(d.size / sampleRate.toFloat(), (start * 100).toInt(), (end * 100).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuildColumns()

    private fun rebuildColumns() {
        val d = data
        val w = width
        if (d == null || d.isEmpty() || w <= 0) {
            columns = FloatArray(0)
            return
        }
        columns = FloatArray(w * 2)
        for (x in 0 until w) {
            val a = (x.toLong() * d.size / w).toInt()
            val b = max(a + 1, ((x + 1).toLong() * d.size / w).toInt()).coerceAtMost(d.size)
            var lo = 0f
            var hi = 0f
            for (i in a until b) {
                val v = d[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            columns[x * 2] = lo
            columns[x * 2 + 1] = hi
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = dp(6f)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, bg)
        val mid = h / 2f
        val amp = h * 0.45f

        val rec = recOverview
        if (rec != null) {
            val n = min(recCount, rec.size)
            for (i in 0 until n) {
                val x = i.toFloat() / recCapacity * w
                val p = rec[i] * amp
                canvas.drawLine(x, mid - p, x, mid + p, marker)
            }
            canvas.drawText("● REC", dp(8f), dp(14f), recText)
            return
        }

        val sx = start * w
        val ex = end * w
        val cols = columns.size / 2
        for (x in 0 until cols) {
            val inside = x >= sx && x <= ex
            canvas.drawLine(x.toFloat(), mid - columns[x * 2 + 1] * amp, x.toFloat(), mid - columns[x * 2] * amp, if (inside) wave else waveDim)
        }
        if (data == null) {
            text.textAlign = Paint.Align.CENTER
            canvas.drawText("EMPTY · REC OR LOAD", w / 2, mid + text.textSize / 3, text)
            text.textAlign = Paint.Align.LEFT
        } else {
            canvas.drawRect(0f, 0f, sx, h, shade)
            canvas.drawRect(ex, 0f, w, h, shade)
            canvas.drawLine(sx, 0f, sx, h, marker)
            canvas.drawLine(ex, 0f, ex, h, marker)
            canvas.drawCircle(sx, dp(6f), dp(5f), marker)
            canvas.drawCircle(ex, h - dp(6f), dp(5f), marker)
            if (playhead in 0f..1f) canvas.drawLine(playhead * w, 0f, playhead * w, h, head)
        }
        canvas.drawText(label, dp(8f), dp(14f), text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (data == null || recOverview != null) return false
        val f = (event.x / width).coerceIn(0f, 1f)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                dragging = if (abs(f - start) <= abs(f - end)) 1 else 2
                move(f)
            }
            MotionEvent.ACTION_MOVE -> move(f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return true
    }

    private fun move(f: Float) {
        val minGap = 0.002f
        if (dragging == 1) start = min(f, end - minGap).coerceAtLeast(0f)
        if (dragging == 2) end = max(f, start + minGap).coerceAtMost(1f)
        updateDescription()
        invalidate()
        onTrimChanged?.invoke(start, end)
    }

    override fun performClick(): Boolean = super.performClick()
}

/** A playable pad: number, sample name, hit LED, dot when it has steps in the pattern. */
@SuppressLint("ViewConstructor")
class SamplerPadView(context: Context, val pad: Int) : View(context) {
    var name = ""
    var empty = true
    var hasSteps = false
    var selectedPad = false
        set(v) {
            field = v
            if (Build.VERSION.SDK_INT >= 30) stateDescription = if (v) "selected" else null
            invalidate()
        }
    var lit = false
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val rect = RectF()
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)
    private val num = textPaint(Palette.buttonText, sp(13f)).apply { textAlign = Paint.Align.LEFT }
    private val small = textPaint(Palette.panelText, sp(9f), bold = false).apply { textAlign = Paint.Align.LEFT }

    init {
        isClickable = true
        isFocusable = true
    }

    fun refresh() {
        contentDescription = "Pad ${pad + 1}, " + if (empty) "empty" else name
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(2f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = dp(6f)
        bg.color = when {
            lit -> Palette.buttonDarkPressed
            isPressed -> Palette.buttonDarkPressed
            else -> Palette.buttonDark
        }
        canvas.drawRoundRect(rect, r, r, bg)
        border.color = if (selectedPad) Palette.ledOn else Palette.panelEdge
        border.strokeWidth = dp(if (selectedPad) 2.5f else 1f)
        canvas.drawRoundRect(rect, r, r, border)
        led.color = if (lit) Palette.ledOn else Palette.ledOff
        canvas.drawCircle(width - dp(10f), dp(10f), dp(3.5f), led)
        if (hasSteps) {
            led.color = Palette.panelText
            canvas.drawCircle(width - dp(10f), height - dp(10f), dp(2.5f), led)
        }
        num.textSize = min(sp(13f), height * 0.36f)
        canvas.drawText("${pad + 1}", dp(8f), height * 0.42f, num)
        small.textSize = min(sp(9f), height * 0.24f)
        small.color = if (empty) Palette.knobRim else Palette.panelText
        val label = if (empty) "EMPTY" else name
        val maxW = width - dp(14f)
        var shown = label
        while (shown.length > 1 && small.measureText(shown) > maxW) shown = shown.dropLast(1)
        canvas.drawText(shown, dp(8f), height * 0.8f, small)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun performClick(): Boolean {
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return super.performClick()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isSelected = selectedPad
    }
}

/** Input level bar for the microphone. */
class LevelMeter(context: Context) : View(context) {
    var level = 0f
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0E0C0B.toInt() }
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val r = dp(4f)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, r, r, bg)
        // dB scale: -48 dB .. 0 dB across the width.
        val db = if (level <= 1e-5f) -96.0 else 20 * Math.log10(level.toDouble())
        val f = ((db + 48) / 48).coerceIn(0.0, 1.0).toFloat()
        bar.color = if (f > 0.94f) Palette.ledOn else if (f > 0.75f) 0xFFE0A030.toInt() else 0xFF4FB04F.toInt()
        val pad = dp(3f)
        rect.set(pad, pad, pad + (width - 2 * pad) * f, height - pad)
        canvas.drawRoundRect(rect, r / 2, r / 2, bar)
    }
}
