package com.vtlo.desktop.ui

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** The hardware palette, shared with the Android app. */
object Colors {
    val chassis = Color(0x1A1816)
    val panel = Color(0xC6C2BC)
    val panelShade = Color(0xB4B0AA)
    val panelEdge = Color(0x8E8A84)
    val ink = Color(0x1A1A1A)
    val inkMuted = Color(0x5E5A56)
    val red = Color(0xC02020)
    val ledOn = Color(0xFF2A1A)
    val ledOff = Color(0x5A2A2A)
    val knobBody = Color(0x262422)
    val knobRim = Color(0x4A4744)
    val pointer = Color(0xF0ECE6)
    val keyWhite = Color(0xEAE6E0)
    val keyWhitePressed = Color(0xD2CEC8)
    val keyBlack = Color(0x1A1816)
    val keyBlackPressed = Color(0x3A3836)
    val button = Color(0x2E2C2A)
    val buttonPressed = Color(0x45423F)
    val buttonText = Color(0xE8E4DE)
    val panelText = Color(0xC6C2BC)
    val display = Color(0x0E0C0B)
}

fun font(size: Float, bold: Boolean = true): Font = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 1).deriveFont(size)

fun Graphics2D.textCentered(text: String, cx: Float, baseline: Float, f: Font, c: Color) {
    font = f
    color = c
    drawString(text, cx - fontMetrics.stringWidth(text) / 2f, baseline)
}

fun Graphics2D.textLeft(text: String, x: Float, baseline: Float, f: Font, c: Color) {
    font = f
    color = c
    drawString(text, x, baseline)
}

/** Shrinks [size] until [text] fits [width]. */
fun Graphics2D.fit(text: String, size: Float, width: Float, bold: Boolean = true): Font {
    var s = size
    while (s > 6f && getFontMetrics(font(s, bold)).stringWidth(text) > width) s -= 0.5f
    return font(s, bold)
}

fun Graphics2D.roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, fill: Color?, stroke: Color? = null, width: Float = 1f) {
    val rr = RoundRectangle2D.Float(x, y, w, h, r * 2, r * 2)
    if (fill != null) {
        color = fill; fill(rr)
    }
    if (stroke != null) {
        color = stroke; this.stroke = BasicStroke(width); draw(rr)
    }
}

fun Graphics2D.dot(cx: Float, cy: Float, r: Float, c: Color) {
    color = c
    fill(Ellipse2D.Float(cx - r, cy - r, r * 2, r * 2))
}

/** Mouse event in design coordinates. */
class Press(val x: Float, val y: Float, val right: Boolean, val clicks: Int, val shift: Boolean)

/** A control on the scaled canvas, positioned in design units. */
abstract class Widget {
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    open val interactive = true
    open val tooltip: String? get() = null

    fun place(x: Float, y: Float, w: Float, h: Float): Widget {
        this.x = x; this.y = y; this.w = w; this.h = h
        return this
    }

    fun contains(px: Float, py: Float) = px >= x && px < x + w && py >= y && py < y + h

    abstract fun paint(g: Graphics2D)
    /** @return true to capture the drag that follows. */
    open fun press(p: Press): Boolean = false
    open fun drag(px: Float, py: Float) {}
    open fun release(px: Float, py: Float) {}
    open fun wheel(steps: Int) {}
}

/** Titled panel background; the title may change (selected pad, track…). */
class PanelBox(var title: () -> String) : Widget() {
    override val interactive = false
    override fun paint(g: Graphics2D) {
        g.roundRect(x, y, w, h, 10f, Colors.panel, Colors.panelEdge)
        g.textLeft(title(), x + 12f, y + 17f, font(11f), Colors.red)
    }
}

class TextBox(var text: () -> String, val size: Float, val color: Color, val bold: Boolean = true) : Widget() {
    override val interactive = false
    override fun paint(g: Graphics2D) = g.textLeft(text(), x, y + h * 0.8f, font(size, bold), color)
}

/** Seven-segment style tempo readout. */
class BpmDisplay(var value: () -> Int) : Widget() {
    override val interactive = false
    override fun paint(g: Graphics2D) {
        g.roundRect(x, y, w, h, 6f, Colors.display, Colors.knobRim)
        g.textCentered("%3d".format(value()), x + w / 2, y + h * 0.75f, Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(h * 0.62f), Colors.ledOn)
    }
}

