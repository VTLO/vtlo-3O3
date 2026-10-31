package com.vtlo.drums

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import com.vtlo.core.ui.Palette
import com.vtlo.core.ui.dp
import com.vtlo.core.ui.sp
import com.vtlo.core.ui.textPaint
import kotlin.math.min

/**
 * One cell of the 8×16 grid. Grey when off, dark when on, red when accented;
 * groups of four steps alternate in shade like the hardware's step keys.
 */
@SuppressLint("ViewConstructor")
class DrumStepCell(context: Context, val step: Int) : View(context) {
    var on = false
    var accent = false
    var playing = false
    var inLength = true
    var trackName = ""

    private val rect = RectF()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        isClickable = true
        isLongClickable = true
        isFocusable = true
    }

    fun refresh() {
        contentDescription = "$trackName step ${step + 1}, " +
            (if (!on) "off" else if (accent) "accent" else "on") + if (inLength) "" else ", beyond pattern length"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(1.5f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = dp(4f)
        fill.color = when {
            accent -> Palette.red
            on -> Palette.knobBody
            isPressed -> Palette.panelEdge
            (step / 4) % 2 == 0 -> Palette.panel
            else -> Palette.panelShade
        }
        if (!inLength) fill.alpha = 90
        canvas.drawRoundRect(rect, r, r, fill)
        border.color = if (playing) Palette.ledOn else Palette.panelEdge
        border.strokeWidth = dp(if (playing) 2.5f else 1f)
        canvas.drawRoundRect(rect, r, r, border)
        if (on) {
            dot.color = if (playing) Palette.ledOn else if (accent) Color.WHITE else Palette.ledOff
            canvas.drawCircle(width / 2f, height / 2f, min(width, height) * 0.12f, dot)
        }
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
}

/** Step numbers with the playhead LED, aligned above the grid columns. */
class StepRuler(context: Context) : View(context) {
    var current = -1
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }
    var length = 16
        set(v) {
            if (field != v) {
                field = v
                invalidate()
            }
        }

    private val num = textPaint(Palette.inkMuted, sp(9f))
    private val led = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    override fun onDraw(canvas: Canvas) {
        val w = width / 16f
        val y = height / 2f
        for (i in 0 until 16) {
            val cx = (i + 0.5f) * w
            led.color = if (i == current) Palette.ledOn else Palette.ledOff
            canvas.drawCircle(cx - dp(7f), y, dp(3f), led)
            num.color = if (i < length) Palette.ink else Palette.panelEdge
            canvas.drawText((i + 1).toString(), cx + dp(4f), y + num.textSize * 0.35f, num)
        }
    }
}

/** Track selector: hit LED, track name and the kit it plays from. Red outline when selected. */
@SuppressLint("ViewConstructor")
class TrackButton(context: Context) : View(context) {
    var name = ""
    var kit = ""
    var selectedTrack = false
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
    private val label = textPaint(Palette.buttonText, sp(12f)).apply { textAlign = Paint.Align.LEFT }
    private val small = textPaint(Palette.panelText, sp(8f), bold = false).apply { textAlign = Paint.Align.RIGHT }

    init {
        isClickable = true
        isFocusable = true
    }

    fun refresh() {
        contentDescription = "$name, $kit kit"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = dp(1.5f)
        rect.set(inset, inset, width - inset, height - inset)
        val r = dp(4f)
        bg.color = if (isPressed) Palette.buttonDarkPressed else Palette.buttonDark
        canvas.drawRoundRect(rect, r, r, bg)
        border.color = if (selectedTrack) Palette.ledOn else Palette.panelEdge
        border.strokeWidth = dp(if (selectedTrack) 2.5f else 1f)
        canvas.drawRoundRect(rect, r, r, border)
        val cy = height / 2f
        led.color = if (lit) Palette.ledOn else Palette.ledOff
        canvas.drawCircle(dp(10f), cy, dp(3.5f), led)
        label.textSize = min(sp(12f), height * 0.45f)
        canvas.drawText(name, dp(18f), cy + label.textSize * 0.36f, label)
        small.textSize = min(sp(8f), height * 0.3f)
        canvas.drawText(kit, width - dp(6f), cy + small.textSize * 0.36f, small)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }

    override fun getAccessibilityClassName(): CharSequence = Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isSelected = selectedTrack
    }
}
