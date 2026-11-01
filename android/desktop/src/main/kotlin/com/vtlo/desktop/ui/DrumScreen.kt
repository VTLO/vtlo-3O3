package com.vtlo.desktop.ui

import com.vtlo.core.audio.WavWriter
import com.vtlo.core.seq.StepPatternBank
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.DrumPresets
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.TrackParam
import kotlin.math.roundToInt

/** The drum machine page: kits, track and master knobs, the 8×16 grid. */
class DrumScreen(host: Host) : Screen(host) {
    private val engine = host.studio.drums
    private val bank: StepPatternBank get() = engine.bank
    private val pattern get() = engine.selectedPattern

    private var selectedTrack = 0
    private var clipboard: IntArray? = null
    private var undo: IntArray? = null
    private var lastStep = -2
    private var frameCount = 0
    private val seenHits = IntArray(Kit.TRACKS) { engine.hits.get(it) }
    private val litUntil = LongArray(Kit.TRACKS)

    private val master = HashMap<DrumParam, Knob>()
    private val trackKnobs = HashMap<TrackParam, Knob>()
    private lateinit var kitKnob: Knob
    private lateinit var lengthKnob: Knob
    private val kitPads = ArrayList<Pad>()
    private val mutes = ArrayList<Pad>()
    private val names = ArrayList<TrackPad>()
    private val cells = Array(Kit.TRACKS) { ArrayList<GridStep>() }
    private val patternPads = ArrayList<Pad>()
    private lateinit var bankPad: Pad
    private lateinit var run: Pad
    private lateinit var ruler: Ruler

