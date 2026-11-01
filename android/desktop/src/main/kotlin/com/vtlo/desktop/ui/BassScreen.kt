package com.vtlo.desktop.ui

import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.acid303.dsp.WavExporter
import java.awt.event.KeyEvent
import javax.swing.Timer
import kotlin.math.roundToInt

private val NOTE_NAMES = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
fun noteName(midi: Int) = NOTE_NAMES[midi % 12] + (midi / 12 - 1)
fun pct(v: Float) = "${(v * 100).roundToInt()}"
fun patternLabel(p: Int) = "${if (p < 8) 'A' else 'B'}${p % 8 + 1}"

/** The TB-303 page: same controls and behaviour as the Android landscape layout. */
class BassScreen(host: Host) : Screen(host) {
    private val engine = host.studio.bass
    private val bank: PatternBank get() = engine.bank
    private val pattern get() = engine.selectedPattern

    private var selectedStep = 0
    private var clipboard: IntArray? = null
    private var undo: IntArray? = null
    private var lastStep = -2
    private val held = HashSet<Int>()
    private var frameCount = 0

    private val knobs = HashMap<Param, Knob>()
    private val steps = ArrayList<NoteStep>()
    private val patternPads = ArrayList<Pad>()
    private lateinit var bankPad: Pad
    private lateinit var run: Pad
    private lateinit var wave: Pad
    private lateinit var gatePad: Pad
    private lateinit var accentPad: Pad
    private lateinit var slidePad: Pad
    private lateinit var keys: Keys
    private lateinit var lengthKnob: Knob
    private val previewOff = Timer(180) { if (!host.playing) engine.previewNoteOff() }.apply { isRepeats = false }

