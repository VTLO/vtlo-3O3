package com.vtlo.drums.dsp

import com.vtlo.core.audio.SequencerEngine
import com.vtlo.core.dsp.FdnReverb
import com.vtlo.core.dsp.OnePoleHighpass
import com.vtlo.core.dsp.Overdrive
import com.vtlo.core.dsp.PingPongDelay
import com.vtlo.core.dsp.SoftLimiter
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin

/** Global parameters, normalized to 0..1. */
enum class DrumParam(val key: String, val default: Float) {
    TEMPO("tempo", 0.5f), // 130 BPM
    SWING("swing", 0f),
    ACCENT("accent", 0.6f),
    DRIVE("drive", 0.1f),
    DELAY("delay", 0f),
    DELAY_FEEDBACK("delayfb", 0.4f),
    REVERB("reverb", 0.12f),
    VOLUME("volume", 0.7f);

    companion object {
        const val BPM_MIN = 60f
        const val BPM_MAX = 200f
        fun bpmToNorm(bpm: Float) = (bpm - BPM_MIN) / (BPM_MAX - BPM_MIN)
        fun normToBpm(v: Float) = BPM_MIN + v * (BPM_MAX - BPM_MIN)
    }
}

/** Per-track parameters, normalized to 0..1. */
enum class TrackParam(val key: String, val default: Float) {
    LEVEL("level", 0.8f),
    TUNE("tune", 0.5f), // ±12 semitones
    DECAY("decay", 1f), // 1 = the sample's natural length
    PAN("pan", 0.5f),
}

/**
 * Sample-based drum machine: eight tracks, each playing one sound from any of
 * the three kits, a step sequencer clocked by the audio sample counter, and the
 * shared effects and limiter.
 *
 * Thread model as for the bass line: [render] runs on one audio thread; every
 * other public method is safe from the UI thread.
 */
class DrumEngine(val sampleRate: Int, private val samples: SampleBank) : SequencerEngine {
    init {
        require(samples.sampleRate == sampleRate) { "Samples must be converted to the engine rate" }
    }

    val bank = DrumPatternBank()

    private val params = AtomicIntegerArray(DrumParam.entries.size).also { a ->
        DrumParam.entries.forEach { a.set(it.ordinal, it.default.toRawBits()) }
    }
    private val trackParams = AtomicIntegerArray(Kit.TRACKS * TrackParam.entries.size).also { a ->
        for (t in 0 until Kit.TRACKS) TrackParam.entries.forEach { a.set(t * TrackParam.entries.size + it.ordinal, it.default.toRawBits()) }
    }
    private val trackKits = AtomicIntegerArray(Kit.TRACKS).also { a -> for (t in 0 until Kit.TRACKS) a.set(t, Kit.TR909.ordinal) }
    private val mutes = AtomicInteger(0)

    /** Incremented on every hit, so the UI can flash the track's LED. */
    val hits = AtomicIntegerArray(Kit.TRACKS)

    fun get(p: DrumParam): Float = Float.fromBits(params.get(p.ordinal))
    fun set(p: DrumParam, v: Float) = params.set(p.ordinal, v.coerceIn(0f, 1f).toRawBits())

    fun get(track: Int, p: TrackParam): Float = Float.fromBits(trackParams.get(track * TrackParam.entries.size + p.ordinal))
    fun set(track: Int, p: TrackParam, v: Float) =
        trackParams.set(track * TrackParam.entries.size + p.ordinal, v.coerceIn(0f, 1f).toRawBits())

    fun kit(track: Int): Kit = Kit.entries[trackKits.get(track)]
    fun setKit(track: Int, kit: Kit) = trackKits.set(track, kit.ordinal)

    fun isMuted(track: Int) = mutes.get() and (1 shl track) != 0
    fun setMuted(track: Int, muted: Boolean) {
        while (true) {
            val m = mutes.get()
            val n = if (muted) m or (1 shl track) else m and (1 shl track).inv()
            if (mutes.compareAndSet(m, n)) return
        }
    }

    @Volatile var selectedPattern = 0
    @Volatile override var isPlaying = false
        private set
    @Volatile var currentStep = -1
        private set
    @Volatile var playingPattern = 0
        private set

