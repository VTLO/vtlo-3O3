package com.vtlo.desktop.ui

import com.vtlo.core.audio.WavWriter
import com.vtlo.core.dsp.Resampler
import com.vtlo.core.dsp.WavReader
import com.vtlo.core.seq.StepPatternBank
import com.vtlo.desktop.Recorder
import com.vtlo.sampler.dsp.PadParam
import com.vtlo.sampler.dsp.SampleTools
import com.vtlo.sampler.dsp.SamplerEngine
import com.vtlo.sampler.dsp.SamplerParam
import java.awt.event.KeyEvent
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.swing.SwingUtilities
import kotlin.concurrent.thread
import kotlin.math.roundToInt
import kotlin.random.Random

/** The sampler page: record or load into eight pads, per-pad effects, step sequencer. */
class SamplerScreen(host: Host) : Screen(host) {
    private val engine = host.studio.sampler
    private val bank: StepPatternBank get() = engine.bank
    private val pattern get() = engine.selectedPattern
    private val recorder = Recorder(engine.sampleRate)

    private var selectedPad = 0
    private var clipboard: IntArray? = null
    private var undo: IntArray? = null
    private var recordings = 0
    private var lastStep = -2
    private val held = HashSet<Int>()
    private var frameCount = 0
    private val seenHits = IntArray(SamplerEngine.PADS) { engine.hits.get(it) }
    private val litUntil = LongArray(SamplerEngine.PADS)

    private val master = HashMap<SamplerParam, Knob>()
    private val padKnobs = HashMap<PadParam, Knob>()
    private lateinit var lengthKnob: Knob
    private val pads = ArrayList<SamplePad>()
    private val cells = ArrayList<GridStep>()
    private val patternPads = ArrayList<Pad>()
    private lateinit var bankPad: Pad
    private lateinit var run: Pad
    private lateinit var rec: Pad
    private lateinit var reverse: Pad
    private lateinit var mute: Pad
    private lateinit var waveform: Waveform

