package com.vtlo.guide

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The short animated guide (in French) shown at first launch, shared by the Android and desktop apps.
 * Text, layout and animations live here; each app only supplies a [GuidePen] that draws in its own toolkit.
 */

/** Minimal drawing surface. Colours are ARGB ints; sizes are in the app's layout units (dp / design units). */
interface GuidePen {
    fun fillRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int)
    fun strokeRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float)
    fun fillCircle(cx: Float, cy: Float, r: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float)
    /** Arc stroke; angles in degrees, 0 = 3 o'clock, positive = clockwise. */
    fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int, width: Float)
    /** [align]: -1 left, 0 centre, 1 right of [x]; [y] is the baseline. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Int)
    fun measure(s: String, size: Float, bold: Boolean): Float
    /** The app's two-faced emblem, turned by [angle] radians. */
    fun coin(cx: Float, cy: Float, r: Float, angle: Float)
}

enum class GuideInput { TOUCH, DESKTOP }

enum class GuideArt { WELCOME, SCREENS, RUN, STEPS, KNOB, SAMPLER, END }

class GuidePage(val title: String, val body: String, val art: GuideArt)

object Guide {
    fun pages(input: GuideInput): List<GuidePage> {
        val touch = input == GuideInput.TOUCH
        return listOf(
            GuidePage(
                "Bienvenue dans VTLO 3O3",
                "Un studio de poche\u00A0: deux lignes de basse 303, deux boîtes à rythmes et un sampler, " +
                    "tous calés sur le même tempo.",
                GuideArt.WELCOME,
            ),
            GuidePage(
                "Cinq écrans",
                (if (touch) "Glissez vers la gauche ou la droite, ou touchez un onglet\u00A0: BASS\u00A01, DRUMS\u00A01, SAMPLER, puis BASS\u00A02 et DRUMS\u00A02. "
                else "Cliquez sur un onglet, ou passez de l'un à l'autre avec TAB et F1\u00A0à\u00A0F5. ") +
                    "BASS\u00A02 et DRUMS\u00A02 démarrent vides, prêts à être remplis.",
                GuideArt.SCREENS,
            ),
            GuidePage(
                "Lancer la musique",
                (if (touch) "RUN démarre et arrête tous les instruments ensemble. "
                else "RUN (ou la barre ESPACE) démarre et arrête tous les instruments ensemble. ") +
                    "Les boutons B1, D1, SMP, B2 et D2 ajoutent chaque instrument au mix ou l'en retirent.",
                GuideArt.RUN,
            ),
            GuidePage(
                "Programmer un motif",
                if (touch) "Un motif compte 16\u00A0pas. Touchez un pas pour le modifier\u00A0: sur la basse, choisissez sa note au clavier " +
                    "puis ajoutez ACCENT ou SLIDE\u00A0; sur la batterie et le sampler, allumez ou éteignez les pas."
                else "Un motif compte 16\u00A0pas. Cliquez sur un pas pour le modifier, clic droit pour l'accent ou le silence. " +
                    "Sur la basse, jouez les notes au clavier de l'ordinateur (Z\u00A0S\u00A0X\u00A0D\u00A0C…).",
                GuideArt.STEPS,
            ),
            GuidePage(
                "Façonner le son",
                if (touch) "Faites glisser un bouton vers le haut ou le bas pour le tourner. Touchez-le deux fois pour revenir à sa valeur de départ."
                else "Glissez un bouton vers le haut ou le bas, ou utilisez la molette (MAJ pour un réglage fin). Double-cliquez pour le réinitialiser.",
                GuideArt.KNOB,
            ),
            GuidePage(
                "Le sampler",
                "Choisissez un pad, puis REC pour enregistrer au micro ou LOAD pour importer un son. " +
                    "Réglez ensuite filtre, bitcrusher et drive, et placez-le dans la grille.",
                GuideArt.SAMPLER,
            ),
            GuidePage(
                "À vous de jouer\u00A0!",
                (if (touch) "La musique continue en arrière-plan. " else "") +
                    "EXPORT enregistre vos boucles en WAV et tout est sauvegardé automatiquement. " +
                    "Le bouton\u00A0? relance ce guide.",
                GuideArt.END,
            ),
        )
    }

