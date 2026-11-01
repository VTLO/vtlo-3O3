package com.vtlo.sampler.dsp

import com.vtlo.core.audio.SequencerEngine
import com.vtlo.core.dsp.FdnReverb
import com.vtlo.core.dsp.OnePoleHighpass
import com.vtlo.core.dsp.PingPongDelay
import com.vtlo.core.dsp.SoftLimiter
import com.vtlo.core.seq.StepPatternBank
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh

/** Global parameters, normalized to 0..1. */
enum class SamplerParam(val key: String, val default: Float) {
    TEMPO("tempo", 0.5f), // 130 BPM
    SWING("swing", 0f),
    ACCENT("accent", 0.6f),
    DELAY("delay", 0.35f), // delay return level
    DELAY_FEEDBACK("delayfb", 0.45f),
    REVERB("reverb", 0.35f), // reverb return level
    VOLUME("volume", 0.7f);

    companion object {
        const val BPM_MIN = 60f
        const val BPM_MAX = 200f
        fun bpmToNorm(bpm: Float) = (bpm - BPM_MIN) / (BPM_MAX - BPM_MIN)
        fun normToBpm(v: Float) = BPM_MIN + v * (BPM_MAX - BPM_MIN)
    }
}

/** Per-pad parameters, normalized to 0..1. */
enum class PadParam(val key: String, val default: Float) {
    LEVEL("level", 0.8f),
    TUNE("tune", 0.5f), // ±24 semitones
    START("start", 0f), // trim, as a fraction of the sample
    END("end", 1f),
    ATTACK("attack", 0f), // 0 .. 1 s
    DECAY("decay", 1f), // 1 = the sample's natural length
    FILTER("filter", 0.5f), // below centre low-pass, above centre high-pass
    RESO("reso", 0.2f),
    CRUSH("crush", 0f), // bit depth and sample-rate reduction
    DRIVE("drive", 0f),
    PAN("pan", 0.5f),
    SEND("send", 0f), // to the delay and reverb
    REVERSE("reverse", 0f); // 0 or 1
}

/**
 * Eight-pad sampler: any audio (recorded, imported or bundled) on each pad, a
 * per-pad effect chain (pitch, trim, attack/decay, reverse, filter, bit-crusher,
 * drive, pan, effects send), master delay and reverb, and the same
 * sample-accurate step sequencer as the drum machine.
 *
 * Samples must be at [sampleRate]. Thread model as for the other instruments:
 * [render] runs on one audio thread; every other public method is safe from the UI.
 */
class SamplerEngine(val sampleRate: Int) : SequencerEngine {
    val bank = StepPatternBank()

    private val samples = AtomicReferenceArray<FloatArray?>(PADS)

    /** Display name of each pad's sample (UI thread only; not used for audio). */
    val padNames = Array(PADS) { "" }
    private val params = AtomicIntegerArray(SamplerParam.entries.size).also { a ->
        SamplerParam.entries.forEach { a.set(it.ordinal, it.default.toRawBits()) }
    }
    private val padParams = AtomicIntegerArray(PADS * PAD_PARAMS).also { a ->
        for (p in 0 until PADS) PadParam.entries.forEach { a.set(p * PAD_PARAMS + it.ordinal, it.default.toRawBits()) }
    }
    private val mutes = AtomicInteger(0)

    /** Incremented on every hit, so the UI can flash the pad. */
    val hits = AtomicIntegerArray(PADS)
    /** Play position of each pad's newest voice as a fraction of its sample, or -1. */
    private val playheads = AtomicIntegerArray(PADS).also { a -> for (p in 0 until PADS) a.set(p, (-1f).toRawBits()) }

    fun sample(pad: Int): FloatArray? = samples.get(pad)
    fun setSample(pad: Int, data: FloatArray?) = samples.set(pad, data)

    fun get(p: SamplerParam): Float = Float.fromBits(params.get(p.ordinal))
    fun set(p: SamplerParam, v: Float) = params.set(p.ordinal, v.coerceIn(0f, 1f).toRawBits())

    fun get(pad: Int, p: PadParam): Float = Float.fromBits(padParams.get(pad * PAD_PARAMS + p.ordinal))
    fun set(pad: Int, p: PadParam, v: Float) = padParams.set(pad * PAD_PARAMS + p.ordinal, v.coerceIn(0f, 1f).toRawBits())

    fun isMuted(pad: Int) = mutes.get() and (1 shl pad) != 0
    fun setMuted(pad: Int, muted: Boolean) {
        while (true) {
            val m = mutes.get()
            val n = if (muted) m or (1 shl pad) else m and (1 shl pad).inv()
            if (mutes.compareAndSet(m, n)) return
        }
    }