    init {
        val t = TOP
        add(TextBox({ "VTLO SAMPLER" }, 22f, Colors.panelText), 16f, t + 4, 300f, 28f)
        add(TextBox({ "COMPUTER CONTROLLED  ·  SAMPLER" }, 10f, Colors.red), 16f, t + 32, 320f, 14f)
        rec = add(Pad("REC", led = true, hint = "Record the microphone into the selected pad") { toggleRecording() }, 520f, t + 6, 120f, 46f)
        add(Meter { recorder.level }, 648f, t + 19, 92f, 20f)
        add(BpmDisplay { SamplerParam.normToBpm(engine.get(SamplerParam.TEMPO)).roundToInt() }, 748f, t + 8, 90f, 40f)
        run = add(Pad("RUN", led = true, hint = "Start/stop all instruments (Space)") { host.toggleTransport() }, 846f, t + 6, 146f, 46f)

        // Sample and pad effects
        val ya = t + 60
        add(PanelBox {
            val name = engine.padNames[selectedPad]
            "SAMPLE · PAD ${selectedPad + 1}" + if (recorder.isRecording) " · RECORDING" else if (name.isNotEmpty()) " · $name" else ""
        }, 8f, ya, 460f, 226f)
        waveform = add(Waveform { s, e ->
            engine.set(selectedPad, PadParam.START, s); engine.set(selectedPad, PadParam.END, e)
            padKnobs[PadParam.START]?.set(s); padKnobs[PadParam.END]?.set(e)
        }, 18f, ya + 24, 440f, 148f)
        val tools = listOf(
            Pad("LOAD", hint = "Load a WAV, AIFF or AU file into the pad") { load() },
            Pad("NORMAL", hint = "Normalize the pad") { normalize() },
            Pad("REVERSE") {
                engine.set(selectedPad, PadParam.REVERSE, if (engine.get(selectedPad, PadParam.REVERSE) >= 0.5f) 0f else 1f)
                refreshPad()
            }.also { reverse = it },
            Pad("MUTE") { engine.setMuted(selectedPad, !engine.isMuted(selectedPad)); refreshPad() }.also { mute = it },
            Pad("ERASE", hint = "Erase the pad") { assign(selectedPad, null, "") },
        )
        tools.forEachIndexed { i, p -> add(p, 16f + i * 88.8f, ya + 178, 88.8f, 42f) }

        add(PanelBox { "PAD FX" }, 476f, ya, 516f, 226f)
        val pk = listOf<Triple<PadParam, String, (Float) -> String>>(
            Triple(PadParam.LEVEL, "LEVEL", ::pct),
            Triple(PadParam.TUNE, "TUNE") { v -> val st = ((v - 0.5f) * 48f).roundToInt(); (if (st > 0) "+" else "") + st },
            Triple(PadParam.START, "START", ::pct),
            Triple(PadParam.END, "END", ::pct),
            Triple(PadParam.ATTACK, "ATTACK") { v -> "${(v * v * 1000).roundToInt()}ms" },
            Triple(PadParam.DECAY, "DECAY") { v -> if (v >= 0.99f) "FULL" else "${(20 * Math.pow(10.0, v * 2.0)).roundToInt()}ms" },
            Triple(PadParam.FILTER, "FILTER") { v ->
                when {
                    v < 0.48f -> "LP " + hz(80 * Math.pow(250.0, v / 0.48))
                    v > 0.52f -> "HP " + hz(20 * Math.pow(400.0, (v - 0.52) / 0.48))
                    else -> "OFF"
                }
            },
            Triple(PadParam.RESO, "RESO", ::pct),
            Triple(PadParam.CRUSH, "CRUSH") { v -> if (v < 0.01f) "OFF" else "${(16 - 13 * v).roundToInt()}bit" },
            Triple(PadParam.DRIVE, "DRIVE", ::pct),
            Triple(PadParam.PAN, "PAN") { v -> val p = ((v - 0.5f) * 200).roundToInt(); if (p < 0) "L${-p}" else if (p > 0) "R$p" else "C" },
            Triple(PadParam.SEND, "FX SEND", ::pct),
        )
        pk.forEachIndexed { i, (p, l, f) ->
            padKnobs[p] = add(Knob(l, p.default, f, { v ->
                engine.set(selectedPad, p, v)
                if (p == PadParam.START || p == PadParam.END) waveform.setSample(engine.sample(selectedPad), cur(PadParam.START), cur(PadParam.END))
            }), 484f + (i % 6) * 84f, ya + 22 + (i / 6) * 101f, 84f, 100f)
        }

        // Pads and master
        val yb = t + 294
        add(PanelBox { "PADS" }, 8f, yb, 380f, 130f)
        for (p in 0 until SamplerEngine.PADS) {
            pads.add(add(SamplePad(p) { selectPad(p); engine.trigger(p, false) }, 16f + (p % 4) * 91f, yb + 24 + (p / 4) * 51f, 91f, 51f))
        }
        add(PanelBox { "MASTER · TEMPO" }, 396f, yb, 596f, 130f)
        val mk = listOf<Triple<SamplerParam, String, (Float) -> String>>(
            Triple(SamplerParam.ACCENT, "ACCENT", ::pct),
            Triple(SamplerParam.DELAY, "DELAY", ::pct),
            Triple(SamplerParam.DELAY_FEEDBACK, "FEEDBACK", ::pct),
            Triple(SamplerParam.REVERB, "REVERB", ::pct),
            Triple(SamplerParam.VOLUME, "VOLUME", ::pct),
            Triple(SamplerParam.TEMPO, "TEMPO") { v -> "${SamplerParam.normToBpm(v).roundToInt()}" },
            Triple(SamplerParam.SWING, "SWING") { v -> "${50 + (v * 20).roundToInt()}%" },
        )
        mk.forEachIndexed { i, (p, l, f) ->
            master[p] = add(Knob(l, p.default, f, { v -> engine.set(p, v) }), 404f + i * 72.5f, yb + 22, 72.5f, 104f)
        }
        lengthKnob = add(Knob("LENGTH", 1f, { v -> "${lengthFrom(v)}" }, { v ->
            bank.setLength(pattern, lengthFrom(v)); refreshSteps()
        }, detents = StepPatternBank.STEPS), 404f + 7 * 72.5f, yb + 22, 72.5f, 104f)

        // Steps of the selected pad
        val yc = t + 432
        add(PanelBox { "STEPS · PAD ${selectedPad + 1} · PATTERN ${patternLabel(pattern)}" }, 8f, yc, 984f, 82f)
        val cw = 968f / 16
        add(Ruler({ if (host.playing && engine.playingPattern == pattern) engine.currentStep else -1 }, { bank.length(pattern) }),
            16f, yc + 20, cw * 16, 12f)
        for (s in 0 until StepPatternBank.STEPS) {
            cells.add(add(GridStep(s, { "Pad ${selectedPad + 1}" }, {
                bank.toggle(pattern, selectedPad, s)
                if (!host.playing && bank.isOn(pattern, selectedPad, s)) engine.trigger(selectedPad, bank.isAccent(pattern, selectedPad, s))
                refreshSteps()
            }, {
                bank.toggleAccent(pattern, selectedPad, s)
                if (!host.playing && bank.isOn(pattern, selectedPad, s)) engine.trigger(selectedPad, bank.isAccent(pattern, selectedPad, s))
                refreshSteps()
            }), 16f + s * cw, yc + 34, cw, 42f))
        }

        val yd = t + 520
        add(PanelBox { "PATTERN" }, 8f, yd, 470f, 58f)
        bankPad = add(Pad("BANK A", dark = false) { selectPattern((pattern + 8) % StepPatternBank.PATTERNS) }, 14f, yd + 18, 51f, 38f)
        for (i in 0 until 8) {
            patternPads.add(add(Pad("${i + 1}", dark = false) { selectPattern((pattern / 8) * 8 + i) }, 14f + (i + 1) * 51f, yd + 18, 51f, 38f))
        }
        add(PanelBox { "UTILITY" }, 486f, yd, 506f, 58f)
        val utils = listOf(
            Pad("RANDOM") { modify { randomize() } },
            Pad("CLEAR") { modify { bank.clear(pattern) } },
            Pad("UNDO") { restoreUndo() },
            Pad("COPY") { clipboard = bank.snapshot(pattern); host.toast("Pattern ${patternLabel(pattern)} copied") },
            Pad("PASTE") { clipboard?.let { c -> modify { bank.restore(pattern, c) } } ?: host.toast("Nothing copied yet") },
            Pad("PANIC", hint = "Stop all sound (Esc)") { host.studio.engine.panic() },
            Pad("EXPORT", hint = "Export four loops of this pattern as WAV") { export() },
        )
        utils.forEachIndexed { i, p -> add(p, 492f + i * 70.8f, yd + 18, 70.8f, 38f) }
    }

