package com.vtlo.drums

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.vtlo.core.audio.WavWriter
import com.vtlo.core.ui.FitScaleLayout
import com.vtlo.core.ui.FlowGrid
import com.vtlo.core.ui.InstrumentScreen
import com.vtlo.core.ui.KnobView
import com.vtlo.core.ui.PadButton
import com.vtlo.core.ui.Palette
import com.vtlo.core.ui.ScreenHost
import com.vtlo.core.ui.dpi
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.DrumPatternBank
import com.vtlo.drums.dsp.DrumPresets
import com.vtlo.drums.dsp.Kit
import com.vtlo.drums.dsp.SampleBank
import com.vtlo.drums.dsp.TrackParam
import kotlin.math.roundToInt

/**
 * The drum machine page. In landscape every control fits one screen (scaled to
 * the window); in portrait the page scrolls and each track's 16 steps wrap onto
 * two lines on narrow screens.
 *
 * @param samplesFor sample bank at a given rate, for WAV export.
 */
class DrumScreen(
    host: ScreenHost,
    private val engine: DrumEngine,
    private val samplesFor: (Int) -> SampleBank,
) : InstrumentScreen(host) {

    private val bank: DrumPatternBank get() = engine.bank
    private val pattern get() = engine.selectedPattern

    private var selectedTrack = 0
    private var clipboard: IntArray? = null
    private var undo: IntArray? = null

    private val masterKnobs = HashMap<DrumParam, KnobView>()
    private val trackKnobs = HashMap<TrackParam, KnobView>()
    private lateinit var kitKnob: KnobView
    private lateinit var lengthKnob: KnobView
    private val kitButtons = ArrayList<PadButton>()
    private val trackButtons = ArrayList<TrackButton>()
    private val muteButtons = ArrayList<PadButton>()
    private val cells = Array(Kit.TRACKS) { ArrayList<DrumStepCell>() }
    private val patternButtons = ArrayList<PadButton>()
    private lateinit var bankButton: PadButton
    private lateinit var playButton: PadButton
    private lateinit var bpmText: TextView
    private lateinit var trackTitle: TextView
    private var ruler: StepRuler? = null

    private var lastStep = -2
    private var blinkPhase = false
    private var frameCount = 0
    private val seenHits = IntArray(Kit.TRACKS) { engine.hits.get(it) }
    private val litUntil = LongArray(Kit.TRACKS)

    private fun dpi(v: Int) = context.dpi(v)

    // ─── UI construction ─────────────────────────────────────

    override fun build(landscape: Boolean): View {
        val view = if (landscape) landscapeUi() else portraitUi()
        refreshAll()
        return view
    }

    private fun landscapeUi(): View {
        val fit = FitScaleLayout(context, dpi(DESIGN_WIDTH_DP))
        val content = column().apply {
            val pad = dpi(8)
            setPadding(pad, pad / 2, pad, pad)
        }
        fit.addView(content)
        content.addView(header(landscape = true))

        val top = row()
        top.addView(trackPanel(intArrayOf(5)), weighted(5f, endMargin = 8))
        top.addView(masterPanel(intArrayOf(9)), weighted(9f))
        content.addView(top)

        content.addView(panel("SEQUENCER").apply { addView(sequencerGrid(landscape = true)) })

        val bottom = row()
        bottom.addView(panel("PATTERN").apply {
            addView(patternGrid(intArrayOf(9), cellHeight = dpi(44)))
        }, weighted(8f, endMargin = 8))
        bottom.addView(panel("UTILITY").apply {
            addView(utilityGrid(intArrayOf(8), cellHeight = dpi(44)))
        }, weighted(10f))
        content.addView(bottom)
        return fit
    }

    private fun portraitUi(): View {
        val scroll = ScrollView(context).apply { isFillViewport = true }
        val content = column().apply {
            val pad = dpi(10)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content, ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        content.addView(header(landscape = false))
        content.addView(trackPanel(null))
        content.addView(masterPanel(null))
        content.addView(panel("SEQUENCER").apply { addView(sequencerGrid(landscape = false)) })
        content.addView(panel("PATTERN").apply { addView(patternGrid(intArrayOf(3, 9))) })
        content.addView(panel("UTILITY").apply { addView(utilityGrid(intArrayOf(4, 8))) })
        return scroll
    }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dpi(8) }
    }

    private fun weighted(weight: Float, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply { marginEnd = dpi(endMargin) }

    private fun header(landscape: Boolean): View {
        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(6), dpi(4), dpi(6), dpi(6))
        }
        val titles = column()
        titles.addView(TextView(context).apply {
            text = context.getString(R.string.drums_title)
            setTextColor(Palette.panelText)
            textSize = if (landscape) 20f else 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        titles.addView(TextView(context).apply {
            text = context.getString(R.string.drums_subtitle)
            setTextColor(Palette.red)
            textSize = 10f
            letterSpacing = 0.15f
        })
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        // Kit buttons switch every track at once; the TRACK panel's KIT knob mixes kits.
        val kits = if (landscape) top else LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dpi(6), 0, dpi(6), dpi(8))
        }
        for (kit in Kit.entries) {
            val b = PadButton(context, kit.label, hasLed = true, dark = false).apply {
                contentDescription = "All tracks ${kit.label} kit"
                setOnClickListener {
                    for (t in 0 until Kit.TRACKS) engine.setKit(t, kit)
                    refreshTracks()
                }
            }
            kitButtons.add(b)
            val lp = if (landscape) LinearLayout.LayoutParams(dpi(72), dpi(48)) else LinearLayout.LayoutParams(0, dpi(48), 1f)
            kits.addView(b, lp.apply { marginEnd = dpi(6) })
        }

        bpmText = TextView(context).apply {
            setTextColor(Palette.ledOn)
            textSize = 22f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dpi(12), dpi(2), dpi(12), dpi(2))
            background = GradientDrawable().apply {
                setColor(0xFF0E0C0B.toInt()); cornerRadius = dpi(6).toFloat()
                setStroke(dpi(1), Palette.knobRim)
            }
            contentDescription = "Tempo"
        }
        top.addView(bpmText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(8) })

        playButton = PadButton(context, "RUN", hasLed = true).apply {
            setOnClickListener { host.toggleTransport() }
        }
        top.addView(playButton, LinearLayout.LayoutParams(dpi(if (landscape) 140 else 96), dpi(48)).apply { marginStart = dpi(10) })
        if (landscape) return top
        return column().apply {
            addView(top)
            addView(kits)
        }
    }

    private fun panel(title: String): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(8), dpi(6), dpi(8), dpi(8))
        background = GradientDrawable().apply {
            setColor(Palette.panel)
            cornerRadius = dpi(10).toFloat()
            setStroke(dpi(1), Palette.panelEdge)
        }
        addView(TextView(context).apply {
            text = title
            setTextColor(Palette.red)
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.2f
            setPadding(dpi(4), 0, 0, dpi(4))
            isFocusable = false
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dpi(8) }
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}"

    private fun knobGrid(columns: IntArray?) =
        if (columns != null) FlowGrid(context, dpi(40), allowedColumns = columns) else FlowGrid(context, dpi(66))

    private fun trackPanel(columns: IntArray?) = panel("TRACK").apply {
        trackTitle = getChildAt(0) as TextView
        addView(knobGrid(columns).also(::addTrackControls))
    }

    private fun masterPanel(columns: IntArray?) = panel("MASTER · TEMPO").apply {
        addView(knobGrid(columns).also(::addMasterControls))
    }

    private fun masterKnob(p: DrumParam, label: String, format: (Float) -> String, onChange: (() -> Unit)? = null) =
        KnobView(context, label, p.default, format, { v ->
            engine.set(p, v)
            onChange?.invoke()
        }).also {
            it.setValue(engine.get(p))
            masterKnobs[p] = it
        }

    private fun trackKnob(p: TrackParam, label: String, format: (Float) -> String) =
        KnobView(context, label, p.default, format, { v -> engine.set(selectedTrack, p, v) }).also { trackKnobs[p] = it }

    private fun addTrackControls(grid: ViewGroup) {
        grid.addView(trackKnob(TrackParam.LEVEL, "LEVEL", ::pct))
        grid.addView(trackKnob(TrackParam.TUNE, "TUNE") { v ->
            val st = ((v - 0.5f) * 24f).roundToInt()
            (if (st > 0) "+" else "") + st
        })
        grid.addView(trackKnob(TrackParam.DECAY, "DECAY") { v ->
            if (v >= 0.99f) "FULL" else "${(30 * Math.pow(10.0, v * 2.0)).roundToInt()}ms"
        })
        grid.addView(trackKnob(TrackParam.PAN, "PAN") { v ->
            val p = ((v - 0.5f) * 200).roundToInt()
            when {
                p < 0 -> "L${-p}"
                p > 0 -> "R$p"
                else -> "C"
            }
        })
        kitKnob = KnobView(context, "KIT", 1f, { v -> Kit.entries[kitIndex(v)].label }, { v ->
            engine.setKit(selectedTrack, Kit.entries[kitIndex(v)])
            refreshTracks()
        }, detents = Kit.entries.size)
        grid.addView(kitKnob)
    }

    private fun kitIndex(v: Float) = (v * (Kit.entries.size - 1)).roundToInt()

    private fun addMasterControls(grid: ViewGroup) {
        grid.addView(masterKnob(DrumParam.ACCENT, "ACCENT", ::pct))
        grid.addView(masterKnob(DrumParam.DRIVE, "DRIVE", ::pct))
        grid.addView(masterKnob(DrumParam.DELAY, "DELAY", ::pct))
        grid.addView(masterKnob(DrumParam.DELAY_FEEDBACK, "FEEDBACK", ::pct))
        grid.addView(masterKnob(DrumParam.REVERB, "REVERB", ::pct))
        grid.addView(masterKnob(DrumParam.VOLUME, "VOLUME", ::pct))
        grid.addView(masterKnob(DrumParam.TEMPO, "TEMPO", { v -> "${DrumParam.normToBpm(v).roundToInt()}" }) { refreshBpm() })
        grid.addView(masterKnob(DrumParam.SWING, "SWING", { v -> "${50 + (v * 20).roundToInt()}%" }))
        lengthKnob = KnobView(context, "LENGTH", 1f, { v -> "${lengthFromNorm(v)}" }, { v ->
            bank.setLength(pattern, lengthFromNorm(v))
            refreshSteps()
        }, detents = DrumPatternBank.STEPS)
        grid.addView(lengthKnob)
    }

    private fun sequencerGrid(landscape: Boolean): View {
        val grid = column()
        if (landscape) {
            ruler = StepRuler(context).also {
                grid.addView(it, LinearLayout.LayoutParams(MATCH_PARENT, dpi(16)).apply {
                    marginStart = dpi(MUTE_WIDTH_DP + NAME_WIDTH_DP + 4)
                })
            }
        }
        for (t in 0 until Kit.TRACKS) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            val mute = PadButton(context, "M").apply {
                setOnClickListener {
                    engine.setMuted(t, !engine.isMuted(t))
                    refreshTracks()
                }
            }
            muteButtons.add(mute)
            line.addView(mute, LinearLayout.LayoutParams(dpi(MUTE_WIDTH_DP), MATCH_PARENT))
            val name = TrackButton(context).apply {
                setOnClickListener {
                    selectTrack(t)
                    engine.trigger(t, false)
                }
            }
            trackButtons.add(name)
            line.addView(name, LinearLayout.LayoutParams(dpi(NAME_WIDTH_DP), MATCH_PARENT).apply { marginEnd = dpi(4) })
            // Landscape: one line of 16. Portrait: 16 if they fit, otherwise two lines of 8.
            val steps = FlowGrid(
                context, dpi(if (landscape) 20 else 26), cellHeight = dpi(ROW_HEIGHT_DP),
                allowedColumns = if (landscape) intArrayOf(16) else intArrayOf(8, 16)
            )
            for (s in 0 until DrumPatternBank.STEPS) {
                val cell = DrumStepCell(context, s).apply {
                    setOnClickListener {
                        bank.toggle(pattern, t, s)
                        if (!host.transport.playing && bank.isOn(pattern, t, s)) engine.trigger(t, bank.isAccent(pattern, t, s))
                        refreshRow(t)
                    }
                    setOnLongClickListener {
                        bank.toggleAccent(pattern, t, s)
                        if (!host.transport.playing && bank.isOn(pattern, t, s)) engine.trigger(t, bank.isAccent(pattern, t, s))
                        refreshRow(t)
                        true
                    }
                }
                cells[t].add(cell)
                steps.addView(cell)
            }
            line.addView(steps, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            grid.addView(line, LinearLayout.LayoutParams(MATCH_PARENT, if (landscape) dpi(ROW_HEIGHT_DP) else WRAP_CONTENT).apply {
                if (!landscape) bottomMargin = dpi(4)
            })
        }
        return grid
    }

    private fun patternGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(context, dpi(30), cellHeight = cellHeight, allowedColumns = columns)
        bankButton = PadButton(context, "BANK A", dark = false).apply {
            setOnClickListener { selectPattern((pattern + 8) % DrumPatternBank.PATTERNS) }
        }
        grid.addView(bankButton)
        for (i in 0 until 8) {
            grid.addView(PadButton(context, "${i + 1}", hasLed = true, dark = false).apply {
                setOnClickListener { selectPattern((pattern / 8) * 8 + i) }
                patternButtons.add(this)
            })
        }
        return grid
    }

    private fun pad(label: String, action: (View) -> Unit) =
        PadButton(context, label).apply { setOnClickListener { action(it) } }

    private fun utilityGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(context, dpi(30), cellHeight = cellHeight, allowedColumns = columns)
        grid.addView(pad("PRESET") { v -> showPresets(v) })
        grid.addView(pad("RANDOM") { modifyPattern { DrumPresets.randomize(bank, pattern) } })
        grid.addView(pad("CLEAR") { modifyPattern { bank.clear(pattern) } })
        grid.addView(pad("UNDO") { restoreUndo() })
        grid.addView(pad("COPY") {
            clipboard = bank.snapshot(pattern)
            host.toast("Pattern ${patternLabel(pattern)} copied")
        })
        grid.addView(pad("PASTE") {
            val c = clipboard ?: return@pad host.toast("Nothing copied yet")
            modifyPattern { bank.restore(pattern, c) }
        })
        grid.addView(pad("PANIC") {
            host.transport.panic()
            onTransportChanged(false)
        }.also { it.contentDescription = "Stop all sound" })
        grid.addView(pad("EXPORT") { startExport() }.also { it.contentDescription = "Export drums as WAV" })
        return grid
    }

    // ─── Actions ─────────────────────────────────────────────

    private fun selectPattern(p: Int) {
        engine.selectedPattern = p
        refreshAll()
    }

    private fun selectTrack(t: Int) {
        selectedTrack = t
        refreshTracks()
    }

    private inline fun modifyPattern(f: () -> Unit) {
        undo = bank.snapshot(pattern)
        f()
        refreshAll()
    }

    private fun restoreUndo() {
        val u = undo ?: return host.toast("Nothing to undo")
        undo = bank.snapshot(pattern)
        bank.restore(pattern, u)
        refreshAll()
    }

    private fun showPresets(anchor: View) {
        PopupMenu(context, anchor).apply {
            DrumPresets.all.forEachIndexed { i, p -> menu.add(0, i, i, "${p.name}  ·  ${p.kit.label}  ·  ${p.bpm} BPM") }
            setOnMenuItemClickListener { item ->
                val p = DrumPresets.all[item.itemId]
                modifyPattern {
                    DrumPresets.load(bank, pattern, p)
                    for (t in 0 until Kit.TRACKS) engine.setKit(t, p.kit)
                    engine.set(DrumParam.TEMPO, DrumParam.bpmToNorm(p.bpm.toFloat()))
                }
                true
            }
            show()
        }
    }

    /** Renders four loops of the current pattern with the current sound to a 48 kHz WAV. */
    private fun startExport() {
        val live = engine
        val params = DrumParam.entries.associateWith { live.get(it) }
        val trackParams = Array(Kit.TRACKS) { t -> TrackParam.entries.associateWith { live.get(t, it) } }
        val kits = Array(Kit.TRACKS) { live.kit(it) }
        val mutes = BooleanArray(Kit.TRACKS) { live.isMuted(it) }
        val rows = bank.snapshot(pattern)
        host.requestExport("vtlo-drums-pattern-${patternLabel(pattern)}.wav") { out ->
            val e = DrumEngine(EXPORT_RATE, samplesFor(EXPORT_RATE))
            params.forEach { (p, v) -> e.set(p, v) }
            for (t in 0 until Kit.TRACKS) {
                trackParams[t].forEach { (p, v) -> e.set(t, p, v) }
                e.setKit(t, kits[t])
                e.setMuted(t, mutes[t])
            }
            e.bank.restore(0, rows)
            e.selectedPattern = 0
            val stepSeconds = 60.0 / DrumParam.normToBpm(e.get(DrumParam.TEMPO)) / 4.0
            val playFrames = (EXPORT_LOOPS * rows[0] * stepSeconds * EXPORT_RATE).toInt()
            WavWriter.render(out, e, EXPORT_RATE, playFrames, EXPORT_RATE * 2)
        }
    }

    // ─── Refresh ─────────────────────────────────────────────

    override fun refreshAll() {
        DrumParam.entries.forEach { p -> masterKnobs[p]?.setValue(engine.get(p)) }
        refreshBpm()
        refreshPatterns()
        refreshTracks()
        onTransportChanged(host.transport.playing)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshBpm() {
        bpmText.text = "%3d".format(DrumParam.normToBpm(engine.get(DrumParam.TEMPO)).roundToInt())
    }

    private fun patternLabel(p: Int) = "${if (p < 8) 'A' else 'B'}${p % 8 + 1}"

    private fun refreshPatterns() {
        bankButton.setLabel(if (pattern < 8) "BANK A" else "BANK B")
        patternButtons.forEachIndexed { i, b ->
            b.active = (pattern % 8) == i
            b.contentDescription = "Pattern ${patternLabel((pattern / 8) * 8 + i)}"
        }
        lengthKnob.setValue(normFromLength(bank.length(pattern)))
    }

    /** Track names, kits, mutes, the selected track's knobs and the kit buttons. */
    @SuppressLint("SetTextI18n")
    private fun refreshTracks() {
        for (t in 0 until Kit.TRACKS) {
            val kit = engine.kit(t)
            trackButtons[t].apply {
                name = kit.trackNames[t]
                this.kit = kit.label
                selectedTrack = t == this@DrumScreen.selectedTrack
                refresh()
            }
            muteButtons[t].apply {
                active = engine.isMuted(t)
                setLabel(if (active) "MUTE" else "M")
                contentDescription = "Mute ${kit.trackNames[t]}"
            }
        }
        val kit = engine.kit(selectedTrack)
        trackTitle.text = "TRACK · ${kit.trackNames[selectedTrack]} · ${kit.label}"
        TrackParam.entries.forEach { p -> trackKnobs[p]?.setValue(engine.get(selectedTrack, p)) }
        kitKnob.setValue(kit.ordinal / (Kit.entries.size - 1f))
        kitButtons.forEachIndexed { i, b -> b.active = (0 until Kit.TRACKS).all { engine.kit(it).ordinal == i } }
        refreshSteps()
    }

    private fun refreshSteps() {
        for (t in 0 until Kit.TRACKS) refreshRow(t)
        ruler?.length = bank.length(pattern)
    }

    private fun refreshRow(t: Int) {
        val len = bank.length(pattern)
        val showPlayhead = host.transport.playing && engine.playingPattern == pattern
        val cur = engine.currentStep
        val name = engine.kit(t).trackNames[t]
        for (s in cells[t].indices) cells[t][s].apply {
            on = bank.isOn(pattern, t, s)
            accent = bank.isAccent(pattern, t, s)
            inLength = s < len
            playing = showPlayhead && s == cur
            trackName = name
            refresh()
        }
    }

    override fun onTransportChanged(playing: Boolean) {
        playButton.active = playing
        playButton.setLabel(if (playing) "STOP" else "RUN")
        refreshSteps()
    }

    override fun onFrame(playing: Boolean) {
        val step = if (playing && engine.playingPattern == pattern) engine.currentStep else -1
        if (step != lastStep) {
            for (t in 0 until Kit.TRACKS) {
                if (lastStep in 0 until DrumPatternBank.STEPS) cells[t][lastStep].apply { this.playing = false; invalidate() }
                if (step in 0 until DrumPatternBank.STEPS) cells[t][step].apply { this.playing = true; invalidate() }
            }
            ruler?.current = step
            lastStep = step
        }
        // Flash each track's LED on its hits.
        val now = SystemClock.uptimeMillis()
        for (t in 0 until Kit.TRACKS) {
            val h = engine.hits.get(t)
            if (h != seenHits[t]) {
                seenHits[t] = h
                litUntil[t] = now + HIT_FLASH_MS
            }
            trackButtons[t].lit = now < litUntil[t]
        }
        // Blink the selected pattern LED while a pattern change is queued.
        if (++frameCount % 15 == 0) {
            blinkPhase = !blinkPhase
            val queued = playing && engine.playingPattern != pattern
            patternButtons.forEachIndexed { i, b ->
                b.active = (pattern % 8) == i && (!queued || blinkPhase)
            }
        }
    }

    private fun lengthFromNorm(v: Float) = 1 + (v * (DrumPatternBank.STEPS - 1)).roundToInt()
    private fun normFromLength(len: Int) = (len - 1f) / (DrumPatternBank.STEPS - 1)

    private companion object {
        const val DESIGN_WIDTH_DP = 900
        const val MUTE_WIDTH_DP = 52
        const val NAME_WIDTH_DP = 76
        const val ROW_HEIGHT_DP = 32
        const val HIT_FLASH_MS = 90L
        const val EXPORT_RATE = 48000
        const val EXPORT_LOOPS = 4
    }
}