    /** Greedy word wrap using the pen's text measurement. */
    fun wrap(pen: GuidePen, text: String, size: Float, width: Float): List<String> {
        val lines = ArrayList<String>()
        var line = ""
        for (word in text.split(' ')) {
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (line.isNotEmpty() && pen.measure(candidate, size, false) > width) {
                lines.add(line)
                line = word
            } else {
                line = candidate
            }
        }
        if (line.isNotEmpty()) lines.add(line)
        return lines
    }
}

/**
 * The guide overlay: a dimmed backdrop and a card with an animated picture, a title, a short text,
 * progress dots and PASSER / SUIVANT buttons. Pages slide in; everything is driven by the clock passed to [paint].
 */
class GuideOverlay(val pages: List<GuidePage>, private val animate: Boolean = true) {
    var page = 0
        private set
    var finished = false
        private set

    private var pageStart = -1L
    private var openStart = -1L
    private var skipRect = FloatArray(4)
    private var nextRect = FloatArray(4)
    private var backRect = FloatArray(4)
    private var lastNow = 0L

    val isLast get() = page == pages.size - 1
    val current get() = pages[page]

    fun next() {
        if (isLast) finished = true else { page++; pageStart = lastNow }
    }

    fun previous() {
        if (page > 0) { page--; pageStart = lastNow }
    }

    fun skip() { finished = true }

    /** Handles a tap anywhere on screen (the overlay swallows them all). */
    fun tap(x: Float, y: Float) {
        when {
            inside(nextRect, x, y) -> next()
            inside(skipRect, x, y) -> skip()
            inside(backRect, x, y) -> previous()
        }
    }

