package com.vtlo.sampler

import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.vtlo.core.audio.WavWriter
import com.vtlo.core.dsp.Resampler
import com.vtlo.core.seq.StepPatternBank
import com.vtlo.core.ui.FitScaleLayout
import com.vtlo.core.ui.FlowGrid
import com.vtlo.core.ui.GridStepCell
import com.vtlo.core.ui.InstrumentScreen
import com.vtlo.core.ui.KnobView
import com.vtlo.core.ui.PadButton
import com.vtlo.core.ui.Palette
import com.vtlo.core.ui.ScreenHost
import com.vtlo.core.ui.StepRuler
import com.vtlo.core.ui.dpi
import com.vtlo.sampler.dsp.PadParam
import com.vtlo.sampler.dsp.SampleTools
import com.vtlo.sampler.dsp.SamplerEngine
import com.vtlo.sampler.dsp.SamplerParam
import java.util.concurrent.Executors
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The sampler page: record from the microphone or load a file into any of eight
 * pads, shape each pad (trim, pitch, envelope, reverse, filter, crusher, drive,
 * pan, effects send) and sequence them. Landscape fits one screen; portrait scrolls.
 */
class SamplerScreen(host: ScreenHost, private val engine: SamplerEngine) : InstrumentScreen(host) {

    private val bank: StepPatternBank get() = engine.bank
    private val pattern get() = engine.selectedPattern
    private val recorder = Recorder(context.applicationContext, engine.sampleRate)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var selectedPad = 0
    private var clipboard: IntArray? = null
    private var undo: IntArray? = null
    private var recordings = 0

    private val masterKnobs = HashMap<SamplerParam, KnobView>()
    private val padKnobs = HashMap<PadParam, KnobView>()
    private lateinit var lengthKnob: KnobView
    private val pads = ArrayList<SamplerPadView>()
    private val cells = ArrayList<GridStepCell>()
    private val patternButtons = ArrayList<PadButton>()
    private lateinit var bankButton: PadButton
    private lateinit var playButton: PadButton
    private lateinit var recButton: PadButton
    private lateinit var reverseButton: PadButton
    private lateinit var muteButton: PadButton
    private lateinit var meter: LevelMeter
    private lateinit var waveform: WaveformView
    private lateinit var bpmText: TextView
    private lateinit var sampleTitle: TextView
    private lateinit var stepsTitle: TextView
    private var ruler: StepRuler? = null

    private var lastStep = -2
    private var blinkPhase = false
    private var frameCount = 0
    private val seenHits = IntArray(SamplerEngine.PADS) { engine.hits.get(it) }
    private val litUntil = LongArray(SamplerEngine.PADS)

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

        val a = row()
        a.addView(samplePanel(waveHeight = 140), weighted(9f, endMargin = 8))
        a.addView(padPanel(intArrayOf(6)), weighted(9f))
        content.addView(a)

        val b = row()
        b.addView(padsPanel(), weighted(7f, endMargin = 8))
        b.addView(masterPanel(intArrayOf(8)), weighted(11f))
        content.addView(b)

        content.addView(stepsPanel(landscape = true))