/** Rotary knob: drag up/down, mouse wheel, double-click resets; shift for fine moves. */
class Knob(
    val label: String,
    private val default: Float,
    private val format: (Float) -> String,
    private val onChange: (Float) -> Unit,
    private val detents: Int = 0,
) : Widget() {
    var value = default
        private set
    private var dragging = false
    private var lastY = 0f
    private var accum = 0f

    override val tooltip get() = "$label: ${format(value)} — drag or scroll, double-click to reset"

    fun set(v: Float, fromUser: Boolean = false) {
        var nv = v.coerceIn(0f, 1f)
        if (detents > 1) nv = (nv * (detents - 1)).roundToInt().toFloat() / (detents - 1)
        if (nv == value) return
        value = nv
        if (fromUser) onChange(nv)
    }

    override fun paint(g: Graphics2D) {
        val cx = x + w / 2
        val r = min(w * 0.33f, (h - 34f) * 0.42f)
        val cy = y + 16f + (h - 34f) / 2
        g.textCentered(label, cx, y + 11f, g.fit(label, 10.5f, w - 2), Colors.ink)
        val ar = r * 1.2f
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Colors.panelEdge
        g.draw(Arc2D.Float(cx - ar, cy - ar, ar * 2, ar * 2, 225f, -270f, Arc2D.OPEN))
        g.color = Colors.red
        g.draw(Arc2D.Float(cx - ar, cy - ar, ar * 2, ar * 2, 225f, -270f * value, Arc2D.OPEN))
        g.stroke = BasicStroke(1.2f)
        g.color = Colors.inkMuted
        for (i in 0..10) {
            val a = Math.toRadians(225.0 - 27.0 * i)
            g.draw(Line2D.Double(cx + cos(a) * r * 1.36, cy - sin(a) * r * 1.36, cx + cos(a) * r * 1.46, cy - sin(a) * r * 1.46))
        }
        g.dot(cx, cy, r, Colors.knobBody)
        g.stroke = BasicStroke(2f)
        g.color = Colors.knobRim
        g.draw(Ellipse2D.Float(cx - r + 1.5f, cy - r + 1.5f, (r - 1.5f) * 2, (r - 1.5f) * 2))
        val a = Math.toRadians(225.0 - 270.0 * value)
        g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.color = Colors.pointer
        g.draw(Line2D.Double(cx + cos(a) * r * 0.25, cy - sin(a) * r * 0.25, cx + cos(a) * r * 0.85, cy - sin(a) * r * 0.85))
        g.textCentered(format(value), cx, y + h - 4f, font(11f), if (dragging) Colors.red else Colors.inkMuted)
    }

    override fun press(p: Press): Boolean {
        if (p.clicks >= 2) {
            set(default, true)
            return false
        }
        dragging = true
        lastY = p.y
        accum = 0f
        return true
    }

    override fun drag(px: Float, py: Float) {
        val d = (lastY - py) / 200f
        lastY = py
        if (detents > 1) {
            accum += d
            val step = 1f / (detents - 1)
            while (accum >= step * 0.6f) { set(value + step, true); accum -= step * 0.6f }
            while (accum <= -step * 0.6f) { set(value - step, true); accum += step * 0.6f }
        } else {
            set(value + d, true)
        }
    }

    override fun release(px: Float, py: Float) {
        dragging = false
    }

    override fun wheel(steps: Int) {
        val step = if (detents > 1) 1f / (detents - 1) else 0.02f
        set(value - steps * step, true)
    }
}

/** Hardware-style button, optional LED; right-click runs [onRight] when set. */
class Pad(
    var text: String,
    private val led: Boolean = false,
    private val dark: Boolean = true,
    var hint: String? = null,
    private val onRight: (() -> Unit)? = null,
    private val onClick: () -> Unit,
) : Widget() {
    var active = false
    private var down = false

    override val tooltip get() = hint

    override fun paint(g: Graphics2D) {
        val bg = if (down) (if (dark) Colors.buttonPressed else Colors.panelShade) else (if (dark) Colors.button else Colors.panel)
        g.roundRect(x + 2, y + 2, w - 4, h - 4, 6f, bg, Colors.panelEdge)
        var ty = y + h / 2 + 4.5f
        if (led) {
            val ly = y + min(13f, h * 0.28f)
            if (active) g.dot(x + w / 2, ly, 8f, Color(255, 42, 26, 70))
            g.dot(x + w / 2, ly, 4f, if (active) Colors.ledOn else Colors.ledOff)
            ty += min(7f, h * 0.14f)
        } else if (active) {
            g.roundRect(x + 2, y + 2, w - 4, h - 4, 6f, null, Colors.ledOn, 2.5f)
        }
        g.textCentered(text, x + w / 2, ty, g.fit(text, 12f, w - 10), if (dark) Colors.buttonText else Colors.ink)
    }

    override fun press(p: Press): Boolean {
        if (p.right) {
            onRight?.invoke()
            return false
        }
        down = true
        return true
    }

    override fun release(px: Float, py: Float) {
        if (down && contains(px, py)) onClick()
        down = false
    }
}