    fun playhead(pad: Int): Float = Float.fromBits(playheads.get(pad))

    @Volatile var selectedPattern = 0
    @Volatile override var isPlaying = false
        private set
    @Volatile var currentStep = -1
        private set
    @Volatile var playingPattern = 0
        private set

    private val fs = sampleRate.toDouble()
    private val voices = Array(PADS * 2) { Voice(it shr 1) } // current + fading-out voice per pad
    private val delay = PingPongDelay(fs)
    private val reverb = FdnReverb(fs)
    private val dcL = OnePoleHighpass(8.0, fs)
    private val dcR = OnePoleHighpass(8.0, fs)

    private val smooth = DoubleArray(SamplerParam.entries.size) { SamplerParam.entries[it].default.toDouble() }
    private val smoothCoeff = exp(-1.0 / (0.008 * fs))
    private val stealFade = exp(-1.0 / (0.003 * fs))

    // Per-pad live values, refreshed every block.
    private val gainL = DoubleArray(PADS)
    private val gainR = DoubleArray(PADS)
    private val send = DoubleArray(PADS)
    private val drive = DoubleArray(PADS)
    private val crushLevels = DoubleArray(PADS)
    private val crushHold = IntArray(PADS)
    private val filterMode = IntArray(PADS)
    private val fA1 = DoubleArray(PADS)
    private val fA2 = DoubleArray(PADS)
    private val fA3 = DoubleArray(PADS)
    private val fK = DoubleArray(PADS)

    private val queue = IntArray(QUEUE_SIZE)
    private val qWrite = AtomicInteger(0)
    private val qRead = AtomicInteger(0)

    private var stepIndex = -1
    private var samplesToNextStep = 0.0

    override fun play() = post(CMD_PLAY, 0)
    override fun stop() = post(CMD_STOP, 0)
    override fun panic() = post(CMD_PANIC, 0)

    /** Plays a pad now (pads and auditioning), whether or not the sequencer runs. */
    fun trigger(pad: Int, accent: Boolean) = post(CMD_TRIGGER, pad or (if (accent) 0x100 else 0))

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

    private fun updatePads() {
        for (p in 0 until PADS) {
            val level = get(p, PadParam.LEVEL).toDouble()
            val g = level * level * 1.6
            val pan = get(p, PadParam.PAN) * PI / 2
            gainL[p] = g * cos(pan) * SQRT2
            gainR[p] = g * sin(pan) * SQRT2
            val s = get(p, PadParam.SEND).toDouble()
            send[p] = s * s
            drive[p] = get(p, PadParam.DRIVE).toDouble()

            val c = get(p, PadParam.CRUSH).toDouble()
            if (c < 0.01) {
                crushLevels[p] = 0.0
                crushHold[p] = 1
            } else {
                crushLevels[p] = 2.0.pow(15.0 - 13.0 * c) // 16 bits down to 3
                crushHold[p] = 1 + (c * c * 31).toInt() // up to 1/32 of the sample rate
            }

            // One knob: low-pass below the centre, high-pass above, a dead zone in the middle.
            val f = get(p, PadParam.FILTER).toDouble()
            val fc = when {
                f < 0.48 -> {
                    filterMode[p] = 1
                    80.0 * (20000.0 / 80.0).pow(f / 0.48)
                }
                f > 0.52 -> {
                    filterMode[p] = 2
                    20.0 * (8000.0 / 20.0).pow((f - 0.52) / 0.48)
                }
                else -> {
                    filterMode[p] = 0
                    1000.0
                }
            }
            val q = 0.5 + get(p, PadParam.RESO).toDouble().pow(2) * 14.0
            val gg = tan(PI * minOf(fc, fs * 0.45) / fs)
            val k = 1.0 / q
            fA1[p] = 1.0 / (1.0 + gg * (gg + k))
            fA2[p] = gg * fA1[p]
            fA3[p] = gg * fA2[p]
            fK[p] = k
        }
    }