    private fun inside(r: FloatArray, x: Float, y: Float) = x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3]

    fun paint(pen: GuidePen, x0: Float, y0: Float, w: Float, h: Float, nowMs: Long) {
        lastNow = nowMs
        if (openStart < 0) { openStart = nowMs; pageStart = nowMs }
        val open = if (animate) ease(((nowMs - openStart) / 300f).coerceIn(0f, 1f)) else 1f
        val enter = if (animate) ease(((nowMs - pageStart) / 320f).coerceIn(0f, 1f)) else 1f
        val t = if (animate) (nowMs - pageStart) / 1000f else 0f

        pen.fillRound(x0, y0, w, h, 0f, fade(0xE0000000.toInt(), open))

        val cw = min(560f, w - 24f)
        val ch = min(380f, h - 16f)
        val cx = x0 + (w - cw) / 2f
        val cy = y0 + (h - ch) / 2f + (1f - open) * 24f
        pen.fillRound(cx, cy, cw, ch, 16f, fade(CARD, open))
        pen.strokeRound(cx, cy, cw, ch, 16f, fade(EDGE, open), 1.5f)

        val pad = 20f
        val slide = (1f - enter) * 36f
        val a = open * enter
        val p = pages[page]

        // Picture.
        val artH = ch * 0.38f
        pen.fillRound(cx + pad, cy + pad, cw - 2 * pad, artH, 10f, fade(STAGE, open))
        drawArt(pen, p.art, cx + pad + slide, cy + pad, cw - 2 * pad, artH, t, a)

        // Title and body, shrinking the type if a long text would not fit.
        val buttonH = 36f
        val textTop = cy + pad + artH + 14f
        val textBottom = cy + ch - pad - buttonH - 10f
        var titleSize = 20f
        var bodySize = 14f
        var lines: List<String>
        while (true) {
            lines = Guide.wrap(pen, p.body, bodySize, cw - 2 * pad)
            val needed = titleSize * 1.3f + lines.size * bodySize * 1.4f
            if (needed <= textBottom - textTop || bodySize <= 10f) break
            bodySize -= 0.5f
            titleSize = max(15f, titleSize - 0.5f)
        }
        var y = textTop + titleSize
        pen.text(p.title, cx + pad + slide, y, titleSize, fade(TITLE, a), true, -1)
        y += titleSize * 0.4f
        for (line in lines) {
            y += bodySize * 1.4f
            pen.text(line, cx + pad + slide, y, bodySize, fade(BODY, a), false, -1)
        }

        // Buttons and progress.
        val by = cy + ch - pad - buttonH
        val nextW = 132f
        nextRect = floatArrayOf(cx + cw - pad - nextW, by, nextW, buttonH)
        val pulse = if (animate && isLast) 0.5f + 0.5f * sin(t * 4f) else 0f
        pen.fillRound(nextRect[0], by, nextW, buttonH, 8f, fade(mix(RED, 0xFFFF6A50.toInt(), pulse), open))
        pen.text(if (isLast) "C'EST PARTI" else "SUIVANT", nextRect[0] + nextW / 2f, by + buttonH * 0.66f, 13f, fade(WHITE, open), true, 0)

        if (!isLast) {
            skipRect = floatArrayOf(cx + pad, by, 92f, buttonH)
            pen.strokeRound(cx + pad, by, 92f, buttonH, 8f, fade(EDGE, open), 1.5f)
            pen.text("PASSER", cx + pad + 46f, by + buttonH * 0.66f, 12f, fade(MUTED, open), true, 0)
        } else {
            skipRect = FloatArray(4)
        }
        backRect = FloatArray(4)
        if (page > 0) {
            val bx = if (isLast) cx + pad else cx + pad + 100f
            backRect = floatArrayOf(bx, by, 40f, buttonH)
            pen.strokeRound(bx, by, 40f, buttonH, 8f, fade(EDGE, open), 1.5f)
            pen.text("‹", bx + 20f, by + buttonH * 0.7f, 18f, fade(MUTED, open), true, 0)
        }

        val dotsLeft = (if (page > 0) backRect[0] + backRect[2] else if (!isLast) skipRect[0] + skipRect[2] else cx + pad) + 8f
        val dotsRight = nextRect[0] - 8f
        val gap = 12f
        val dx = (dotsLeft + dotsRight) / 2f - gap * (pages.size - 1) / 2f
        if (dotsRight - dotsLeft > gap * pages.size) {
            for (i in pages.indices) {
                val on = i == page
                pen.fillCircle(dx + i * gap, by + buttonH / 2f, if (on) 4f else 3f, fade(if (on) RED else EDGE, open))
            }
        }
    }

    // ─── Pictures ─────────────────────────────────────────────

    private fun drawArt(pen: GuidePen, art: GuideArt, x: Float, y: Float, w: Float, h: Float, t: Float, a: Float) {
        val mx = x + w / 2f
        val my = y + h / 2f
        when (art) {
            GuideArt.WELCOME -> {
                val r = h * 0.32f
                pen.coin(mx, my, r, (t * 2f * PI / 4.0).toFloat())
                val maxRing = h / 2f - 3f
                for (i in 0 until 3) {
                    val k = ((t * 0.8f + i / 3f) % 1f)
                    pen.strokeCircle(mx, my, r + 4f + k * (maxRing - r - 4f), fade(RED, a * (1f - k) * 0.7f), 2f)
                }
            }
            GuideArt.SCREENS -> {
                val labels = listOf("BASS 1", "DRUMS 1", "SAMPLER", "BASS 2", "DRUMS 2")
                val phase = (t / 1.6f).toInt() % labels.size
                val local = (t / 1.6f) % 1f
                val tabW = min(78f, (w - 40f) / labels.size - 4f)
                val tx = mx - (tabW + 4f) * labels.size / 2f
                for (i in labels.indices) {
                    val on = i == phase
                    pen.fillRound(tx + i * (tabW + 4f), y + 12f, tabW, 24f, 6f, fade(if (on) PANEL else 0xFF3A3632.toInt(), a))
                    if (on) pen.strokeRound(tx + i * (tabW + 4f), y + 12f, tabW, 24f, 6f, fade(RED, a), 2f)
                    pen.text(labels[i], tx + i * (tabW + 4f) + tabW / 2f, y + 28f, 10f, fade(if (on) 0xFF1A1816.toInt() else BODY, a), true, 0)
                }
                // A screen sliding in from the right, with a swiping finger.
                val sw = min(w - 40f, 300f)
                val sh = h - 58f
                val move = ease((local * 2f).coerceIn(0f, 1f))
                val sx = mx - sw / 2f
                pen.fillRound(sx, y + 44f, sw, sh, 6f, fade(PANEL, a * 0.35f))
                val incoming = sx + (1f - move) * sw * 0.6f
                pen.fillRound(incoming, y + 44f, sw * (0.4f + 0.6f * move), sh, 6f, fade(PANEL, a))
                pen.text(labels[phase], incoming + 10f, y + 62f, 11f, fade(0xFF1A1816.toInt(), a), true, -1)
                for (k in 0 until 6) {
                    pen.fillCircle(incoming + 18f + k * 22f, y + 44f + sh * 0.65f, 6f, fade(0xFF4A4540.toInt(), a))
                }
                val fx = sx + sw * (0.85f - 0.6f * move)
                pen.fillCircle(fx, y + 44f + sh * 0.5f, 10f, fade(0x88FFFFFF.toInt(), a * (1f - move * 0.6f)))
            }
            GuideArt.RUN -> {
                val playing = (t % 4f) < 3f
                val bx = mx - 150f
                pen.fillRound(bx, my - 24f, 96f, 48f, 8f, fade(0xFF2E2C2A.toInt(), a))
                pen.strokeRound(bx, my - 24f, 96f, 48f, 8f, fade(EDGE, a), 1.5f)
                pen.fillCircle(bx + 48f, my - 10f, 4f, fade(if (playing) LED else 0xFF5A1A14.toInt(), a))
                pen.text(if (playing) "STOP" else "RUN", bx + 48f, my + 14f, 12f, fade(WHITE, a), true, 0)
                val step = (t * 8f).toInt() % 16
                val lx = mx - 36f
                for (i in 0 until 16) {
                    val col = i % 8
                    val row = i / 8
                    val on = playing && i == step
                    pen.fillCircle(lx + col * 22f, my - 11f + row * 22f, 6f, fade(if (on) LED else 0xFF4A4540.toInt(), a))
                }
            }
            GuideArt.STEPS -> {
                val pattern = intArrayOf(1, 0, 0, 1, 0, 0, 1, 0, 1, 0, 1, 0, 0, 1, 1, 0)
                val shown = ((t * 3f).toInt() % 20).coerceAtMost(16)
                val cell = min(30f, (w - 24f) / 16f - 2f)
                val gx = mx - (cell + 2f) * 8f
                val play = (t * 6f).toInt() % 16
                for (i in 0 until 16) {
                    val on = pattern[i] == 1 && i < shown
                    val sx = gx + i * (cell + 2f)
                    pen.fillRound(sx, my - cell / 2f, cell, cell, 4f, fade(if (on) PANEL else 0xFF3A3632.toInt(), a))
                    if (on && i == play) pen.strokeRound(sx, my - cell / 2f, cell, cell, 4f, fade(RED, a), 2f)
                    if (on) pen.fillCircle(sx + cell / 2f, my - cell / 2f + 6f, 2.5f, fade(LED, a))
                    if (i % 4 == 0) pen.text("${i + 1}", sx + cell / 2f, my + cell / 2f + 14f, 9f, fade(MUTED, a), false, 0)
                }
                // The finger taps the step that is being switched on.
                val target = (shown - 1).coerceIn(0, 15)
                if (shown < 16 && pattern[target] == 1) {
                    val sx = gx + target * (cell + 2f) + cell / 2f
                    pen.fillCircle(sx, my + 4f, 11f, fade(0x88FFFFFF.toInt(), a))
                }
                pen.text("ACCENT", mx - 60f, y + 22f, 10f, fade(if (shown % 4 == 1) RED else MUTED, a), true, 0)
                pen.text("SLIDE", mx + 60f, y + 22f, 10f, fade(if (shown % 4 == 3) RED else MUTED, a), true, 0)
            }
            GuideArt.KNOB -> {
                val r = h * 0.28f
                val v = 0.5f + 0.42f * sin(t * 1.6f)
                val kx = mx - 40f
                pen.arc(kx, my, r + 6f, 135f, 270f, fade(0xFF3A3632.toInt(), a), 4f)
                pen.arc(kx, my, r + 6f, 135f, 270f * v, fade(RED, a), 4f)
                pen.fillCircle(kx, my, r, fade(0xFF1E1C1A.toInt(), a))
                pen.strokeCircle(kx, my, r, fade(EDGE, a), 2f)
                val ang = Math.toRadians((135f + 270f * v).toDouble())
                pen.line(kx + (r * 0.3f * cos(ang)).toFloat(), my + (r * 0.3f * sin(ang)).toFloat(),
                    kx + (r * 0.85f * cos(ang)).toFloat(), my + (r * 0.85f * sin(ang)).toFloat(), fade(WHITE, a), 3f)
                pen.text("CUT OFF", kx, my + r + 22f, 10f, fade(BODY, a), true, 0)
                // Finger dragging up and down.
                val fy = my - (v - 0.5f) * r * 2f
                pen.fillCircle(kx + r + 34f, fy, 10f, fade(0x88FFFFFF.toInt(), a))
                pen.text("▲", kx + r + 34f, my - r - 2f, 11f, fade(MUTED, a), false, 0)
                pen.text("▼", kx + r + 34f, my + r + 12f, 11f, fade(MUTED, a), false, 0)
                // The sound it shapes: a resonant low-pass curve following the knob.
                val gx = mx + 70f
                val gw = min(140f, x + w - gx - 12f)
                if (gw > 40f) {
                    var px = gx
                    var py = my
                    val corner = gx + gw * (0.2f + 0.7f * v)
                    for (k in 1..40) {
                        val qx = gx + gw * k / 40f
                        val d = (qx - corner) / gw
                        val qy = my - 14f * kotlin.math.exp(-d * d * 300f) + if (qx > corner) (qx - corner) * 1.2f else 0f
                        pen.line(px, py, qx, min(qy, y + h - 8f), fade(LED, a), 2f)
                        px = qx; py = min(qy, y + h - 8f)
                    }
                }
            }
            GuideArt.SAMPLER -> {
                val rec = (t % 3f) < 2f
                val pw = 30f
                val px0 = mx - 150f
                for (i in 0 until 8) {
                    val sel = i == 2
                    val c = i % 4
                    val r = i / 4
                    pen.fillRound(px0 + c * (pw + 6f), my - pw - 3f + r * (pw + 6f), pw, pw, 5f,
                        fade(if (sel) PANEL else 0xFF3A3632.toInt(), a))
                    if (sel) pen.strokeRound(px0 + c * (pw + 6f), my - pw - 3f + r * (pw + 6f), pw, pw, 5f, fade(RED, a), 2f)
                }
                val wx = mx + 10f
                val ww = min(150f, x + w - wx - 12f)
                pen.fillCircle(wx + 8f, y + 18f, 5f, fade(if (rec && (t * 2f).toInt() % 2 == 0) LED else 0xFF5A1A14.toInt(), a))
                pen.text("REC", wx + 18f, y + 22f, 10f, fade(BODY, a), true, -1)
                val drawn = if (rec) (t % 3f) / 2f else 1f
                val n = 48
                for (k in 0 until (n * drawn).toInt()) {
                    val amp = (0.25f + 0.75f * abs(sin(k * 0.37f) * sin(k * 0.11f + 1f))) * h * 0.3f
                    val lx = wx + ww * k / n
                    pen.line(lx, my - amp, lx, my + amp, fade(LED, a), 2f)
                }
            }
            GuideArt.END -> {
                val r = h * 0.3f
                pen.coin(mx, my, r, (t * 2f * PI / 4.0).toFloat())
                for (i in 0 until 10) {
                    val ang = i * 2f * PI.toFloat() / 10f + t * 0.6f
                    val k = (t * 0.7f + i * 0.1f) % 1f
                    val d = r + 8f + k * (h / 2f - r - 14f)
                    pen.fillCircle(mx + cos(ang) * d * 1.6f, my + sin(ang) * d, 3f, fade(if (i % 2 == 0) RED else 0xFF5FA640.toInt(), a * (1f - k)))
                }
            }
        }
    }

    private companion object {
        const val CARD = 0xFF26231F.toInt()
        const val STAGE = 0xFF1A1816.toInt()
        const val EDGE = 0xFF57504A.toInt()
        const val TITLE = 0xFFFF4A3A.toInt()
        const val BODY = 0xFFE8E2D8.toInt()
        const val MUTED = 0xFFA39B91.toInt()
        const val RED = 0xFFE8231B.toInt()
        const val LED = 0xFFFF3B2A.toInt()
        const val PANEL = 0xFFD9D4CA.toInt()
        const val WHITE = 0xFFFFFFFF.toInt()

        fun ease(x: Float) = 1f - (1f - x) * (1f - x) * (1f - x)

        fun fade(color: Int, alpha: Float): Int {
            val a = ((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt()
            return (a shl 24) or (color and 0xFFFFFF)
        }

        fun mix(c1: Int, c2: Int, k: Float): Int {
            fun ch(shift: Int) = (((c1 shr shift) and 0xFF) * (1f - k) + ((c2 shr shift) and 0xFF) * k).toInt() shl shift
            return (0xFF shl 24) or ch(16) or ch(8) or ch(0)
        }
    }
}