    private fun hz(v: Double) = if (v >= 1000) "%.1fk".format(v / 1000) else "${v.roundToInt()}"
    private fun cur(p: PadParam) = engine.get(selectedPad, p)
    private fun lengthFrom(v: Float) = 1 + (v * (StepPatternBank.STEPS - 1)).roundToInt()

    // ─── Sample editing ──────────────────────────────────────

    private fun assign(pad: Int, data: FloatArray?, name: String) {
        engine.setSample(pad, data)
        engine.padNames[pad] = name
        engine.set(pad, PadParam.START, 0f)
        engine.set(pad, PadParam.END, 1f)
        host.studio.saveSample(pad, data)
        refresh()
    }

    private fun toggleRecording() {
        if (recorder.isRecording) return finishRecording()
        recorder.start()?.let { host.toast(it); return }
        rec.active = true
    }

    private fun finishRecording() {
        val raw = recorder.stop()
        rec.active = false
        rec.text = "REC"
        waveform.recording = null
        val trimmed = SampleTools.trimSilence(raw, engine.sampleRate)
        if (trimmed.isEmpty()) {
            host.toast("Nothing recorded (only silence)")
            refreshPad()
            return
        }
        recordings++
        assign(selectedPad, SampleTools.fadeEdges(SampleTools.normalize(trimmed), engine.sampleRate), "MIC $recordings")
    }