    override fun render(out: FloatArray, frames: Int) {
        drainCommands()
        val bpm = SamplerParam.normToBpm(get(SamplerParam.TEMPO)).toDouble()
        val swing = get(SamplerParam.SWING) * 0.4
        val stepSamples = fs * 60.0 / bpm / 4.0
        val delaySeconds = 3.0 * 60.0 / bpm / 4.0
        updatePads()

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
            var bus = 0.0
            for (v in voices) {
                if (v.data == null) continue
                val s = v.next()
                val p = v.pad
                l += s * gainL[p]
                r += s * gainR[p]
                bus += s * send[p]
            }

            delay.process(bus, delaySeconds, smooth[SamplerParam.DELAY_FEEDBACK.ordinal] * 0.9)
            reverb.process(bus, 0.84)
            val dm = smooth[SamplerParam.DELAY.ordinal]
            val rm = smooth[SamplerParam.REVERB.ordinal]
            l += dm * delay.outL + rm * reverb.outL
            r += dm * delay.outR + rm * reverb.outR

            val vol = smooth[SamplerParam.VOLUME.ordinal]
            val gain = 1.2 * vol * vol
            out[o++] = SoftLimiter.process(dcL.process(l * gain)).toFloat()
            out[o++] = SoftLimiter.process(dcR.process(r * gain)).toFloat()
        }

        for (p in 0 until PADS) {
            val v = voices[p * 2]
            val d = v.data
            playheads.set(p, (if (d == null || d.isEmpty()) -1f else (v.pos / d.size).toFloat()).toRawBits())
        }
    }

    private fun advanceStep() {
        stepIndex++
        if (stepIndex >= bank.length(playingPattern)) stepIndex = 0
        if (stepIndex == 0) playingPattern = selectedPattern
        val m = mutes.get()
        for (p in 0 until PADS) {
            if (m and (1 shl p) != 0) continue
            val row = bank.row(playingPattern, p)
            if (row and (1 shl stepIndex) != 0) hit(p, row and (1 shl (stepIndex + 16)) != 0)
        }
        currentStep = stepIndex
    }

    private fun hit(pad: Int, accent: Boolean) {
        if (pad !in 0 until PADS) return
        val data = samples.get(pad) ?: return
        if (data.size < 4) return
        val cur = voices[pad * 2]
        if (cur.data != null) {
            voices[pad * 2 + 1].copyFrom(cur)
            voices[pad * 2 + 1].fadeMul = stealFade
        }
        val a = get(pad, PadParam.START)
        val b = get(pad, PadParam.END)
        val last = (data.size - 1).toDouble()
        val end = maxOf(maxOf(a, b) * last, 2.0)
        val start = minOf(minOf(a, b) * last, end - 2.0)
        val reverse = get(pad, PadParam.REVERSE) >= 0.5f
        val rate = 2.0.pow((get(pad, PadParam.TUNE) - 0.5) * 48.0 / 12.0)
        val attack = get(pad, PadParam.ATTACK).toDouble().pow(2) // seconds
        val decay = get(pad, PadParam.DECAY).toDouble()
        val accentAmount = get(SamplerParam.ACCENT).toDouble()
        cur.start(
            data, start, end, reverse, rate,
            velocity = VELOCITY * (if (accent) 1.0 + accentAmount * 0.9 else 1.0),
            attackInc = if (attack < 0.0005) 1.0 else 1.0 / (attack * fs),
            decayMul = if (decay >= 0.99) 1.0 else exp(-1.0 / (0.02 * 10.0.pow(decay * 2.0) * fs)),
        )
        hits.incrementAndGet(pad)
    }

    private inner class Voice(val pad: Int) {
        var data: FloatArray? = null
        var pos = 0.0
        private var startIdx = 0.0
        private var endIdx = 0.0
        private var inc = 1.0
        private var velocity = 1.0
        private var attack = 1.0
        private var attackInc = 1.0
        private var env = 1.0
        private var envMul = 1.0
        private var fade = 1.0
        var fadeMul = 1.0
        private var ic1 = 0.0
        private var ic2 = 0.0
        private var held = 0.0
        private var holdCount = 0

        fun start(
            d: FloatArray, start: Double, end: Double, reverse: Boolean, rate: Double,
            velocity: Double, attackInc: Double, decayMul: Double,
        ) {
            data = d
            startIdx = start
            endIdx = end
            inc = if (reverse) -rate else rate
            pos = if (reverse) end else start
            this.velocity = velocity
            this.attackInc = attackInc
            attack = if (attackInc >= 1.0) 1.0 else 0.0
            env = 1.0
            envMul = decayMul
            fade = 1.0
            fadeMul = 1.0
            ic1 = 0.0
            ic2 = 0.0
            holdCount = 0
        }

        fun copyFrom(o: Voice) {
            data = o.data; pos = o.pos; startIdx = o.startIdx; endIdx = o.endIdx; inc = o.inc
            velocity = o.velocity; attack = o.attack; attackInc = o.attackInc; env = o.env; envMul = o.envMul
            fade = o.fade; fadeMul = o.fadeMul; ic1 = o.ic1; ic2 = o.ic2; held = o.held; holdCount = o.holdCount
        }

        fun next(): Double {
            val d = data ?: return 0.0
            if (pos < startIdx || pos > endIdx || fade < 1e-4 || env < 1e-4) {
                data = null
                return 0.0
            }
            var s = hermite(d, pos)
            pos += inc

            // Bit-crusher: sample-and-hold plus quantization.
            val levels = crushLevels[pad]
            if (levels > 0.0) {
                if (holdCount <= 0) {
                    held = round(s * levels) / levels
                    holdCount = crushHold[pad]
                }
                holdCount--
                s = held
            }
            val dr = drive[pad]
            if (dr > 0.01) {
                val g = 1.0 + dr * 15.0
                s = tanh(s * g) / tanh(g) * 0.9
            }
            // State-variable filter (topology-preserving transform).
            val mode = filterMode[pad]
            if (mode != 0) {
                val v3 = s - ic2
                val v1 = fA1[pad] * ic1 + fA2[pad] * v3
                val v2 = ic2 + fA2[pad] * ic1 + fA3[pad] * v3
                ic1 = 2 * v1 - ic1
                ic2 = 2 * v2 - ic2
                s = if (mode == 1) v2 else s - fK[pad] * v1 - v2
            }

            if (attack < 1.0) attack = minOf(1.0, attack + attackInc)
            val out = s * velocity * attack * env * fade
            env *= envMul
            fade *= fadeMul
            return out
        }
    }

    companion object {
        const val PADS = StepPatternBank.TRACKS
        private val PAD_PARAMS = PadParam.entries.size
        private const val QUEUE_SIZE = 256
        private const val CMD_PLAY = 1
        private const val CMD_STOP = 2
        private const val CMD_PANIC = 3
        private const val CMD_TRIGGER = 4
        private const val VELOCITY = 0.6
        private const val SQRT2 = 1.4142135623730951

        /** 4-point Hermite interpolation; exact at integer positions. */
        private fun hermite(d: FloatArray, pos: Double): Double {
            val i = pos.toInt()
            val f = pos - i
            val x0 = d[i].toDouble()
            if (f == 0.0) return x0
            val xm1 = if (i > 0) d[i - 1].toDouble() else 0.0
            val x1 = if (i + 1 < d.size) d[i + 1].toDouble() else 0.0
            val x2 = if (i + 2 < d.size) d[i + 2].toDouble() else 0.0
            val c1 = 0.5 * (x1 - xm1)
            val c2 = xm1 - 2.5 * x0 + 2 * x1 - 0.5 * x2
            val c3 = 0.5 * (x2 - xm1) + 1.5 * (x0 - x1)
            return ((c3 * f + c2) * f + c1) * f + x0
        }
    }
}

