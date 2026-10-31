package com.vtlo.acid303

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.acid303.dsp.PatternBank
import com.vtlo.acid303.dsp.Presets
import com.vtlo.acid303.dsp.WavExporter
import com.vtlo.core.ui.FitScaleLayout
import com.vtlo.core.ui.FlowGrid
import com.vtlo.core.ui.KeyboardView
import com.vtlo.core.ui.KnobView
import com.vtlo.core.ui.PadButton
import com.vtlo.core.ui.Palette
import com.vtlo.core.ui.StepCell
import com.vtlo.core.ui.applySystemInsets
import com.vtlo.core.ui.dpi
import com.vtlo.core.ui.enableEdgeToEdge
import com.vtlo.core.ui.hideSystemBars
import com.vtlo.core.ui.isLandscape
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private val app get() = application as AcidApp
    private val engine: AcidEngine get() = app.engine
    private val bank: PatternBank get() = engine.bank
    private val pattern get() = engine.selectedPattern

    private var selectedStep = 0
    private var clipboard: IntArray? = null
    private var undo: IntArray? = null

    private val knobs = HashMap<Param, KnobView>()
    private val cells = ArrayList<StepCell>()
    private val patternButtons = ArrayList<PadButton>()
    private lateinit var bankButton: PadButton
    private lateinit var playButton: PadButton
    private lateinit var waveButton: PadButton
    private lateinit var gateButton: PadButton
    private lateinit var accentButton: PadButton
    private lateinit var slideButton: PadButton
    private lateinit var keyboard: KeyboardView
    private lateinit var bpmText: TextView
    private lateinit var lengthKnob: KnobView

    private val mainHandler = Handler(Looper.getMainLooper())
    private val exportExecutor = Executors.newSingleThreadExecutor()

    private var lastStep = -2
    private var lastPlaying = false
    private var blinkPhase = false
    private var frameCount = 0
    private var animating = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            updatePlayhead()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        volumeControlStream = AudioManager.STREAM_MUSIC
        selectedStep = savedInstanceState?.getInt(STATE_STEP, 0) ?: 0

        setContentView(buildUi())
        enableEdgeToEdge()
        if (isLandscape()) hideSystemBars()
        refreshAll()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_STEP, selectedStep)
    }

    override fun onStart() {
        super.onStart()
        app.transport.uiVisible = true
        app.output.start(engine)
        animating = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onStop() {
        animating = false
        app.save()
        // Playback continues in the foreground service; an idle audio device is released.
        if (!isChangingConfigurations) app.transport.uiVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        exportExecutor.shutdown()
        super.onDestroy()
    }

    // ─── UI construction ─────────────────────────────────────

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.chassis) }
        applySystemInsets(root)
        val content = if (isLandscape()) landscapeUi() else portraitUi()
        root.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        return root
    }

    /**
     * Every control on one screen: laid out at a fixed design width and scaled
     * uniformly to fit the window, so nothing needs scrolling.
     */
    private fun landscapeUi(): View {
        val fit = FitScaleLayout(this, dpi(LANDSCAPE_DESIGN_WIDTH_DP))
        val content = column().apply {
            val pad = dpi(8)
            setPadding(pad, pad / 2, pad, pad)
        }
        fit.addView(content)

        content.addView(header(compact = true).apply {
            createPlayButton()
            (this as LinearLayout).addView(playButton, LinearLayout.LayoutParams(dpi(140), dpi(48)).apply {
                marginStart = dpi(10)
            })
        })

        val top = row()
        val voice = panel("VOICE").apply {
            addView(FlowGrid(context, dpi(40), allowedColumns = intArrayOf(7)).also(::addVoiceControls))
        }
        val fx = panel("EFFECTS · MASTER · TEMPO").apply {
            addView(FlowGrid(context, dpi(40), allowedColumns = intArrayOf(8)).also { g ->
                addFxControls(g)
                addTimingControls(g)
            })
        }
        top.addView(voice, weighted(7f, endMargin = 8))
        top.addView(fx, weighted(8f))
        content.addView(top)

        content.addView(panel("SEQUENCER").apply {
            addView(stepGrid(intArrayOf(16), cellHeight = dpi(64)))
        })

        val bottom = row()
        bottom.addView(panel("KEYBOARD").apply {
            addView(createKeyboard(), LinearLayout.LayoutParams(MATCH_PARENT, dpi(120)))
        }, weighted(3.4f, endMargin = 8))
        bottom.addView(panel("STEP").apply {
            addView(editGrid(intArrayOf(3), cellHeight = dpi(40)))
        }, weighted(2.6f, endMargin = 8))
        bottom.addView(panel("PATTERN").apply {
            addView(patternGrid(intArrayOf(3), cellHeight = dpi(40)))
        }, weighted(1.9f, endMargin = 8))
        bottom.addView(panel("UTILITY").apply {
            addView(utilityGrid(intArrayOf(3), cellHeight = dpi(40)))
        }, weighted(2.4f))
        content.addView(bottom)
        return fit
    }

    /** Scrolling layout, used when the window is taller than wide (large screens ignore the landscape request). */
    private fun portraitUi(): View {
        val wide = resources.configuration.screenWidthDp >= 840
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val content = LinearLayout(this).apply {
            orientation = if (wide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            val pad = dpi(10)
            setPadding(pad, pad, pad, pad)
        }
        scroll.addView(content, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))

        val left = column()
        val right = if (wide) column() else left
        left.addView(header(compact = false))
        left.addView(panel("VOICE").apply { addView(FlowGrid(context, dpi(70)).also(::addVoiceControls)) })
        left.addView(panel("EFFECTS · MASTER").apply { addView(FlowGrid(context, dpi(70)).also(::addFxControls)) })
        right.addView(sequencerPanel())

        if (wide) {
            content.addView(left, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f).apply { marginEnd = dpi(10) })
            content.addView(right, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1.25f))
        } else {
            content.addView(left, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        return scroll
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun row() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dpi(8) }
    }

    private fun weighted(weight: Float, endMargin: Int = 0) =
        LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply { marginEnd = dpi(endMargin) }

    private fun header(compact: Boolean): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dpi(6), dpi(4), dpi(6), dpi(if (compact) 6 else 10))
        }
        val titles = column()
        titles.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            setTextColor(Palette.panelText)
            textSize = if (compact) 20f else 24f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.12f
        })
        titles.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            setTextColor(Palette.red)
            textSize = 10f
            letterSpacing = 0.15f
        })
        row.addView(titles, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        bpmText = TextView(this).apply {
            setTextColor(Palette.ledOn)
            textSize = if (compact) 22f else 26f
            typeface = Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(dpi(12), dpi(2), dpi(12), dpi(2))
            background = GradientDrawable().apply {
                setColor(0xFF0E0C0B.toInt()); cornerRadius = dpi(6).toFloat()
                setStroke(dpi(1), Palette.knobRim)
            }
            contentDescription = "Tempo"
        }
        row.addView(bpmText)
        return row
    }

    private fun panel(title: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dpi(8), dpi(6), dpi(8), dpi(8))
        background = GradientDrawable().apply {
            setColor(Palette.panel)
            cornerRadius = dpi(10).toFloat()
            setStroke(dpi(1), Palette.panelEdge)
        }
        addView(TextView(this@MainActivity).apply {
            text = title
            setTextColor(Palette.red)
            textSize = 11f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            letterSpacing = 0.2f
            setPadding(dpi(4), 0, 0, dpi(4))
            isFocusable = false
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        })
        layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { bottomMargin = dpi(10) }
    }

    private fun knob(
        p: Param, label: String, format: (Float) -> String, detents: Int = 0,
        onChange: ((Float) -> Unit)? = null,
    ): KnobView = KnobView(this, label, p.default, format, { v ->
        engine.set(p, v)
        onChange?.invoke(v)
    }, detents).also {
        it.setValue(engine.get(p))
        knobs[p] = it
    }

    private fun pct(v: Float) = "${(v * 100).roundToInt()}"

    private fun addVoiceControls(grid: ViewGroup) {
        waveButton = PadButton(this, "SAW", hasLed = true, dark = false).apply {
            setOnClickListener {
                val sq = engine.get(Param.WAVEFORM) < 0.5f
                engine.set(Param.WAVEFORM, if (sq) 1f else 0f)
                refreshWave()
            }
        }
        grid.addView(FrameLayout(this).apply {
            addView(waveButton, FrameLayout.LayoutParams(MATCH_PARENT, dpi(56), Gravity.CENTER))
        })
        grid.addView(knob(Param.TUNE, "TUNING", { v ->
            val st = (v - 0.5f) * 24f
            (if (st >= 0) "+" else "") + "%.1f".format(st)
        }))
        grid.addView(knob(Param.CUTOFF, "CUT OFF", ::pct))
        grid.addView(knob(Param.RESONANCE, "RESO", ::pct))
        grid.addView(knob(Param.ENV_MOD, "ENV MOD", ::pct))
        grid.addView(knob(Param.DECAY, "DECAY", { v -> "${(200 * Math.pow(10.0, v.toDouble())).roundToInt()}ms" }))
        grid.addView(knob(Param.ACCENT, "ACCENT", ::pct))
    }

    private fun addFxControls(grid: ViewGroup) {
        grid.addView(knob(Param.DRIVE, "DRIVE", ::pct))
        grid.addView(knob(Param.DELAY, "DELAY", ::pct))
        grid.addView(knob(Param.DELAY_FEEDBACK, "FEEDBACK", ::pct))
        grid.addView(knob(Param.REVERB, "REVERB", ::pct))
        grid.addView(knob(Param.VOLUME, "VOLUME", ::pct))
    }

    private fun addTimingControls(grid: ViewGroup) {
        grid.addView(knob(Param.TEMPO, "TEMPO", { v -> "${Param.normToBpm(v).roundToInt()}" }) { refreshBpm() })
        grid.addView(knob(Param.SWING, "SWING", { v -> "${50 + (v * 20).roundToInt()}%" }))
        lengthKnob = KnobView(this, "LENGTH", 1f, { v -> "${lengthFromNorm(v)}" }, { v ->
            bank.setLength(pattern, lengthFromNorm(v))
            refreshSteps()
        }, detents = PatternBank.STEPS)
        grid.addView(lengthKnob)
    }

    private fun createPlayButton() {
        playButton = PadButton(this, "RUN", hasLed = true).apply {
            setOnClickListener { if (app.transport.playing) stopTransport() else startTransport() }
        }
    }

    private fun patternGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(this, dpi(40), cellHeight = cellHeight, allowedColumns = columns)
        bankButton = PadButton(this, "BANK A", dark = false).apply {
            setOnClickListener { selectPattern((pattern + 8) % PatternBank.PATTERNS) }
        }
        grid.addView(bankButton)
        for (i in 0 until 8) {
            grid.addView(PadButton(this, "${i + 1}", hasLed = true, dark = false).apply {
                setOnClickListener { selectPattern((pattern / 8) * 8 + i) }
                patternButtons.add(this)
            })
        }
        return grid
    }

    private fun stepGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(this, dpi(40), cellHeight = cellHeight, allowedColumns = columns)
        for (i in 0 until PatternBank.STEPS) {
            val cell = StepCell(this, i).apply {
                setOnClickListener { selectStep(i) }
                setOnLongClickListener {
                    editStep { PatternBank.toggleGate(it) }
                    true
                }
            }
            cells.add(cell)
            grid.addView(cell)
        }
        return grid
    }

    private fun editGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(this, dpi(56), cellHeight = cellHeight, allowedColumns = columns)
        grid.addView(pad("◀ STEP") { selectStep((selectedStep + 15) % 16) }.also { it.contentDescription = "Previous step" })
        grid.addView(pad("STEP ▶") { selectStep((selectedStep + 1) % 16) }.also { it.contentDescription = "Next step" })
        gateButton = PadButton(this, "GATE", hasLed = true).apply {
            setOnClickListener { editStep { PatternBank.toggleGate(it) } }
        }
        grid.addView(gateButton)
        grid.addView(pad("NOTE −") { transpose(-1) }.also { it.contentDescription = "Note down" })
        grid.addView(pad("NOTE +") { transpose(1) }.also { it.contentDescription = "Note up" })
        accentButton = PadButton(this, "ACCENT", hasLed = true).apply {
            setOnClickListener { editStep { PatternBank.toggleAccent(it) } }
        }
        grid.addView(accentButton)
        grid.addView(pad("OCT −") { transpose(-12) }.also { it.contentDescription = "Octave down" })
        grid.addView(pad("OCT +") { transpose(12) }.also { it.contentDescription = "Octave up" })
        slideButton = PadButton(this, "SLIDE", hasLed = true).apply {
            setOnClickListener { editStep { PatternBank.toggleSlide(it) } }
        }
        grid.addView(slideButton)
        return grid
    }

    private fun createKeyboard(): KeyboardView {
        keyboard = KeyboardView(this).apply {
            onKeyDown = { k -> onKeyboard(k) }
            onKeyUp = { if (!engine.isPlaying) engine.previewNoteOff() }
        }
        return keyboard
    }

    private fun utilityGrid(columns: IntArray, cellHeight: Int = 0): View {
        val grid = FlowGrid(this, dpi(64), cellHeight = cellHeight, allowedColumns = columns)
        grid.addView(pad("PRESET") { v -> showPresets(v) })
        grid.addView(pad("RANDOM") { modifyPattern { Presets.randomize(bank, pattern) } })
        grid.addView(pad("CLEAR") { modifyPattern { bank.clear(pattern) } })
        grid.addView(pad("UNDO") { restoreUndo() })
        grid.addView(pad("COPY") {
            clipboard = snapshot()
            toast("Pattern ${patternLabel(pattern)} copied")
        })
        grid.addView(pad("PASTE") {
            val c = clipboard ?: return@pad toast("Nothing copied yet")
            modifyPattern { restore(c) }
        })
        grid.addView(pad("PANIC") {
            app.transport.panic()
            refreshTransport(false)
        }.also { it.contentDescription = "Stop all sound" })
        grid.addView(pad("EXPORT") { startExport() }.also { it.contentDescription = "Export WAV" })
        return grid
    }

    private fun sequencerPanel(): View {
        val panel = panel("SEQUENCER")
        val transport = FlowGrid(this, dpi(70))
        createPlayButton()
        transport.addView(FrameLayout(this).apply {
            addView(playButton, FrameLayout.LayoutParams(MATCH_PARENT, dpi(64), Gravity.CENTER))
        })
        addTimingControls(transport)
        panel.addView(transport)
        panel.addView(spacer(6))
        panel.addView(patternGrid(intArrayOf(3, 9)))
        panel.addView(spacer(6))
        panel.addView(stepGrid(intArrayOf(4, 8, 16)))
        panel.addView(spacer(6))
        panel.addView(editGrid(intArrayOf(3, 9)))
        panel.addView(spacer(8))
        panel.addView(createKeyboard(), LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        panel.addView(spacer(8))
        panel.addView(utilityGrid(intArrayOf(4, 8)))
        return panel
    }

    private fun spacer(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dpi(h)) }

    private fun pad(label: String, action: (View) -> Unit) =
        PadButton(this, label).apply { setOnClickListener { action(it) } }

    // ─── Actions ─────────────────────────────────────────────

    private fun startTransport() {
        if (!app.transport.start()) {
            toast("Audio is in use by another app")
            return
        }
        requestNotificationPermissionOnce()
        refreshTransport(true)
    }

    private fun stopTransport() {
        app.transport.stop()
        refreshTransport(false)
    }

    /** The playback notification needs this on Android 13+; playback works without it. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = app.prefs()
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    private fun selectPattern(p: Int) {
        engine.selectedPattern = p
        refreshAll()
    }

    private fun selectStep(i: Int) {
        selectedStep = i
        val s = bank.step(pattern, i)
        if (!engine.isPlaying && PatternBank.gate(s)) {
            engine.previewNoteOn(PatternBank.note(s), PatternBank.accent(s))
            mainHandler.removeCallbacks(previewOff)
            mainHandler.postDelayed(previewOff, 180)
        }
        refreshSteps()
    }

    private val previewOff = Runnable { if (!engine.isPlaying) engine.previewNoteOff() }

    private inline fun editStep(f: (Int) -> Int) {
        bank.setStep(pattern, selectedStep, f(bank.step(pattern, selectedStep)))
        refreshSteps()
    }

    private fun transpose(semis: Int) {
        val s = bank.step(pattern, selectedStep)
        val n = PatternBank.note(s) + semis
        if (n !in PatternBank.MIN_NOTE..PatternBank.MAX_NOTE) return
        editStep { PatternBank.withNote(it, n) }
        val ns = bank.step(pattern, selectedStep)
        if (!engine.isPlaying && PatternBank.gate(ns)) selectStep(selectedStep)
    }

    private fun keyboardBase(): Int {
        val n = PatternBank.note(bank.step(pattern, selectedStep))
        return ((n / 12) * 12).coerceIn(PatternBank.MIN_NOTE, PatternBank.MAX_NOTE - 12)
    }

    private fun onKeyboard(key: Int) {
        val note = keyboardBase() + key
        var s = PatternBank.withNote(bank.step(pattern, selectedStep), note)
        if (!PatternBank.gate(s)) s = PatternBank.toggleGate(s)
        bank.setStep(pattern, selectedStep, s)
        if (!engine.isPlaying) engine.previewNoteOn(note, PatternBank.accent(s))
        refreshSteps()
    }

    private fun snapshot(): IntArray =
        IntArray(PatternBank.STEPS + 1) { i -> if (i == 0) bank.length(pattern) else bank.step(pattern, i - 1) }

    private fun restore(data: IntArray) {
        bank.setLength(pattern, data[0])
        for (i in 0 until PatternBank.STEPS) bank.setStep(pattern, i, data[i + 1])
    }

    private inline fun modifyPattern(f: () -> Unit) {
        undo = snapshot()
        f()
        refreshAll()
    }

    private fun restoreUndo() {
        val u = undo ?: return toast("Nothing to undo")
        undo = snapshot()
        restore(u)
        refreshAll()
    }

    private fun showPresets(anchor: View) {
        PopupMenu(this, anchor).apply {
            Presets.all.forEachIndexed { i, p -> menu.add(0, i, i, "${p.name}  ·  ${p.bpm} BPM") }
            setOnMenuItemClickListener { item ->
                val p = Presets.all[item.itemId]
                modifyPattern {
                    Presets.load(bank, pattern, p)
                    engine.set(Param.TEMPO, Param.bpmToNorm(p.bpm.toFloat()))
                }
                true
            }
            show()
        }
    }

    // ─── WAV export (Storage Access Framework, no permissions) ─

    private fun startExport() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, "vtlo-303-pattern-${patternLabel(pattern)}.wav")
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT)
        } catch (e: Exception) {
            toast("No file picker available")
        }
    }

    @Deprecated("Platform API; no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val params = Param.entries.associateWith { engine.get(it) }
        val steps = snapshot()
        val len = steps[0]
        toast("Rendering…")
        exportExecutor.execute {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use { out ->
                    WavExporter.export(out, loops = 4, patternLength = len) { e ->
                        params.forEach { (p, v) -> e.set(p, v) }
                        e.bank.setLength(0, len)
                        for (i in 0 until PatternBank.STEPS) e.bank.setStep(0, i, steps[i + 1])
                        e.selectedPattern = 0
                    }
                } != null
            } catch (e: Exception) {
                false
            }
            mainHandler.post { toast(if (ok) "WAV exported" else "Export failed") }
        }
    }

    // ─── Refresh ─────────────────────────────────────────────

    private fun refreshAll() {
        Param.entries.forEach { p -> knobs[p]?.setValue(engine.get(p)) }
        refreshWave()
        refreshBpm()
        refreshPatterns()
        refreshSteps()
        refreshTransport()
    }

    private fun refreshWave() {
        val sq = engine.get(Param.WAVEFORM) >= 0.5f
        waveButton.setLabel(if (sq) "SQUARE" else "SAW")
        waveButton.active = sq
        waveButton.contentDescription = "Waveform " + if (sq) "square" else "saw"
    }

    @SuppressLint("SetTextI18n")
    private fun refreshBpm() {
        bpmText.text = "%3d".format(Param.normToBpm(engine.get(Param.TEMPO)).roundToInt())
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

    private fun refreshSteps() {
        val len = bank.length(pattern)
        val showPlayhead = engine.isPlaying && engine.playingPattern == pattern
        val cur = engine.currentStep
        for (i in cells.indices) {
            val s = bank.step(pattern, i)
            cells[i].apply {
                note = PatternBank.note(s)
                gate = PatternBank.gate(s)
                accent = PatternBank.accent(s)
                slide = PatternBank.slide(s)
                selectedStep = i == this@MainActivity.selectedStep
                inLength = i < len
                playing = showPlayhead && i == cur
                refresh()
            }
        }
        val s = bank.step(pattern, selectedStep)
        gateButton.active = PatternBank.gate(s)
        accentButton.active = PatternBank.accent(s)
        slideButton.active = PatternBank.slide(s)
        val base = keyboardBase()
        keyboard.octaveLabel = (base / 12 - 1).toString()
        val n = PatternBank.note(s) - base
        keyboard.highlighted = if (PatternBank.gate(s) && n in 0..12) n else -1
    }

    private fun refreshTransport(playing: Boolean = app.transport.playing) {
        playButton.active = playing
        playButton.setLabel(if (playing) "STOP" else "RUN")
        lastPlaying = playing
        refreshSteps()
    }

    private fun updatePlayhead() {
        val playing = app.transport.playing
        if (playing != lastPlaying) refreshTransport(playing)
        val step = if (playing && engine.playingPattern == pattern) engine.currentStep else -1
        if (step != lastStep) {
            if (lastStep in cells.indices) cells[lastStep].apply { this.playing = false; invalidate() }
            if (step in cells.indices) cells[step].apply { this.playing = true; invalidate() }
            lastStep = step
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

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun lengthFromNorm(v: Float) = 1 + (v * (PatternBank.STEPS - 1)).roundToInt()
    private fun normFromLength(len: Int) = (len - 1f) / (PatternBank.STEPS - 1)

    private companion object {
        const val STATE_STEP = "step"
        const val LANDSCAPE_DESIGN_WIDTH_DP = 900
        const val REQUEST_EXPORT = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