    init {
        val t = TOP
        add(TextBox({ "VTLO 3O3" }, 22f, Colors.panelText), 16f, t + 4, 300f, 28f)
        add(TextBox({ "COMPUTER CONTROLLED  ·  BASS LINE" }, 10f, Colors.red), 16f, t + 32, 320f, 14f)
        add(BpmDisplay { Param.normToBpm(engine.get(Param.TEMPO)).roundToInt() }, 740f, t + 8, 90f, 40f)
        run = add(Pad("RUN", led = true, hint = "Start/stop all instruments (Space)") { host.toggleTransport() }, 840f, t + 6, 150f, 46f)

        // Voice
        val y1 = t + 60
        add(PanelBox { "VOICE" }, 8f, y1, 480f, 150f)
        wave = add(Pad("SAW", led = true, dark = false, hint = "Waveform: saw or square") {
            engine.set(Param.WAVEFORM, if (engine.get(Param.WAVEFORM) < 0.5f) 1f else 0f)
            refresh()
        }, 16f, y1 + 46, 60f, 60f)
        val voice = listOf<Triple<Param, String, (Float) -> String>>(
            Triple(Param.TUNE, "TUNING") { v -> val st = (v - 0.5f) * 24f; (if (st >= 0) "+" else "") + "%.1f".format(st) },
            Triple(Param.CUTOFF, "CUT OFF", ::pct),
            Triple(Param.RESONANCE, "RESO", ::pct),
            Triple(Param.ENV_MOD, "ENV MOD", ::pct),
            Triple(Param.DECAY, "DECAY") { v -> "${(200 * Math.pow(10.0, v.toDouble())).roundToInt()}ms" },
            Triple(Param.ACCENT, "ACCENT", ::pct),
        )
        voice.forEachIndexed { i, (p, l, f) -> add(knob(p, l, f), 82f + i * 67f, y1 + 22, 67f, 124f) }

        add(PanelBox { "EFFECTS · MASTER · TEMPO" }, 496f, y1, 496f, 150f)
        val fx = listOf<Triple<Param, String, (Float) -> String>>(
            Triple(Param.DRIVE, "DRIVE", ::pct),
            Triple(Param.DELAY, "DELAY", ::pct),
            Triple(Param.DELAY_FEEDBACK, "FEEDBACK", ::pct),
            Triple(Param.REVERB, "REVERB", ::pct),
            Triple(Param.VOLUME, "VOLUME", ::pct),
            Triple(Param.TEMPO, "TEMPO") { v -> "${Param.normToBpm(v).roundToInt()}" },
            Triple(Param.SWING, "SWING") { v -> "${50 + (v * 20).roundToInt()}%" },
        )
        fx.forEachIndexed { i, (p, l, f) -> add(knob(p, l, f), 502f + i * 60.5f, y1 + 22, 60.5f, 124f) }
        lengthKnob = add(Knob("LENGTH", 1f, { v -> "${lengthFrom(v)}" }, { v ->
            bank.setLength(pattern, lengthFrom(v)); refreshSteps()
        }, detents = PatternBank.STEPS), 502f + 7 * 60.5f, y1 + 22, 60.5f, 124f)

        // Steps
        val y2 = t + 218
        add(PanelBox { "SEQUENCER · PATTERN ${patternLabel(pattern)}" }, 8f, y2, 984f, 92f)
        for (i in 0 until PatternBank.STEPS) {
            steps.add(add(NoteStep(i, { selectStep(i) }, { selectedStep = i; editStep { PatternBank.toggleGate(it) } }),
                16f + i * 60.5f, y2 + 22, 60.5f, 66f))
        }

        // Keyboard, step edit, pattern, utility
        val y3 = t + 318
        val h3 = 254f
        add(PanelBox { "KEYBOARD" }, 8f, y3, 340f, h3)
        keys = add(Keys({ k -> onKey(k) }, { if (!host.playing) engine.previewNoteOff() }), 18f, y3 + 26, 320f, h3 - 36)

        add(PanelBox { "STEP ${selectedStep + 1}" }, 356f, y3, 250f, h3)
        val ch = (h3 - 36) / 3
        val edit = listOf(
            Pad("◀ STEP", hint = "Previous step") { selectStep((selectedStep + 15) % 16) },
            Pad("STEP ▶", hint = "Next step") { selectStep((selectedStep + 1) % 16) },
            Pad("GATE", led = true, hint = "Note or rest") { editStep { PatternBank.toggleGate(it) } }.also { gatePad = it },
            Pad("NOTE −") { transpose(-1) },
            Pad("NOTE +") { transpose(1) },
            Pad("ACCENT", led = true) { editStep { PatternBank.toggleAccent(it) } }.also { accentPad = it },
            Pad("OCT −") { transpose(-12) },
            Pad("OCT +") { transpose(12) },
            Pad("SLIDE", led = true, hint = "Glide into the next step") { editStep { PatternBank.toggleSlide(it) } }.also { slidePad = it },
        )
        edit.forEachIndexed { i, p -> add(p, 364f + (i % 3) * 79f, y3 + 26 + (i / 3) * ch, 79f, ch) }

        add(PanelBox { "PATTERN" }, 614f, y3, 180f, h3)
        bankPad = add(Pad("BANK A", dark = false) { selectPattern((pattern + 8) % PatternBank.PATTERNS) }, 620f, y3 + 26, 56f, ch)
        for (i in 0 until 8) {
            val c = (i + 1) % 3
            val r = (i + 1) / 3
            patternPads.add(add(Pad("${i + 1}", led = true, dark = false) { selectPattern((pattern / 8) * 8 + i) },
                620f + c * 56f, y3 + 26 + r * ch, 56f, ch))
        }

        add(PanelBox { "UTILITY" }, 802f, y3, 190f, h3)
        lateinit var presetPad: Pad
        val utils = listOf(
            Pad("PRESET") { presetMenu(presetPad) }.also { presetPad = it },
            Pad("RANDOM") { modify { Presets.randomize(bank, pattern) } },
            Pad("CLEAR") { modify { bank.clear(pattern) } },
            Pad("UNDO") { restoreUndo() },
            Pad("COPY") { clipboard = snapshot(); host.toast("Pattern ${patternLabel(pattern)} copied") },
            Pad("PASTE") { clipboard?.let { c -> modify { restore(c) } } ?: host.toast("Nothing copied yet") },
            Pad("PANIC", hint = "Stop all sound (Esc)") { host.studio.engine.panic() },
            Pad("EXPORT", hint = "Export four loops of this pattern as WAV") { export() },
        )
        utils.forEachIndexed { i, p -> add(p, 808f + (i % 3) * 60f, y3 + 26 + (i / 3) * ch, 60f, ch) }
    }

    private fun knob(p: Param, label: String, format: (Float) -> String) =
        Knob(label, p.default, format, { v -> engine.set(p, v) }).also { knobs[p] = it }

    private fun lengthFrom(v: Float) = 1 + (v * (PatternBank.STEPS - 1)).roundToInt()

    // ─── Actions ─────────────────────────────────────────────

    private fun selectPattern(p: Int) {
        engine.selectedPattern = p
        refresh()
    }

    private fun selectStep(i: Int) {
        selectedStep = i
        val s = bank.step(pattern, i)
        if (!host.playing && PatternBank.gate(s)) {
            engine.previewNoteOn(PatternBank.note(s), PatternBank.accent(s))
            previewOff.restart()
        }
        refreshSteps()
    }

    private inline fun editStep(f: (Int) -> Int) {
        bank.setStep(pattern, selectedStep, f(bank.step(pattern, selectedStep)))
        refreshSteps()
    }

    private fun transpose(semis: Int) {
        val n = PatternBank.note(bank.step(pattern, selectedStep)) + semis
        if (n !in PatternBank.MIN_NOTE..PatternBank.MAX_NOTE) return
        editStep { PatternBank.withNote(it, n) }
        selectStep(selectedStep)
    }

    private fun keyboardBase(): Int {
        val n = PatternBank.note(bank.step(pattern, selectedStep))
        return ((n / 12) * 12).coerceIn(PatternBank.MIN_NOTE, PatternBank.MAX_NOTE - 12)
    }