/** 303 step: playhead LED, number, note, accent/slide flags. */
class NoteStep(val index: Int, private val onClick: () -> Unit, private val onRight: () -> Unit) : Widget() {
    var note = ""
    var gate = false
    var accent = false
    var slide = false
    var selected = false
    var playing = false
    var inLength = true

    override val tooltip get() = "Step ${index + 1}: click to select, right-click toggles the gate"

    override fun paint(g: Graphics2D) {
        g.roundRect(x + 2, y + 2, w - 4, h - 4, 5f, if (inLength) Colors.panel else Colors.panelEdge,
            if (selected) Colors.red else Colors.panelEdge, if (selected) 3f else 1f)
        val cx = x + w / 2
        g.dot(cx, y + h * 0.15f, 4f, if (playing) Colors.ledOn else Colors.ledOff)
        g.textCentered("${index + 1}", cx, y + h * 0.38f, font(9f), Colors.inkMuted)
        g.textCentered(if (gate) note else "—", cx, y + h * 0.66f, font(13f), if (gate) Colors.ink else Colors.inkMuted)
        if (gate && (accent || slide)) {
            val tag = if (accent && slide) "ACC SLD" else if (accent) "ACC" else "SLD"
            g.textCentered(tag, cx, y + h - 6f, g.fit(tag, 9f, w - 6), Colors.red)
        }
    }

    override fun press(p: Press): Boolean {
        if (p.right) onRight() else onClick()
        return false
    }
}

/** Drum/sampler grid step: grey off, dark on, red accent; groups of four alternate. */
class GridStep(val step: Int, private val name: () -> String, private val onClick: () -> Unit, private val onRight: () -> Unit) : Widget() {
    var on = false
    var accent = false
    var playing = false
    var inLength = true

    override val tooltip get() = "${name()} step ${step + 1}: click on/off, right-click accent"

    override fun paint(g: Graphics2D) {
        var fill = when {
            accent -> Colors.red
            on -> Colors.knobBody
            (step / 4) % 2 == 0 -> Colors.panel
            else -> Colors.panelShade
        }
        if (!inLength) fill = Color(fill.red, fill.green, fill.blue, 90)
        g.roundRect(x + 1.5f, y + 1.5f, w - 3, h - 3, 4f, fill, if (playing) Colors.ledOn else Colors.panelEdge, if (playing) 2.5f else 1f)
        if (on) g.dot(x + w / 2, y + h / 2, min(w, h) * 0.12f, if (playing) Colors.ledOn else if (accent) Color.WHITE else Colors.ledOff)
    }

    override fun press(p: Press): Boolean {
        if (p.right) onRight() else onClick()
        return false
    }
}

/** Step numbers with the playhead LED. */
class Ruler(var current: () -> Int, var length: () -> Int) : Widget() {
    override val interactive = false
    override fun paint(g: Graphics2D) {
        val cw = w / 16
        for (i in 0 until 16) {
            val cx = x + (i + 0.5f) * cw
            g.dot(cx - 7f, y + h / 2, 3f, if (i == current()) Colors.ledOn else Colors.ledOff)
            g.textLeft("${i + 1}", cx - 2f, y + h / 2 + 3.5f, font(9f), if (i < length()) Colors.ink else Colors.panelEdge)
        }
    }
}

/** Track selector with hit LED and a small tag; red outline when selected. */
class TrackPad(private val onClick: () -> Unit) : Widget() {
    var name = ""
    var tag = ""
    var selected = false
    var lit = false

    override val tooltip get() = "$name ($tag kit): click to select and hear it"

    override fun paint(g: Graphics2D) {
        g.roundRect(x + 1.5f, y + 1.5f, w - 3, h - 3, 4f, Colors.button,
            if (selected) Colors.ledOn else Colors.panelEdge, if (selected) 2.5f else 1f)
        g.dot(x + 10f, y + h / 2, 3.5f, if (lit) Colors.ledOn else Colors.ledOff)
        g.textLeft(name, x + 18f, y + h / 2 + 4.5f, font(min(12f, h * 0.45f)), Colors.buttonText)
        val f = font(8f, false)
        g.font = f
        g.textLeft(tag, x + w - 6f - g.fontMetrics.stringWidth(tag), y + h / 2 + 3f, f, Colors.panelText)
    }