/** Editing helpers applied to recordings and imports. */
object SampleTools {
    /** Drops leading and trailing audio quieter than [thresholdDb], keeping a short pre-roll. */
    fun trimSilence(d: FloatArray, sampleRate: Int, thresholdDb: Double = -48.0): FloatArray {
        val t = 10.0.pow(thresholdDb / 20.0)
        var first = d.indexOfFirst { abs(it) > t }
        if (first < 0) return FloatArray(0)
        var last = d.indexOfLast { abs(it) > t }
        first = maxOf(0, first - sampleRate / 200) // 5 ms pre-roll
        last = minOf(d.size - 1, last + sampleRate / 20) // 50 ms tail
        return d.copyOfRange(first, last + 1)
    }

    /** Scales so the peak sits at [peak] (no-op on silence). */
    fun normalize(d: FloatArray, peak: Float = 0.97f): FloatArray {
        var m = 0f
        for (v in d) m = maxOf(m, abs(v))
        if (m < 1e-6f) return d.copyOf()
        val g = peak / m
        return FloatArray(d.size) { d[it] * g }
    }

    /** Short fades at both ends so cut points never click. */
    fun fadeEdges(d: FloatArray, sampleRate: Int): FloatArray {
        val out = d.copyOf()
        val fin = minOf(out.size / 2, sampleRate / 1000) // 1 ms
        val fout = minOf(out.size / 2, sampleRate / 200) // 5 ms
        for (i in 0 until fin) out[i] *= i.toFloat() / fin
        for (i in 0 until fout) out[out.size - 1 - i] *= i.toFloat() / fout
        return out
    }
}