    private fun onKey(k: Int) {
        val note = keyboardBase() + k
        var s = PatternBank.withNote(bank.step(pattern, selectedStep), note)
        if (!PatternBank.gate(s)) s = PatternBank.toggleGate(s)
        bank.setStep(pattern, selectedStep, s)
        if (!host.playing) engine.previewNoteOn(note, PatternBank.accent(s))
        refreshSteps()
    }

    private fun snapshot() = IntArray(PatternBank.STEPS + 1) { if (it == 0) bank.length(pattern) else bank.step(pattern, it - 1) }

    private fun restore(d: IntArray) {
        bank.setLength(pattern, d[0])
        for (i in 0 until PatternBank.STEPS) bank.setStep(pattern, i, d[i + 1])
    }

    private inline fun modify(f: () -> Unit) {
        undo = snapshot()
        f()
        refresh()
    }

    private fun restoreUndo() {
        val u = undo ?: return host.toast("Nothing to undo")
        undo = snapshot()
        restore(u)
        refresh()
    }

    private fun presetMenu(anchor: Pad) {
        host.popup(Presets.all.map { "${it.name}  ·  ${it.bpm} BPM" }, anchor.x, anchor.y + anchor.h) { i ->
            val p = Presets.all[i]
            modify {
                Presets.load(bank, pattern, p)
                engine.set(Param.TEMPO, Param.bpmToNorm(p.bpm.toFloat()))
            }
        }
    }

    private fun export() {
        val params = Param.entries.associateWith { engine.get(it) }
        val steps = snapshot()
        host.saveWav("vtlo-303-pattern-${patternLabel(pattern)}.wav") { out ->
            WavExporter.export(out, loops = 4, patternLength = steps[0]) { e ->
                params.forEach { (p, v) -> e.set(p, v) }
                e.bank.setLength(0, steps[0])
                for (i in 0 until PatternBank.STEPS) e.bank.setStep(0, i, steps[i + 1])
                e.selectedPattern = 0
            }
        }
    }

    // ─── Refresh ─────────────────────────────────────────────

    override fun refresh() {
        Param.entries.forEach { p -> knobs[p]?.set(engine.get(p)) }
        val sq = engine.get(Param.WAVEFORM) >= 0.5f
        wave.text = if (sq) "SQUARE" else "SAW"
        wave.active = sq
        bankPad.text = if (pattern < 8) "BANK A" else "BANK B"
        patternPads.forEachIndexed { i, b -> b.active = pattern % 8 == i }
        lengthKnob.set((bank.length(pattern) - 1f) / (PatternBank.STEPS - 1))
        run.active = host.playing
        run.text = if (host.playing) "STOP" else "RUN"
        refreshSteps()
    }

    private fun refreshSteps() {
        val len = bank.length(pattern)
        val showPlayhead = host.playing && engine.playingPattern == pattern
        for (i in steps.indices) {
            val s = bank.step(pattern, i)
            steps[i].apply {
                note = noteName(PatternBank.note(s))
                gate = PatternBank.gate(s)
                accent = PatternBank.accent(s)
                slide = PatternBank.slide(s)
                selected = i == selectedStep
                inLength = i < len
                playing = showPlayhead && i == engine.currentStep
            }
        }
        val s = bank.step(pattern, selectedStep)
        gatePad.active = PatternBank.gate(s)
        accentPad.active = PatternBank.accent(s)
        slidePad.active = PatternBank.slide(s)
        val base = keyboardBase()
        keys.octave = (base / 12 - 1).toString()
        val n = PatternBank.note(s) - base
        keys.highlighted = if (PatternBank.gate(s) && n in 0..12) n else -1
    }

    override fun onFrame(playing: Boolean) {
        val step = if (playing && engine.playingPattern == pattern) engine.currentStep else -1
        if (step != lastStep) {
            lastStep = step
            steps.forEachIndexed { i, c -> c.playing = i == step }
        }
        if (++frameCount % 15 == 0) {
            val queued = playing && engine.playingPattern != pattern
            val blink = (frameCount / 15) % 2 == 0
            patternPads.forEachIndexed { i, b -> b.active = pattern % 8 == i && (!queued || blink) }
        }
    }

    /** Computer keyboard as a piano: Z S X D C V G B H N J M , = C .. C. */
    override fun key(code: Int, pressed: Boolean): Boolean {
        val k = PIANO.indexOf(code)
        if (k < 0) return false
        if (!pressed) {
            held.remove(code)
            if (!host.playing) engine.previewNoteOff()
        } else if (held.add(code)) {
            onKey(k) // ignore auto-repeat while the key is held
        }
        return true
    }

    private companion object {
        val PIANO = listOf(
            KeyEvent.VK_Z, KeyEvent.VK_S, KeyEvent.VK_X, KeyEvent.VK_D, KeyEvent.VK_C, KeyEvent.VK_V, KeyEvent.VK_G,
            KeyEvent.VK_B, KeyEvent.VK_H, KeyEvent.VK_N, KeyEvent.VK_J, KeyEvent.VK_M, KeyEvent.VK_COMMA,
        )
    }
}