    private fun load() {
        val pad = selectedPad
        host.openAudio { file ->
            host.toast("Loading…")
            thread(name = "vtlo-load", isDaemon = true) {
                val data = runCatching { decode(file) }.getOrNull()
                SwingUtilities.invokeLater {
                    if (data == null || data.isEmpty()) host.toast("Could not read ${file.name}")
                    else assign(pad, SampleTools.fadeEdges(data, engine.sampleRate), file.nameWithoutExtension.uppercase().take(16))
                }
            }
        }
    }

    /** WAV is read exactly; AIFF/AU (and WAV variants Java Sound knows) go through Java Sound. Mono, engine rate, ≤ 30 s. */
    private fun decode(file: File): FloatArray {
        val head = file.inputStream().use { s -> ByteArray(12).also { s.read(it) } }
        val (data, rate) = if (String(head, 0, 4, Charsets.US_ASCII) == "RIFF") {
            val s = file.inputStream().use(WavReader::read)
            s.data to s.sampleRate
        } else {
            AudioSystem.getAudioInputStream(file).use { src ->
                val f = src.format
                val target = AudioFormat(f.sampleRate, 16, f.channels, true, false)
                AudioSystem.getAudioInputStream(target, src).use { pcm ->
                    val bytes = pcm.readAllBytes()
                    val ch = target.channels
                    val frames = bytes.size / (2 * ch)
                    FloatArray(frames) { i ->
                        var sum = 0f
                        for (c in 0 until ch) {
                            val o = (i * ch + c) * 2
                            sum += ((bytes[o].toInt() and 0xff) or (bytes[o + 1].toInt() shl 8)) / 32768f
                        }
                        sum / ch
                    } to f.sampleRate.roundToInt()
                }
            }
        }
        val limited = data.copyOf(minOf(data.size, rate * Recorder.MAX_SECONDS))
        return Resampler.resample(limited, rate, engine.sampleRate)
    }

    private fun normalize() {
        val d = engine.sample(selectedPad) ?: return host.toast("Pad is empty")
        val s = cur(PadParam.START)
        val e = cur(PadParam.END)
        assign(selectedPad, SampleTools.normalize(d), engine.padNames[selectedPad])
        engine.set(selectedPad, PadParam.START, s)
        engine.set(selectedPad, PadParam.END, e)
        refreshPad()
    }

    // ─── Actions ─────────────────────────────────────────────

    private fun selectPad(p: Int) {
        selectedPad = p
        refreshPads(); refreshPad()
    }

    private fun selectPattern(p: Int) {
        engine.selectedPattern = p
        refresh()
    }

    private inline fun modify(f: () -> Unit) {
        undo = bank.snapshot(pattern)
        f()
        refresh()
    }

    private fun restoreUndo() {
        val u = undo ?: return host.toast("Nothing to undo")
        undo = bank.snapshot(pattern)
        bank.restore(pattern, u)
        refresh()
    }

    private fun randomize() {
        for (p in 0 until SamplerEngine.PADS) {
            var r = 0
            if (engine.sample(p) != null) for (s in 0 until StepPatternBank.STEPS) {
                if (Random.nextFloat() < (if (s % 4 == 0) 0.35f else 0.12f)) {
                    r = r or (1 shl s)
                    if (Random.nextFloat() < 0.3f) r = r or (1 shl (s + 16))
                }
            }
            bank.setRow(pattern, p, r)
        }
    }

    private fun export() {
        val params = SamplerParam.entries.associateWith { engine.get(it) }
        val padParams = Array(SamplerEngine.PADS) { p -> PadParam.entries.associateWith { engine.get(p, it) } }
        val data = Array(SamplerEngine.PADS) { engine.sample(it) }
        val muted = BooleanArray(SamplerEngine.PADS) { engine.isMuted(it) }
        val rows = bank.snapshot(pattern)
        val from = engine.sampleRate
        host.saveWav("vtlo-sampler-pattern-${patternLabel(pattern)}.wav") { out ->
            val rate = 48000
            val e = SamplerEngine(rate)
            params.forEach { (p, v) -> e.set(p, v) }
            for (p in 0 until SamplerEngine.PADS) {
                padParams[p].forEach { (k, v) -> e.set(p, k, v) }
                e.setSample(p, data[p]?.let { Resampler.resample(it, from, rate) })
                e.setMuted(p, muted[p])
            }
            e.bank.restore(0, rows)
            val step = 60.0 / SamplerParam.normToBpm(e.get(SamplerParam.TEMPO)) / 4.0
            WavWriter.render(out, e, rate, (4 * rows[0] * step * rate).toInt(), rate * 3)
        }
    }