    override fun press(p: Press): Boolean {
        onClick()
        return false
    }
}

/** One-octave keyboard; drag across keys for glissando. */
class Keys(private val onDown: (Int) -> Unit, private val onUp: () -> Unit) : Widget() {
    var highlighted = -1
    var octave = ""
    private var pressed = -1
    private val white = intArrayOf(0, 2, 4, 5, 7, 9, 11, 12)
    private val black = intArrayOf(1, 3, 6, 8, 10)
    private val blackPos = floatArrayOf(1f, 2f, 4f, 5f, 6f)
    private val names = arrayOf("C", "D", "E", "F", "G", "A", "B", "C")

    override val tooltip get() = "Sets the selected step's note (computer keys Z S X D C V G B H N J M ,)"

    private fun keyAt(px: Float, py: Float): Int {
        val ww = w / 8
        if (py < y + h * 0.6f) {
            for (i in black.indices) {
                val cx = x + blackPos[i] * ww
                if (abs(px - cx) <= ww * 0.32f) return black[i]
            }
        }
        return white[((px - x) / ww).toInt().coerceIn(0, 7)]
    }

    override fun paint(g: Graphics2D) {
        val ww = w / 8
        for (i in 0 until 8) {
            val k = white[i]
            g.roundRect(x + i * ww + 1, y, ww - 2, h, 4f, if (pressed == k) Colors.keyWhitePressed else Colors.keyWhite)
            if (highlighted == k) g.dot(x + i * ww + ww / 2, y + h - 30f, 5f, Colors.ledOn)
            val label = if (i == 0) names[i] + octave else if (i == 7) names[i] + (octave.toIntOrNull()?.plus(1) ?: "") else names[i]
            g.textCentered(label, x + i * ww + ww / 2, y + h - 10f, font(10f), Colors.inkMuted)
        }
        for (i in black.indices) {
            val k = black[i]
            val cx = x + blackPos[i] * ww
            g.roundRect(cx - ww * 0.3f, y, ww * 0.6f, h * 0.6f, 4f, if (pressed == k) Colors.keyBlackPressed else Colors.keyBlack)
            if (highlighted == k) g.dot(cx, y + h * 0.6f - 12f, 4f, Colors.ledOn)
        }
    }

    override fun press(p: Press): Boolean {
        pressed = keyAt(p.x, p.y)
        onDown(pressed)
        return true
    }

    override fun drag(px: Float, py: Float) {
        val k = keyAt(px.coerceIn(x, x + w - 1), py)
        if (k != pressed) {
            pressed = k
            onDown(k)
        }
    }

    override fun release(px: Float, py: Float) {
        pressed = -1
        onUp()
    }
}

/** Sample waveform with draggable trim markers and playhead; shows a recording live. */
class Waveform(private val onTrim: (Float, Float) -> Unit) : Widget() {
    private var data: FloatArray? = null
    private var columns = FloatArray(0)
    private var columnsFor = -1f
    var start = 0f
    var end = 1f
    var playhead = -1f
    var label = ""
    var recording: Triple<FloatArray, Int, Int>? = null
    private var dragging = 0

    override val tooltip get() = if (data == null) null else "Drag the red markers to trim"

    fun setSample(d: FloatArray?, s: Float, e: Float) {
        if (d !== data) columnsFor = -1f
        data = d
        start = min(s, e); end = max(s, e)
    }

    private fun rebuild() {
        val d = data ?: return
        val cols = w.toInt().coerceAtLeast(1)
        columns = FloatArray(cols * 2)
        for (c in 0 until cols) {
            val a = (c.toLong() * d.size / cols).toInt()
            val b = max(a + 1, ((c + 1).toLong() * d.size / cols).toInt()).coerceAtMost(d.size)
            var lo = 0f
            var hi = 0f
            for (i in a until b) {
                if (d[i] < lo) lo = d[i]
                if (d[i] > hi) hi = d[i]
            }
            columns[c * 2] = lo; columns[c * 2 + 1] = hi
        }
        columnsFor = w
    }