    init {
        val t = TOP
        add(TextBox({ "VTLO DRUMS" }, 22f, Colors.panelText), 16f, t + 4, 300f, 28f)
        add(TextBox({ "COMPUTER CONTROLLED  ·  RHYTHM" }, 10f, Colors.red), 16f, t + 32, 320f, 14f)
        Kit.entries.forEachIndexed { i, kit ->
            kitPads.add(add(Pad(kit.label, led = true, dark = false, hint = "All tracks: ${kit.label} kit") {
                for (tr in 0 until Kit.TRACKS) engine.setKit(tr, kit)
                refreshTracks()
            }, 500f + i * 78f, t + 6, 72f, 46f))
        }
        add(BpmDisplay { DrumParam.normToBpm(engine.get(DrumParam.TEMPO)).roundToInt() }, 744f, t + 8, 90f, 40f)
        run = add(Pad("RUN", led = true, hint = "Start/stop all instruments (Space)") { host.toggleTransport() }, 842f, t + 6, 150f, 46f)

        val y1 = t + 60
        add(PanelBox { val k = engine.kit(selectedTrack); "TRACK · ${k.trackNames[selectedTrack]} · ${k.label}" }, 8f, y1, 360f, 140f)
        val tk = listOf<Triple<TrackParam, String, (Float) -> String>>(
            Triple(TrackParam.LEVEL, "LEVEL", ::pct),
            Triple(TrackParam.TUNE, "TUNE") { v -> val st = ((v - 0.5f) * 24f).roundToInt(); (if (st > 0) "+" else "") + st },
            Triple(TrackParam.DECAY, "DECAY") { v -> if (v >= 0.99f) "FULL" else "${(30 * Math.pow(10.0, v * 2.0)).roundToInt()}ms" },
            Triple(TrackParam.PAN, "PAN") { v -> val p = ((v - 0.5f) * 200).roundToInt(); if (p < 0) "L${-p}" else if (p > 0) "R$p" else "C" },
        )
        tk.forEachIndexed { i, (p, l, f) ->
            trackKnobs[p] = add(Knob(l, p.default, f, { v -> engine.set(selectedTrack, p, v) }), 16f + i * 69f, y1 + 22, 69f, 114f)
        }
        kitKnob = add(Knob("KIT", 1f, { v -> Kit.entries[kitIndex(v)].label }, { v ->
            engine.setKit(selectedTrack, Kit.entries[kitIndex(v)]); refreshTracks()
        }, detents = Kit.entries.size), 16f + 4 * 69f, y1 + 22, 69f, 114f)

        add(PanelBox { "MASTER · TEMPO" }, 376f, y1, 616f, 140f)
        val mk = listOf<Triple<DrumParam, String, (Float) -> String>>(
            Triple(DrumParam.ACCENT, "ACCENT", ::pct),
            Triple(DrumParam.DRIVE, "DRIVE", ::pct),
            Triple(DrumParam.DELAY, "DELAY", ::pct),
            Triple(DrumParam.DELAY_FEEDBACK, "FEEDBACK", ::pct),
            Triple(DrumParam.REVERB, "REVERB", ::pct),
            Triple(DrumParam.VOLUME, "VOLUME", ::pct),
            Triple(DrumParam.TEMPO, "TEMPO") { v -> "${DrumParam.normToBpm(v).roundToInt()}" },
            Triple(DrumParam.SWING, "SWING") { v -> "${50 + (v * 20).roundToInt()}%" },
        )
        mk.forEachIndexed { i, (p, l, f) ->
            master[p] = add(Knob(l, p.default, f, { v -> engine.set(p, v) }), 384f + i * 66.6f, y1 + 22, 66.6f, 114f)
        }
        lengthKnob = add(Knob("LENGTH", 1f, { v -> "${lengthFrom(v)}" }, { v ->
            bank.setLength(pattern, lengthFrom(v)); refreshSteps()
        }, detents = StepPatternBank.STEPS), 384f + 8 * 66.6f, y1 + 22, 66.6f, 114f)

        val y2 = t + 208
        add(PanelBox { "SEQUENCER · PATTERN ${patternLabel(pattern)}" }, 8f, y2, 984f, 282f)
        val gx = 152f
        val cw = (984f - gx) / 16
        ruler = add(Ruler({ if (host.playing && engine.playingPattern == pattern) engine.currentStep else -1 }, { bank.length(pattern) }),
            gx, y2 + 22, cw * 16, 14f)
        for (tr in 0 until Kit.TRACKS) {
            val ry = y2 + 40 + tr * 29.5f
            mutes.add(add(Pad("M", hint = "Mute this track") { engine.setMuted(tr, !engine.isMuted(tr)); refreshTracks() }, 16f, ry, 50f, 29.5f))
            names.add(add(TrackPad { selectedTrack = tr; engine.trigger(tr, false); refreshTracks() }, 68f, ry, 80f, 29.5f))
            for (s in 0 until StepPatternBank.STEPS) {
                cells[tr].add(add(GridStep(s, { engine.kit(tr).trackNames[tr] }, {
                    bank.toggle(pattern, tr, s)
                    if (!host.playing && bank.isOn(pattern, tr, s)) engine.trigger(tr, bank.isAccent(pattern, tr, s))
                    refreshSteps()
                }, {
                    bank.toggleAccent(pattern, tr, s)
                    if (!host.playing && bank.isOn(pattern, tr, s)) engine.trigger(tr, bank.isAccent(pattern, tr, s))
                    refreshSteps()
                }), gx + s * cw, ry, cw, 29.5f))
            }
        }

        val y3 = t + 498
        add(PanelBox { "PATTERN" }, 8f, y3, 470f, 78f)
        bankPad = add(Pad("BANK A", dark = false) { selectPattern((pattern + 8) % StepPatternBank.PATTERNS) }, 14f, y3 + 24, 51f, 48f)
        for (i in 0 until 8) {
            patternPads.add(add(Pad("${i + 1}", led = true, dark = false) { selectPattern((pattern / 8) * 8 + i) },
                14f + (i + 1) * 51f, y3 + 24, 51f, 48f))
        }
        add(PanelBox { "UTILITY" }, 486f, y3, 506f, 78f)
        lateinit var presetPad: Pad
        val utils = listOf(
            Pad("PRESET") { presetMenu(presetPad) }.also { presetPad = it },
            Pad("RANDOM") { modify { DrumPresets.randomize(bank, pattern) } },
            Pad("CLEAR") { modify { bank.clear(pattern) } },
            Pad("UNDO") { restoreUndo() },
            Pad("COPY") { clipboard = bank.snapshot(pattern); host.toast("Pattern ${patternLabel(pattern)} copied") },
            Pad("PASTE") { clipboard?.let { c -> modify { bank.restore(pattern, c) } } ?: host.toast("Nothing copied yet") },
            Pad("PANIC", hint = "Stop all sound (Esc)") { host.studio.engine.panic() },
            Pad("EXPORT", hint = "Export four loops of this pattern as WAV") { export() },
        )
        utils.forEachIndexed { i, p -> add(p, 492f + i * 62f, y3 + 24, 62f, 48f) }
    }

    private fun kitIndex(v: Float) = (v * (Kit.entries.size - 1)).roundToInt()
    private fun lengthFrom(v: Float) = 1 + (v * (StepPatternBank.STEPS - 1)).roundToInt()

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