    // ─── Refresh ─────────────────────────────────────────────

    override fun refresh() {
        SamplerParam.entries.forEach { p -> master[p]?.set(engine.get(p)) }
        bankPad.text = if (pattern < 8) "BANK A" else "BANK B"
        patternPads.forEachIndexed { i, b -> b.active = pattern % 8 == i }
        lengthKnob.set((bank.length(pattern) - 1f) / (StepPatternBank.STEPS - 1))
        run.active = host.playing
        run.text = if (host.playing) "STOP" else "RUN"
        refreshPads()
        refreshPad()
    }

    private fun refreshPads() {
        for (p in pads.indices) pads[p].apply {
            name = engine.padNames[p]
            empty = engine.sample(p) == null
            selected = p == selectedPad
            hasSteps = bank.row(pattern, p) != 0
        }
    }

    private fun refreshPad() {
        val d = engine.sample(selectedPad)
        waveform.setSample(d, cur(PadParam.START), cur(PadParam.END))
        waveform.label = if (d == null) "" else "%.2fs".format(d.size / engine.sampleRate.toFloat())
        PadParam.entries.forEach { k -> padKnobs[k]?.set(cur(k)) }
        reverse.active = cur(PadParam.REVERSE) >= 0.5f
        mute.active = engine.isMuted(selectedPad)
        refreshSteps()
    }

    private fun refreshSteps() {
        val len = bank.length(pattern)
        val step = if (host.playing && engine.playingPattern == pattern) engine.currentStep else -1
        for (s in cells.indices) cells[s].apply {
            on = bank.isOn(pattern, selectedPad, s)
            accent = bank.isAccent(pattern, selectedPad, s)
            inLength = s < len
            playing = s == step
        }
        for (p in pads.indices) pads[p].hasSteps = bank.row(pattern, p) != 0
    }

    override fun onFrame(playing: Boolean) {
        if (recorder.isRecording) {
            if (recorder.isFull) finishRecording() else {
                rec.text = "STOP %.1fs".format(recorder.frames / engine.sampleRate.toFloat())
                waveform.recording = Triple(recorder.overview, recorder.overviewCount, recorder.overview.size)
            }
        } else {
            waveform.playhead = engine.playhead(selectedPad)
        }
        val step = if (playing && engine.playingPattern == pattern) engine.currentStep else -1
        if (step != lastStep) {
            lastStep = step
            cells.forEachIndexed { s, c -> c.playing = s == step }
        }
        val now = System.currentTimeMillis()
        for (p in pads.indices) {
            val h = engine.hits.get(p)
            if (h != seenHits[p]) {
                seenHits[p] = h; litUntil[p] = now + 90
            }
            pads[p].lit = now < litUntil[p]
        }
        if (++frameCount % 15 == 0) {
            val queued = playing && engine.playingPattern != pattern
            val blink = (frameCount / 15) % 2 == 0
            patternPads.forEachIndexed { i, b -> b.active = pattern % 8 == i && (!queued || blink) }
        }
    }

    /** Pads on the computer keyboard: Q W E R (1–4), A S D F (5–8). */
    override fun key(code: Int, pressed: Boolean): Boolean {
        val p = PAD_KEYS.indexOf(code)
        if (p < 0) return false
        if (!pressed) held.remove(code)
        if (pressed && held.add(code)) {
            selectPad(p)
            engine.trigger(p, false)
        }
        return true
    }

    override fun close() {
        if (recorder.isRecording) finishRecording()
    }

    private companion object {
        val PAD_KEYS = listOf(KeyEvent.VK_Q, KeyEvent.VK_W, KeyEvent.VK_E, KeyEvent.VK_R, KeyEvent.VK_A, KeyEvent.VK_S, KeyEvent.VK_D, KeyEvent.VK_F)
    }
}