    private val fs = sampleRate.toDouble()
    private val voices = Array(Kit.TRACKS * 2) { Voice() } // current + fading-out voice per track
    private val driveL = Overdrive(fs)
    private val driveR = Overdrive(fs)
    private val delay = PingPongDelay(fs)
    private val reverb = FdnReverb(fs)
    private val dcL = OnePoleHighpass(8.0, fs)
    private val dcR = OnePoleHighpass(8.0, fs)

    private val smooth = DoubleArray(DrumParam.entries.size) { DrumParam.entries[it].default.toDouble() }
    private val smoothCoeff = exp(-1.0 / (0.008 * fs))
    private val stealFade = exp(-1.0 / (0.003 * fs))
    private val chokeFade = exp(-1.0 / (0.008 * fs))
    private val gainL = DoubleArray(Kit.TRACKS)
    private val gainR = DoubleArray(Kit.TRACKS)

    private val queue = IntArray(QUEUE_SIZE)
    private val qWrite = AtomicInteger(0)
    private val qRead = AtomicInteger(0)

    private var stepIndex = -1
    private var samplesToNextStep = 0.0

    override fun play() = post(CMD_PLAY, 0)
    override fun stop() = post(CMD_STOP, 0)
    override fun panic() = post(CMD_PANIC, 0)

    /** Plays one hit now (pads and auditioning), whether or not the sequencer runs. */
    fun trigger(track: Int, accent: Boolean) = post(CMD_TRIGGER, track or (if (accent) 0x100 else 0))