    override fun paint(g: Graphics2D) {
        g.roundRect(x, y, w, h, 6f, Colors.display)
        val mid = y + h / 2
        val amp = h * 0.45f
        g.stroke = BasicStroke(1f)
        val rec = recording
        if (rec != null) {
            val (ov, count, cap) = rec
            g.color = Colors.ledOn
            for (i in 0 until min(count, ov.size)) {
                val px = x + i.toFloat() / cap * w
                g.draw(Line2D.Float(px, mid - ov[i] * amp, px, mid + ov[i] * amp))
            }
            g.textLeft("● REC", x + 8f, y + 16f, font(11f), Colors.ledOn)
            return
        }
        val d = data
        if (d == null) {
            g.textCentered("EMPTY · REC OR LOAD", x + w / 2, mid + 4f, font(10f, false), Colors.panelText)
            return
        }
        if (columnsFor != w) rebuild()
        val sx = x + start * w
        val ex = x + end * w
        for (c in 0 until columns.size / 2) {
            val px = x + c
            g.color = if (px in sx..ex) Colors.panelText else Colors.knobRim
            g.draw(Line2D.Float(px, mid - columns[c * 2 + 1] * amp, px, mid - columns[c * 2] * amp))
        }
        g.color = Color(0, 0, 0, 150)
        g.fill(java.awt.geom.Rectangle2D.Float(x, y, sx - x, h))
        g.fill(java.awt.geom.Rectangle2D.Float(ex, y, x + w - ex, h))
        g.stroke = BasicStroke(2f)
        g.color = Colors.ledOn
        g.draw(Line2D.Float(sx, y, sx, y + h))
        g.draw(Line2D.Float(ex, y, ex, y + h))
        g.dot(sx, y + 6f, 5f, Colors.ledOn)
        g.dot(ex, y + h - 6f, 5f, Colors.ledOn)
        if (playhead in 0f..1f) {
            g.color = Colors.keyWhite
            g.stroke = BasicStroke(1.5f)
            g.draw(Line2D.Float(x + playhead * w, y, x + playhead * w, y + h))
        }
        g.textLeft(label, x + 8f, y + 15f, font(10f, false), Colors.panelText)
    }

    override fun press(p: Press): Boolean {
        if (data == null || recording != null) return false
        val f = ((p.x - x) / w).coerceIn(0f, 1f)
        dragging = if (abs(f - start) <= abs(f - end)) 1 else 2
        drag(p.x, p.y)
        return true
    }

    override fun drag(px: Float, py: Float) {
        val f = ((px - x) / w).coerceIn(0f, 1f)
        if (dragging == 1) start = min(f, end - 0.002f).coerceAtLeast(0f)
        if (dragging == 2) end = max(f, start + 0.002f).coerceAtMost(1f)
        onTrim(start, end)
    }

    override fun release(px: Float, py: Float) {
        dragging = 0
    }
}

/** Sampler pad: number, sample name, hit LED, dot when it has steps. */
class SamplePad(val pad: Int, private val onClick: () -> Unit) : Widget() {
    var name = ""
    var empty = true
    var selected = false
    var lit = false
    var hasSteps = false

    override val tooltip get() = "Pad ${pad + 1}: click to select and play (keys Q W E R / A S D F)"

    override fun paint(g: Graphics2D) {
        g.roundRect(x + 2, y + 2, w - 4, h - 4, 6f, if (lit) Colors.buttonPressed else Colors.button,
            if (selected) Colors.ledOn else Colors.panelEdge, if (selected) 2.5f else 1f)
        g.dot(x + w - 10f, y + 10f, 3.5f, if (lit) Colors.ledOn else Colors.ledOff)
        if (hasSteps) g.dot(x + w - 10f, y + h - 10f, 2.5f, Colors.panelText)
        g.textLeft("${pad + 1}", x + 9f, y + h * 0.45f, font(13f), Colors.buttonText)
        val label = if (empty) "EMPTY" else name
        g.textLeft(label, x + 9f, y + h * 0.82f, g.fit(label, 9f, w - 22, false), if (empty) Colors.knobRim else Colors.panelText)
    }

    override fun press(p: Press): Boolean {
        onClick()
        return false
    }
}

/** Input level, −48 dB to 0 dB. */
class Meter(var level: () -> Float) : Widget() {
    override val interactive = false
    override fun paint(g: Graphics2D) {
        g.roundRect(x, y, w, h, 4f, Colors.display)
        val l = level()
        val db = if (l <= 1e-5f) -96.0 else 20 * Math.log10(l.toDouble())
        val f = ((db + 48) / 48).coerceIn(0.0, 1.0).toFloat()
        val c = if (f > 0.94f) Colors.ledOn else if (f > 0.75f) Color(0xE0A030) else Color(0x4FB04F)
        if (f > 0f) g.roundRect(x + 3, y + 3, (w - 6) * f, h - 6, 2f, c)
    }
}