        val c = row()
        c.addView(panel("PATTERN").apply { addView(patternGrid(intArrayOf(9), dpi(44))) }, weighted(8f, endMargin = 8))
        c.addView(panel("UTILITY").apply { addView(utilityGrid(intArrayOf(7), dpi(44))) }, weighted(9f))
        content.addView(c)
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
        content.addView(samplePanel(waveHeight = 150))
        content.addView(padsPanel())
        content.addView(padPanel(null))
        content.addView(masterPanel(null))
        content.addView(stepsPanel(landscape = false))
        content.addView(panel("PATTERN").apply { addView(patternGrid(intArrayOf(3, 9))) })
        content.addView(panel("UTILITY").apply { addView(utilityGrid(intArrayOf(4, 7))) })
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
            text = context.getString(R.string.sampler_title)
            setTextColor(Palette.panelText)
            textSize = if (landscape) 20f else 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        titles.addView(TextView(context).apply {
            text = context.getString(R.string.sampler_subtitle)
            setTextColor(Palette.red)
            textSize = 10f
            letterSpacing = 0.15f
        })
        top.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val recRow = if (landscape) top else LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(6), 0, dpi(6), dpi(8))
        }
        recButton = PadButton(context, "REC", hasLed = true).apply {
            contentDescription = "Record from the microphone into the selected pad"
            setOnClickListener { toggleRecording() }
        }
        recRow.addView(recButton, if (landscape) LinearLayout.LayoutParams(dpi(120), dpi(48)) else LinearLayout.LayoutParams(0, dpi(48), 1f))
        meter = LevelMeter(context)
        recRow.addView(meter, LinearLayout.LayoutParams(if (landscape) dpi(110) else 0, dpi(20), if (landscape) 0f else 1f).apply {
            marginStart = dpi(8)
        })

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
        top.addView(bpmText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { marginStart = dpi(12) })
        playButton = PadButton(context, "RUN", hasLed = true).apply { setOnClickListener { host.toggleTransport() } }
        top.addView(playButton, LinearLayout.LayoutParams(dpi(if (landscape) 140 else 96), dpi(48)).apply { marginStart = dpi(10) })
        if (landscape) return top
        return column().apply {
            addView(top)
            addView(recRow)
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

    private fun pad(label: String, action: (View) -> Unit) =
        PadButton(context, label).apply { setOnClickListener { action(it) } }

    private fun samplePanel(waveHeight: Int) = panel("SAMPLE").apply {
        sampleTitle = getChildAt(0) as TextView
        waveform = WaveformView(context).apply {
            onTrimChanged = { s, e ->
                engine.set(selectedPad, PadParam.START, s)
                engine.set(selectedPad, PadParam.END, e)
                padKnobs[PadParam.START]?.setValue(s)
                padKnobs[PadParam.END]?.setValue(e)
            }
        }
        addView(waveform, LinearLayout.LayoutParams(MATCH_PARENT, dpi(waveHeight)).apply { bottomMargin = dpi(4) })
        val tools = FlowGrid(context, dpi(40), cellHeight = dpi(40), allowedColumns = intArrayOf(5))
        tools.addView(pad("LOAD") { loadFile() }.also { it.contentDescription = "Load an audio file into the selected pad" })
        tools.addView(pad("NORMAL") { normalize() }.also { it.contentDescription = "Normalize the selected pad" })
        reverseButton = PadButton(context, "REVERSE").apply {
            setOnClickListener {
                engine.set(selectedPad, PadParam.REVERSE, if (engine.get(selectedPad, PadParam.REVERSE) >= 0.5f) 0f else 1f)
                refreshPad()
            }
        }
        tools.addView(reverseButton)
        muteButton = PadButton(context, "MUTE").apply {
            setOnClickListener {
                engine.setMuted(selectedPad, !engine.isMuted(selectedPad))
                refreshPad()
            }
        }
        tools.addView(muteButton)
        tools.addView(pad("ERASE") { assign(selectedPad, null, "") }.also { it.contentDescription = "Erase the selected pad" })
        addView(tools)
    }

    private fun knobGrid(columns: IntArray?) =
        if (columns != null) FlowGrid(context, dpi(40), allowedColumns = columns) else FlowGrid(context, dpi(66))

    private fun pct(v: Float) = "${(v * 100).roundToInt()}"

    private fun padKnob(p: PadParam, label: String, after: (() -> Unit)? = null, format: (Float) -> String) =
        KnobView(context, label, p.default, format, { v ->
            engine.set(selectedPad, p, v)
            after?.invoke()
        }).also { padKnobs[p] = it }

    private fun padPanel(columns: IntArray?) = panel("PAD FX").apply {
        val g = knobGrid(columns)
        g.addView(padKnob(PadParam.LEVEL, "LEVEL", format = ::pct))
        g.addView(padKnob(PadParam.TUNE, "TUNE") { v ->
            val st = ((v - 0.5f) * 48f).roundToInt()
            (if (st > 0) "+" else "") + st
        })
        val retrim = { waveform.setTrim(cur(PadParam.START), cur(PadParam.END)) }
        g.addView(padKnob(PadParam.START, "START", retrim, ::pct))
        g.addView(padKnob(PadParam.END, "END", retrim, ::pct))
        g.addView(padKnob(PadParam.ATTACK, "ATTACK") { v -> "${(v * v * 1000).roundToInt()}ms" })
        g.addView(padKnob(PadParam.DECAY, "DECAY") { v ->
            if (v >= 0.99f) "FULL" else "${(20 * Math.pow(10.0, v * 2.0)).roundToInt()}ms"
        })
        g.addView(padKnob(PadParam.FILTER, "FILTER") { v ->
            when {
                v < 0.48f -> "LP ${(80 * Math.pow(250.0, v / 0.48)).roundToInt().let(::hz)}"
                v > 0.52f -> "HP ${(20 * Math.pow(400.0, (v - 0.52) / 0.48)).roundToInt().let(::hz)}"
                else -> "OFF"
            }
        })
        g.addView(padKnob(PadParam.RESO, "RESO", format = ::pct))
        g.addView(padKnob(PadParam.CRUSH, "CRUSH") { v -> if (v < 0.01f) "OFF" else "${(16 - 13 * v).roundToInt()}bit" })
        g.addView(padKnob(PadParam.DRIVE, "DRIVE", format = ::pct))
        g.addView(padKnob(PadParam.PAN, "PAN") { v ->
            val p = ((v - 0.5f) * 200).roundToInt()
            when {
                p < 0 -> "L${-p}"
                p > 0 -> "R$p"
                else -> "C"
            }
        })
        g.addView(padKnob(PadParam.SEND, "FX SEND", format = ::pct))
        addView(g)
    }

    private fun hz(v: Int) = if (v >= 1000) "%.1fk".format(v / 1000f) else "$v"

    private fun cur(p: PadParam) = engine.get(selectedPad, p)

    private fun padsPanel() = panel("PADS").apply {
        val g = FlowGrid(context, dpi(60), cellHeight = dpi(48), allowedColumns = intArrayOf(4))
        for (p in 0 until SamplerEngine.PADS) {
            val v = SamplerPadView(context, p).apply {
                setOnClickListener {
                    selectPad(p)
                    engine.trigger(p, false)
                }
            }
            pads.add(v)
            g.addView(v)
        }
        addView(g)
    }

    private fun masterKnob(p: SamplerParam, label: String, format: (Float) -> String, onChange: (() -> Unit)? = null) =
        KnobView(context, label, p.default, format, { v ->
            engine.set(p, v)
            onChange?.invoke()
        }).also {
            it.setValue(engine.get(p))
            masterKnobs[p] = it
        }

    private fun masterPanel(columns: IntArray?) = panel("MASTER · TEMPO").apply {
        val g = knobGrid(columns)
        g.addView(masterKnob(SamplerParam.ACCENT, "ACCENT", ::pct))
        g.addView(masterKnob(SamplerParam.DELAY, "DELAY", ::pct))
        g.addView(masterKnob(SamplerParam.DELAY_FEEDBACK, "FEEDBACK", ::pct))
        g.addView(masterKnob(SamplerParam.REVERB, "REVERB", ::pct))
        g.addView(masterKnob(SamplerParam.VOLUME, "VOLUME", ::pct))
        g.addView(masterKnob(SamplerParam.TEMPO, "TEMPO", { v -> "${SamplerParam.normToBpm(v).roundToInt()}" }) { refreshBpm() })
        g.addView(masterKnob(SamplerParam.SWING, "SWING", { v -> "${50 + (v * 20).roundToInt()}%" }))
        lengthKnob = KnobView(context, "LENGTH", 1f, { v -> "${lengthFromNorm(v)}" }, { v ->
            bank.setLength(pattern, lengthFromNorm(v))
            refreshSteps()
        }, detents = StepPatternBank.STEPS)
        g.addView(lengthKnob)
        addView(g)
    }

    private fun stepsPanel(landscape: Boolean) = panel("STEPS").apply {
        stepsTitle = getChildAt(0) as TextView
        if (landscape) {
            ruler = StepRuler(context).also { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, dpi(16))) }
        }
        val g = FlowGrid(
            context, dpi(30), cellHeight = dpi(if (landscape) 48 else 44),
            allowedColumns = if (landscape) intArrayOf(16) else intArrayOf(8, 16)
        )
        for (s in 0 until StepPatternBank.STEPS) {
            val cell = GridStepCell(context, s).apply {
                setOnClickListener {
                    bank.toggle(pattern, selectedPad, s)
                    if (!host.transport.playing && bank.isOn(pattern, selectedPad, s)) engine.trigger(selectedPad, bank.isAccent(pattern, selectedPad, s))
                    refreshSteps()
                }
                setOnLongClickListener {
                    bank.toggleAccent(pattern, selectedPad, s)
                    if (!host.transport.playing && bank.isOn(pattern, selectedPad, s)) engine.trigger(selectedPad, bank.isAccent(pattern, selectedPad, s))
                    refreshSteps()
                    true
                }
            }
            cells.add(cell)
            g.addView(cell)
        }
        addView(g)
    }

    private fun patternGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(context, dpi(30), cellHeight = cellHeight, allowedColumns = columns)
        bankButton = PadButton(context, "BANK A", dark = false).apply {
            setOnClickListener { selectPattern((pattern + 8) % StepPatternBank.PATTERNS) }
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

    private fun utilityGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(context, dpi(30), cellHeight = cellHeight, allowedColumns = columns)
        grid.addView(pad("RANDOM") { modifyPattern { randomize() } })
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
        grid.addView(pad("EXPORT") { startExport() }.also { it.contentDescription = "Export sampler as WAV" })
        return grid
    }

    // ─── Sample editing ──────────────────────────────────────

    /** Puts audio on a pad (null erases it), resets its trim and stores it. */
    private fun assign(pad: Int, data: FloatArray?, name: String) {
        engine.setSample(pad, data)
        engine.padNames[pad] = name
        engine.set(pad, PadParam.START, 0f)
        engine.set(pad, PadParam.END, 1f)
        SamplerStore.saveSample(context, pad, data, engine.sampleRate)
        refreshPads()
        if (pad == selectedPad) refreshPad()
    }

    @SuppressLint("SetTextI18n")
    private fun toggleRecording() {
        if (recorder.isRecording) {
            finishRecording()
            return
        }
        host.requestPermission(Manifest.permission.RECORD_AUDIO) { granted ->
            when {
                !granted -> host.toast("Microphone permission is needed to record")
                !recorder.start() -> host.toast("Microphone unavailable")
                else -> {
                    recButton.active = true
                    sampleTitle.text = "SAMPLE · PAD ${selectedPad + 1} · RECORDING"
                }
            }
        }
    }

    /** Stops the microphone and puts the take on the selected pad, trimmed and normalized. */
    private fun finishRecording() {
        val raw = recorder.stop()
        recButton.active = false
        recButton.setLabel("REC")
        meter.level = 0f
        val rate = engine.sampleRate
        val trimmed = SampleTools.trimSilence(raw, rate)
        if (trimmed.isEmpty()) {
            host.toast("Nothing recorded (only silence)")
            refreshPad()
            return
        }
        recordings++
        assign(selectedPad, SampleTools.fadeEdges(SampleTools.normalize(trimmed), rate), "MIC ${recordings}")
    }

    private fun loadFile() {
        val pad = selectedPad
        host.requestOpenAudio { uri ->
            host.toast("Loading…")
            worker.execute {
                val result = try {
                    AudioDecoder.decode(context, uri, engine.sampleRate, Recorder.MAX_SECONDS)
                } catch (e: Exception) {
                    null
                }
                val name = displayName(uri)
                mainHandler.post {
                    if (result == null || result.isEmpty()) {
                        host.toast("Could not read that file")
                    } else {
                        assign(pad, SampleTools.fadeEdges(result, engine.sampleRate), name)
                    }
                }
            }
        }
    }

    private fun displayName(uri: Uri): String = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.')?.uppercase()?.take(16) else null
        } ?: "FILE"
    } catch (e: Exception) {
        "FILE"
    }

    private fun normalize() {
        val d = engine.sample(selectedPad) ?: return host.toast("Pad is empty")
        val start = cur(PadParam.START)
        val end = cur(PadParam.END)
        assign(selectedPad, SampleTools.normalize(d), engine.padNames[selectedPad])
        engine.set(selectedPad, PadParam.START, start)
        engine.set(selectedPad, PadParam.END, end)
        refreshPad()
    }

    // ─── Actions ─────────────────────────────────────────────

    private fun selectPad(p: Int) {
        selectedPad = p
        refreshPads()
        refreshPad()
        refreshSteps()
    }

    private fun selectPattern(p: Int) {
        engine.selectedPattern = p
        refreshAll()
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

    /** Sparse random hits on the pads that hold audio. */
    private fun randomize() {
        val rnd = Random.Default
        for (p in 0 until SamplerEngine.PADS) {
            var r = 0
            if (engine.sample(p) != null) {
                for (s in 0 until StepPatternBank.STEPS) {
                    if (rnd.nextFloat() < (if (s % 4 == 0) 0.35f else 0.12f)) {
                        r = r or (1 shl s)
                        if (rnd.nextFloat() < 0.3f) r = r or (1 shl (s + 16))
                    }
                }
            }
            bank.setRow(pattern, p, r)
        }
    }

    /** Renders four loops of the current pattern with the current sound to a 48 kHz WAV. */
    private fun startExport() {
        val params = SamplerParam.entries.associateWith { engine.get(it) }
        val padParams = Array(SamplerEngine.PADS) { p -> PadParam.entries.associateWith { engine.get(p, it) } }
        val data = Array(SamplerEngine.PADS) { engine.sample(it) }
        val mutes = BooleanArray(SamplerEngine.PADS) { engine.isMuted(it) }
        val rows = bank.snapshot(pattern)
        val rate = engine.sampleRate
        host.requestExport("vtlo-sampler-pattern-${patternLabel(pattern)}.wav") { out ->
            val e = SamplerEngine(EXPORT_RATE)
            params.forEach { (p, v) -> e.set(p, v) }
            for (p in 0 until SamplerEngine.PADS) {
                padParams[p].forEach { (k, v) -> e.set(p, k, v) }
                e.setSample(p, data[p]?.let { Resampler.resample(it, rate, EXPORT_RATE) })
                e.setMuted(p, mutes[p])
            }
            e.bank.restore(0, rows)
            e.selectedPattern = 0
            val stepSeconds = 60.0 / SamplerParam.normToBpm(e.get(SamplerParam.TEMPO)) / 4.0
            val playFrames = (EXPORT_LOOPS * rows[0] * stepSeconds * EXPORT_RATE).toInt()
            WavWriter.render(out, e, EXPORT_RATE, playFrames, EXPORT_RATE * 3)
        }
    }

    // ─── Refresh ─────────────────────────────────────────────

    override fun refreshAll() {
        SamplerParam.entries.forEach { p -> masterKnobs[p]?.setValue(engine.get(p)) }
        refreshBpm()
        refreshPatterns()
        refreshPads()
        refreshPad()
        onTransportChanged(host.transport.playing)
    }

    @SuppressLint("SetTextI18n")
    private fun refreshBpm() {
        bpmText.text = "%3d".format(SamplerParam.normToBpm(engine.get(SamplerParam.TEMPO)).roundToInt())
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

    private fun refreshPads() {
        for (p in pads.indices) pads[p].apply {
            name = engine.padNames[p]
            empty = engine.sample(p) == null
            hasSteps = bank.row(pattern, p) != 0
            selectedPad = p == this@SamplerScreen.selectedPad
            refresh()
        }
    }

    /** The selected pad's waveform, knobs and toggles. */
    @SuppressLint("SetTextI18n")
    private fun refreshPad() {
        val p = selectedPad
        val d = engine.sample(p)
        val name = engine.padNames[p]
        sampleTitle.text = "SAMPLE · PAD ${p + 1}" + if (name.isNotEmpty()) " · $name" else ""
        stepsTitle.text = "STEPS · PAD ${p + 1}"
        waveform.setSample(d, engine.sampleRate, cur(PadParam.START), cur(PadParam.END))
        waveform.label = if (d == null) "" else "%.2fs".format(d.size / engine.sampleRate.toFloat())
        PadParam.entries.forEach { k -> padKnobs[k]?.setValue(cur(k)) }
        reverseButton.active = cur(PadParam.REVERSE) >= 0.5f
        muteButton.active = engine.isMuted(p)
        refreshSteps()
    }

    private fun refreshSteps() {
        val len = bank.length(pattern)
        val showPlayhead = host.transport.playing && engine.playingPattern == pattern
        val cur = engine.currentStep
        for (s in cells.indices) cells[s].apply {
            on = bank.isOn(pattern, selectedPad, s)
            accent = bank.isAccent(pattern, selectedPad, s)
            inLength = s < len
            playing = showPlayhead && s == cur
            trackName = "Pad ${selectedPad + 1}"
            refresh()
        }
        ruler?.length = len
        for (p in pads.indices) {
            val has = bank.row(pattern, p) != 0
            if (pads[p].hasSteps != has) pads[p].apply { hasSteps = has; invalidate() }
        }
    }

    override fun onTransportChanged(playing: Boolean) {
        playButton.active = playing
        playButton.setLabel(if (playing) "STOP" else "RUN")
        refreshSteps()
    }

    override fun onFrame(playing: Boolean) {
        if (recorder.isRecording) {
            if (recorder.isFull) {
                finishRecording()
            } else {
                recButton.setLabel("STOP %.1fs".format(recorder.frames / engine.sampleRate.toFloat()))
                meter.level = recorder.level
                waveform.showRecording(recorder.overview, recorder.overviewCount, recorder.overview.size)
            }
        }
        val step = if (playing && engine.playingPattern == pattern) engine.currentStep else -1
        if (step != lastStep) {
            if (lastStep in cells.indices) cells[lastStep].apply { this.playing = false; invalidate() }
            if (step in cells.indices) cells[step].apply { this.playing = true; invalidate() }
            ruler?.current = step
            lastStep = step
        }
        if (!recorder.isRecording) waveform.playhead = engine.playhead(selectedPad)
        val now = SystemClock.uptimeMillis()
        for (p in pads.indices) {
            val h = engine.hits.get(p)
            if (h != seenHits[p]) {
                seenHits[p] = h
                litUntil[p] = now + HIT_FLASH_MS
            }
            pads[p].lit = now < litUntil[p]
        }
        if (++frameCount % 15 == 0) {
            blinkPhase = !blinkPhase
            val queued = playing && engine.playingPattern != pattern
            patternButtons.forEachIndexed { i, b -> b.active = (pattern % 8) == i && (!queued || blinkPhase) }
        }
    }

    override fun onHostStop() {
        // The microphone is only used while the screen is visible.
        if (recorder.isRecording) finishRecording()
    }

    private fun lengthFromNorm(v: Float) = 1 + (v * (StepPatternBank.STEPS - 1)).roundToInt()
    private fun normFromLength(len: Int) = (len - 1f) / (StepPatternBank.STEPS - 1)

    private companion object {
        /** File decoding, shared by every screen instance (screens are rebuilt on rotation). */
        val worker = Executors.newSingleThreadExecutor()
        const val DESIGN_WIDTH_DP = 900
        const val HIT_FLASH_MS = 90L
        const val EXPORT_RATE = 48000
        const val EXPORT_LOOPS = 4
    }
}
