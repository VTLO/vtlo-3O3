package com.vtlo.acid303.dsp

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.exp

/** All user-facing parameters, stored normalized to 0..1. */
enum class Param(val key: String, val default: Float) {
    TUNE("tune", 0.5f),
    CUTOFF("cutoff", 0.25f),
    RESONANCE("resonance", 0.8f),
    ENV_MOD("envmod", 0.55f),
    DECAY("decay", 0.35f),
    ACCENT("accent", 0.7f),
    WAVEFORM("waveform", 0f),
    TEMPO("tempo", 0.5f), // 130 BPM
    SWING("swing", 0f),
    DRIVE("drive", 0.2f),
    DELAY("delay", 0.15f),
    DELAY_FEEDBACK("delayfb", 0.45f),
    REVERB("reverb", 0.1f),
    VOLUME("volume", 0.7f);

    companion object {
        const val BPM_MIN = 60f
        const val BPM_MAX = 200f
        fun bpmToNorm(bpm: Float) = (bpm - BPM_MIN) / (BPM_MAX - BPM_MIN)
        fun normToBpm(v: Float) = BPM_MIN + v * (BPM_MAX - BPM_MIN)
    }
}

/**
 * The complete instrument: TB-303 voice, step sequencer clocked by the audio
 * sample counter (sample-accurate, no jitter), effects and master limiter.
 *
 * Thread model: [render] is called from a single audio thread. Every other
 * public method is safe to call from the UI thread; they communicate through
 * atomics and a single-producer/single-consumer command queue.
 */
class AcidEngine(val sampleRate: Int) {
    val bank = PatternBank()

    private val params = AtomicIntegerArray(Param.entries.size).also { a ->
        Param.entries.forEach { a.set(it.ordinal, it.default.toRawBits()) }
    }

    fun get(p: Param): Float = Float.fromBits(params.get(p.ordinal))
    fun set(p: Param, value: Float) = params.set(p.ordinal, value.coerceIn(0f, 1f).toRawBits())

    /** Pattern the sequencer switches to at the end of the current loop. */
    @Volatile var selectedPattern = 0

    @Volatile var isPlaying = false
        private set
    @Volatile var currentStep = -1
        private set
    @Volatile var playingPattern = 0
        private set

    private val fs = sampleRate.toDouble()
    private val voice = Voice303(fs)
    private val drive = Overdrive(fs)
    private val delay = PingPongDelay(fs)
    private val reverb = FdnReverb(fs)
    private val dcL = OnePoleHighpass(8.0, fs)
    private val dcR = OnePoleHighpass(8.0, fs)

    private val queue = IntArray(QUEUE_SIZE)
    private val qWrite = AtomicInteger(0)
    private val qRead = AtomicInteger(0)

    // Smoothed copies of the continuous parameters (audio thread only).
    private val smooth = DoubleArray(Param.entries.size)
    private val smoothCoeff = exp(-1.0 / (0.008 * fs))

    // Sequencer state (audio thread only).
    private var stepIndex = -1
    private var samplesToNextStep = 0.0
    private var gateOffIn = -1.0
    private var prevGate = false
    private var prevSlide = false

    init {
        Param.entries.forEach { smooth[it.ordinal] = it.default.toDouble() }
    }

    fun play() = post(CMD_PLAY, 0)
    fun stop() = post(CMD_STOP, 0)
    fun panic() = post(CMD_PANIC, 0)

    fun previewNoteOn(note: Int, accent: Boolean) = post(CMD_NOTE_ON, note or (if (accent) 0x100 else 0))
    fun previewNoteOff() = post(CMD_NOTE_OFF, 0)

    private fun post(cmd: Int, arg: Int) {
        val w = qWrite.get()
        if (w - qRead.get() >= QUEUE_SIZE) return // queue full: drop, UI will retry on next gesture
        queue[w and (QUEUE_SIZE - 1)] = (cmd shl 24) or (arg and 0xffffff)
        qWrite.set(w + 1)
    }

    private fun drainCommands() {
        var r = qRead.get()
        val w = qWrite.get()
        while (r != w) {
            val c = queue[r and (QUEUE_SIZE - 1)]
            r++
            val arg = c and 0xffffff
            when (c ushr 24) {
                CMD_PLAY -> if (!isPlaying) {
                    stepIndex = -1
                    samplesToNextStep = 0.0
                    gateOffIn = -1.0
                    prevGate = false
                    prevSlide = false
                    playingPattern = selectedPattern
                    isPlaying = true
                }
                CMD_STOP -> {
                    isPlaying = false
                    currentStep = -1
                    voice.noteOff()
                }
                CMD_PANIC -> {
                    isPlaying = false
                    currentStep = -1
                    voice.reset()
                    delay.clear()
                    reverb.clear()
                }
                CMD_NOTE_ON -> if (!isPlaying) voice.noteOn(arg and 0x7f, arg and 0x100 != 0, false)
                CMD_NOTE_OFF -> if (!isPlaying) voice.noteOff()
            }
        }
        qRead.set(r)
    }