    private fun post(cmd: Int, arg: Int) {
        val w = qWrite.get()
        if (w - qRead.get() >= QUEUE_SIZE) return
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
                    playingPattern = selectedPattern
                    isPlaying = true
                }
                CMD_STOP -> {
                    isPlaying = false
                    currentStep = -1
                }
                CMD_PANIC -> {
                    isPlaying = false
                    currentStep = -1
                    for (v in voices) v.data = null
                    delay.clear()
                    reverb.clear()
                }
                CMD_TRIGGER -> hit(arg and 0xff, arg and 0x100 != 0)
            }
        }
        qRead.set(r)
    }

    override fun render(out: FloatArray, frames: Int) {
        drainCommands()
        val bpm = DrumParam.normToBpm(get(DrumParam.TEMPO)).toDouble()
        val swing = get(DrumParam.SWING) * 0.4
        val stepSamples = fs * 60.0 / bpm / 4.0
        val delaySeconds = 3.0 * 60.0 / bpm / 4.0

        // Level and pan follow the knobs live (equal-power pan), per block.
        for (t in 0 until Kit.TRACKS) {
            val level = get(t, TrackParam.LEVEL).toDouble()
            val g = level * level * 1.6
            val pan = get(t, TrackParam.PAN) * PI / 2
            gainL[t] = g * cos(pan) * SQRT2
            gainR[t] = g * sin(pan) * SQRT2
        }

        var o = 0
        for (n in 0 until frames) {
            for (i in smooth.indices) {
                val target = Float.fromBits(params.get(i)).toDouble()
                smooth[i] = target + smoothCoeff * (smooth[i] - target)
            }

            if (isPlaying) {
                if (samplesToNextStep <= 0.0) {
                    advanceStep()
                    samplesToNextStep += stepSamples * (if (stepIndex % 2 == 0) 1.0 + swing else 1.0 - swing)
                }
                samplesToNextStep -= 1.0
            }

            var l = 0.0
            var r = 0.0
            for (vi in voices.indices) {
                val v = voices[vi]
                val data = v.data ?: continue
                val s = v.next(data)
                val t = vi shr 1
                l += s * gainL[t]
                r += s * gainR[t]
            }

            val drive = smooth[DrumParam.DRIVE.ordinal]
            l = driveL.process(l, drive)
            r = driveR.process(r, drive)

            val mono = (l + r) * 0.5
            delay.process(mono, delaySeconds, smooth[DrumParam.DELAY_FEEDBACK.ordinal] * 0.9)
            val delayMix = smooth[DrumParam.DELAY.ordinal]
            l += delayMix * delay.outL
            r += delayMix * delay.outR

            reverb.process(mono, 0.8)
            val revMix = smooth[DrumParam.REVERB.ordinal]
            l += revMix * reverb.outL
            r += revMix * reverb.outR

            val vol = smooth[DrumParam.VOLUME.ordinal]
            val gain = 1.2 * vol * vol
            out[o++] = SoftLimiter.process(dcL.process(l * gain)).toFloat()
            out[o++] = SoftLimiter.process(dcR.process(r * gain)).toFloat()
        }
    }

    private fun advanceStep() {
        stepIndex++
        if (stepIndex >= bank.length(playingPattern)) stepIndex = 0
        if (stepIndex == 0) playingPattern = selectedPattern
        val m = mutes.get()
        for (t in 0 until Kit.TRACKS) {
            if (m and (1 shl t) != 0) continue
            val row = bank.row(playingPattern, t)
            if (row and (1 shl stepIndex) != 0) hit(t, row and (1 shl (stepIndex + 16)) != 0)
        }
        currentStep = stepIndex
    }

    private fun hit(track: Int, accent: Boolean) {
        if (track !in 0 until Kit.TRACKS) return
        val cur = voices[track * 2]
        val old = voices[track * 2 + 1]
        // The ringing hit fades out over 3 ms instead of clicking off.
        if (cur.data != null) {
            old.copyFrom(cur)
            old.fadeMul = stealFade
        }
        if (track == Kit.CLOSED_HAT) {
            // Closed hat chokes the open hat, as on the hardware.
            for (i in 0..1) voices[Kit.OPEN_HAT * 2 + i].takeIf { it.data != null }?.fadeMul = chokeFade
        }
        val accentAmount = get(DrumParam.ACCENT).toDouble()
        val decay = get(track, TrackParam.DECAY).toDouble()
        cur.data = samples.get(kit(track), track)
        cur.pos = 0.0
        cur.inc = 2.0.pow((get(track, TrackParam.TUNE) - 0.5) * 24.0 / 12.0)
        cur.velocity = VELOCITY * (if (accent) 1.0 + accentAmount * 0.9 else 1.0)
        cur.env = 1.0
        cur.envMul = if (decay >= 0.99) 1.0 else exp(-1.0 / (0.03 * 10.0.pow(decay * 2.0) * fs))
        cur.fade = 1.0
        cur.fadeMul = 1.0
        hits.incrementAndGet(track)
    }

    private class Voice {
        var data: FloatArray? = null
        var pos = 0.0
        var inc = 1.0
        var velocity = 1.0
        var env = 1.0
        var envMul = 1.0
        var fade = 1.0
        var fadeMul = 1.0

        fun copyFrom(o: Voice) {
            data = o.data; pos = o.pos; inc = o.inc; velocity = o.velocity
            env = o.env; envMul = o.envMul; fade = o.fade; fadeMul = o.fadeMul
        }

        /** Next output sample; 4-point Hermite interpolation when retuned. */
        fun next(d: FloatArray): Double {
            val i = pos.toInt()
            if (i >= d.size || fade < 1e-4 || env < 1e-4) {
                data = null
                return 0.0
            }
            val s = if (inc == 1.0) {
                d[i].toDouble()
            } else {
                val f = pos - i
                val xm1 = if (i > 0) d[i - 1].toDouble() else 0.0
                val x0 = d[i].toDouble()
                val x1 = if (i + 1 < d.size) d[i + 1].toDouble() else 0.0
                val x2 = if (i + 2 < d.size) d[i + 2].toDouble() else 0.0
                val c1 = 0.5 * (x1 - xm1)
                val c2 = xm1 - 2.5 * x0 + 2 * x1 - 0.5 * x2
                val c3 = 0.5 * (x2 - xm1) + 1.5 * (x0 - x1)
                ((c3 * f + c2) * f + c1) * f + x0
            }
            pos += inc
            val out = s * velocity * env * fade
            env *= envMul
            fade *= fadeMul
            return out
        }
    }

    private companion object {
        const val QUEUE_SIZE = 256
        const val CMD_PLAY = 1
        const val CMD_STOP = 2
        const val CMD_PANIC = 3
        const val CMD_TRIGGER = 4
        const val VELOCITY = 0.55
        const val SQRT2 = 1.4142135623730951
    }
}