    private fun presetMenu(anchor: Pad) {
        host.popup(DrumPresets.all.map { "${it.name}  ·  ${it.kit.label}  ·  ${it.bpm} BPM" }, anchor.x, anchor.y - 8f * 30) { i ->
            val p = DrumPresets.all[i]
            modify {
                DrumPresets.load(bank, pattern, p)
                for (tr in 0 until Kit.TRACKS) engine.setKit(tr, p.kit)
                engine.set(DrumParam.TEMPO, DrumParam.bpmToNorm(p.bpm.toFloat()))
            }
        }
    }

    private fun export() {
        val params = DrumParam.entries.associateWith { engine.get(it) }
        val trackParams = Array(Kit.TRACKS) { tr -> TrackParam.entries.associateWith { engine.get(tr, it) } }
        val kits = Array(Kit.TRACKS) { engine.kit(it) }
        val muted = BooleanArray(Kit.TRACKS) { engine.isMuted(it) }
        val rows = bank.snapshot(pattern)
        val studio = host.studio
        host.saveWav("vtlo-drums-pattern-${patternLabel(pattern)}.wav") { out ->
            val rate = 48000
            val e = DrumEngine(rate, studio.drumSamplesFor(rate))
            params.forEach { (p, v) -> e.set(p, v) }
            for (tr in 0 until Kit.TRACKS) {
                trackParams[tr].forEach { (p, v) -> e.set(tr, p, v) }
                e.setKit(tr, kits[tr]); e.setMuted(tr, muted[tr])
            }
            e.bank.restore(0, rows)
            val step = 60.0 / DrumParam.normToBpm(e.get(DrumParam.TEMPO)) / 4.0
            WavWriter.render(out, e, rate, (4 * rows[0] * step * rate).toInt(), rate * 2)
        }
    }

    override fun refresh() {
        DrumParam.entries.forEach { p -> master[p]?.set(engine.get(p)) }
        bankPad.text = if (pattern < 8) "BANK A" else "BANK B"
        patternPads.forEachIndexed { i, b -> b.active = pattern % 8 == i }
        lengthKnob.set((bank.length(pattern) - 1f) / (StepPatternBank.STEPS - 1))
        run.active = host.playing
        run.text = if (host.playing) "STOP" else "RUN"
        refreshTracks()
    }

    private fun refreshTracks() {
        for (tr in 0 until Kit.TRACKS) {
            val kit = engine.kit(tr)
            names[tr].apply { name = kit.trackNames[tr]; tag = kit.label; selected = tr == selectedTrack }
            mutes[tr].apply { active = engine.isMuted(tr); text = if (active) "MUTE" else "M" }
        }
        TrackParam.entries.forEach { p -> trackKnobs[p]?.set(engine.get(selectedTrack, p)) }
        kitKnob.set(engine.kit(selectedTrack).ordinal / (Kit.entries.size - 1f))
        kitPads.forEachIndexed { i, b -> b.active = (0 until Kit.TRACKS).all { engine.kit(it).ordinal == i } }
        refreshSteps()
    }

    private fun refreshSteps() {
        val len = bank.length(pattern)
        val cur = if (host.playing && engine.playingPattern == pattern) engine.currentStep else -1
        for (tr in 0 until Kit.TRACKS) for (s in cells[tr].indices) cells[tr][s].apply {
            on = bank.isOn(pattern, tr, s)
            accent = bank.isAccent(pattern, tr, s)
            inLength = s < len
            playing = s == cur
        }
    }

    override fun onFrame(playing: Boolean) {
        val step = if (playing && engine.playingPattern == pattern) engine.currentStep else -1
        if (step != lastStep) {
            lastStep = step
            for (tr in 0 until Kit.TRACKS) cells[tr].forEachIndexed { s, c -> c.playing = s == step }
        }
        val now = System.currentTimeMillis()
        for (tr in 0 until Kit.TRACKS) {
            val h = engine.hits.get(tr)
            if (h != seenHits[tr]) {
                seenHits[tr] = h; litUntil[tr] = now + 90
            }
            names[tr].lit = now < litUntil[tr]
        }
        if (++frameCount % 15 == 0) {
            val queued = playing && engine.playingPattern != pattern
            val blink = (frameCount / 15) % 2 == 0
            patternPads.forEachIndexed { i, b -> b.active = pattern % 8 == i && (!queued || blink) }
        }
    }
}