    /** Renders [frames] interleaved stereo frames into [out]. */
    fun render(out: FloatArray, frames: Int) {
        drainCommands()
        val bpm = Param.normToBpm(get(Param.TEMPO)).toDouble()
        val swing = get(Param.SWING) * 0.4
        val stepSamples = fs * 60.0 / bpm / 4.0
        val delaySeconds = 3.0 * 60.0 / bpm / 4.0 // dotted eighth
        val waveform = get(Param.WAVEFORM).toDouble()
        voice.waveform = if (waveform >= 0.5) 1.0 else 0.0

        var o = 0
        for (n in 0 until frames) {
            // Parameter smoothing (one-pole, ~8 ms).
            for (i in smooth.indices) {
                val t = Float.fromBits(params.get(i)).toDouble()
                smooth[i] = t + smoothCoeff * (smooth[i] - t)
            }
            voice.cutoff = smooth[Param.CUTOFF.ordinal]
            voice.resonance = smooth[Param.RESONANCE.ordinal]
            voice.envMod = smooth[Param.ENV_MOD.ordinal]
            voice.decay = smooth[Param.DECAY.ordinal]
            voice.accent = smooth[Param.ACCENT.ordinal]
            voice.tuneSemitones = (smooth[Param.TUNE.ordinal] - 0.5) * 24.0

            if (isPlaying) {
                if (samplesToNextStep <= 0.0) {
                    advanceStep(stepSamples)
                    val swung = if (stepIndex % 2 == 0) 1.0 + swing else 1.0 - swing
                    samplesToNextStep += stepSamples * swung
                    if (gateOffIn >= 0.0) gateOffIn = stepSamples * swung * GATE_FRACTION
                }
                if (gateOffIn >= 0.0) {
                    if (gateOffIn <= 0.0) {
                        voice.noteOff()
                        gateOffIn = -1.0
                    } else {
                        gateOffIn -= 1.0
                    }
                }
                samplesToNextStep -= 1.0
            }

            var x = voice.render()
            x = drive.process(x, smooth[Param.DRIVE.ordinal])

            val delayMix = smooth[Param.DELAY.ordinal]
            delay.process(x, delaySeconds, smooth[Param.DELAY_FEEDBACK.ordinal] * 0.9)
            var l = x + delayMix * delay.outL
            var r = x + delayMix * delay.outR

            val revMix = smooth[Param.REVERB.ordinal]
            reverb.process((l + r) * 0.5, 0.82)
            l += revMix * reverb.outL
            r += revMix * reverb.outR

            val vol = smooth[Param.VOLUME.ordinal]
            val gain = 1.6 * vol * vol
            l = SoftLimiter.process(dcL.process(l * gain))
            r = SoftLimiter.process(dcR.process(r * gain))
            out[o++] = l.toFloat()
            out[o++] = r.toFloat()
        }
    }

    /** Moves to the next step and fires it. */
    private fun advanceStep(stepSamples: Double) {
        stepIndex++
        if (stepIndex >= bank.length(playingPattern)) stepIndex = 0
        if (stepIndex == 0) playingPattern = selectedPattern
        val s = bank.step(playingPattern, stepIndex)
        val gate = PatternBank.gate(s)
        val slide = PatternBank.slide(s)
        if (gate) {
            voice.noteOn(PatternBank.note(s), PatternBank.accent(s), prevGate && prevSlide)
            // Slide holds the gate into the next step; otherwise release mid-step.
            gateOffIn = if (slide) -1.0 else stepSamples * GATE_FRACTION
        } else {
            voice.noteOff()
            gateOffIn = -1.0
        }
        prevGate = gate
        prevSlide = slide
        currentStep = stepIndex
    }

    companion object {
        private const val QUEUE_SIZE = 256
        private const val GATE_FRACTION = 0.5
        private const val CMD_PLAY = 1
        private const val CMD_STOP = 2
        private const val CMD_PANIC = 3
        private const val CMD_NOTE_ON = 4
        private const val CMD_NOTE_OFF = 5
    }
}
