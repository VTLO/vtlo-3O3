package com.vtlo.acid303.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View

object Palette {
    val chassis = Color.parseColor("#1A1816")
    val panel = Color.parseColor("#C6C2BC")
    val panelShade = Color.parseColor("#B4B0AA")
    val panelEdge = Color.parseColor("#8E8A84")
    val ink = Color.parseColor("#1A1A1A")
    val inkMuted = Color.parseColor("#5E5A56")
    val red = Color.parseColor("#C02020")
    val ledOn = Color.parseColor("#FF2A1A")
    val ledOff = Color.parseColor("#5A2A2A")
    val knobBody = Color.parseColor("#262422")
    val knobRim = Color.parseColor("#4A4744")
    val knobPointer = Color.parseColor("#F0ECE6")
    val keyWhite = Color.parseColor("#EAE6E0")
    val keyWhitePressed = Color.parseColor("#D2CEC8")
    val keyBlack = Color.parseColor("#1A1816")
    val keyBlackPressed = Color.parseColor("#3A3836")
    val buttonDark = Color.parseColor("#2E2C2A")
    val buttonDarkPressed = Color.parseColor("#45423F")
    val buttonText = Color.parseColor("#E8E4DE")
    val panelText = Color.parseColor("#C6C2BC")
}

fun View.dp(v: Float): Float = v * resources.displayMetrics.density
fun View.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dpi(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.sp(v: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

fun textPaint(color: Int, size: Float, bold: Boolean = true) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    textSize = size
    textAlign = Paint.Align.CENTER
    typeface = Typeface.create(Typeface.SANS_SERIF, if (bold) Typeface.BOLD else Typeface.NORMAL)
    letterSpacing = 0.06f
}

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

fun noteName(midi: Int): String = NOTE_NAMES[midi % 12] + (midi / 12 - 1)
