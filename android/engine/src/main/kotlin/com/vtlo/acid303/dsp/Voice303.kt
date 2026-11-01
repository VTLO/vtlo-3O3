package com.vtlo.acid303.dsp

import com.vtlo.core.dsp.Biquad
import com.vtlo.core.dsp.ButterworthLowpass
import com.vtlo.core.dsp.OnePoleAllpass
import com.vtlo.core.dsp.OnePoleHighpass
import com.vtlo.core.dsp.OnePoleLowpass
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow

/**
 * Monophonic TB-303 voice.
 *
 * Signal path follows the measurements published with Open303 (Robin Schmidt):
 * band-limited saw/square -> 44.5 Hz high-pass -> 4-pole diode-ladder model with
 * a 150 Hz high-pass and soft clipper in the feedback loop -> 24.2 Hz high-pass,
 * 14 Hz all-pass, 7.5 Hz notch -> VCA. Oscillator and filter run 4x oversampled
 * and are decimated through a 12th-order Butterworth anti-aliasing filter.
 *
 * On top of that the accent circuit is modelled with a charge/leak "sweep"
 * capacitor so consecutive accents stack up, as on the hardware.
 *
 * All control parameters are normalized 0..1 and may be changed at any time from
 * the audio thread; no allocation happens while rendering.
 */
class Voice303(private val sampleRate: Double) {

    var cutoff = 0.3
    var resonance = 0.75
    var envMod = 0.5
    var decay = 0.4
    var accent = 0.6
    /** 0 = saw, 1 = square, values in between blend. */
    var waveform = 0.0
    var tuneSemitones = 0.0

    private val fsO = sampleRate * OVERSAMPLING
    private val invFsO = 1.0 / fsO

    // --- Oscillator / pitch ---
    private var phase = 0.0
    private var pitch = 36.0
    private var targetPitch = 36.0
    private val slideCoeff = exp(-1.0 / (SLIDE_TIME_S * sampleRate))

    // --- Filter (oversampled) ---
    private var y1 = 0.0
    private var y2 = 0.0
    private var y3 = 0.0
    private var y4 = 0.0
    private var fB0 = 0.0
    private var fK = 0.0
    private var fG = 1.0
    private val feedbackHp = OnePoleHighpass(150.0, fsO)
    private val preHp = OnePoleHighpass(44.486, fsO)
    private val antiAlias = ButterworthLowpass(12, minOf(0.4 * sampleRate, 20000.0), fsO)

    // --- Post filter (base rate) ---
    private val postHp = OnePoleHighpass(24.167, sampleRate)
    private val postAp = OnePoleAllpass(14.008, sampleRate)
    private val postNotch = Biquad().notch(7.5164, 4.7, sampleRate)

    // --- Envelopes ---
    private var mainEnv = 0.0
    private var mainCoeff = 0.0
    private var accentActive = false
    private var accentRc = 0.0
    private val accentRcCoeff = exp(-1.0 / (ACCENT_RC_S * sampleRate))
    private val accentNorm: Double
    private var sweep = 0.0
    private val sweepRate = SWEEP_STEP / (ACCENT_DECAY_S * sampleRate)

    private var gate = false
    private var ampEnv = 0.0
    private val ampDecayCoeff = exp(-1.0 / (AMP_DECAY_S * sampleRate))
    private val ampReleaseCoeff = exp(-1.0 / (AMP_RELEASE_S * sampleRate))
    private val declick1 = OnePoleLowpass().apply { setCutoff(DECLICK_HZ, sampleRate) }
    private val declick2 = OnePoleLowpass().apply { setCutoff(DECLICK_HZ, sampleRate) }

    init {
        // Peak of an RC low-pass driven by a decaying exponential; used so the
        // accent contribution reaches exactly 1.0 at its maximum.
        val td = ACCENT_DECAY_S
        val tr = ACCENT_RC_S
        val tPeak = td * tr / (td - tr) * ln(td / tr)
        val peak = td / (td - tr) * (exp(-tPeak / td) - exp(-tPeak / tr))
        accentNorm = 1.0 / peak
    }

    val isSounding: Boolean get() = gate || ampEnv > 1e-5

    /**
     * Starts a note. With [legato] and an open gate the pitch glides to the new
     * note without retriggering the envelopes (TB-303 slide).
     */
    fun noteOn(note: Int, accented: Boolean, legato: Boolean) {
        targetPitch = note.toDouble()
        accentActive = accented
        if (legato && gate) return
        pitch = targetPitch
        val decayS = if (accented) ACCENT_DECAY_S else DECAY_MIN_S * 10.0.pow(decay)
        mainCoeff = exp(-1.0 / (decayS * sampleRate))
        mainEnv = 1.0
        ampEnv = 1.0
        gate = true
    }

    fun noteOff() {
        gate = false
    }

    fun reset() {
        gate = false
        ampEnv = 0.0; mainEnv = 0.0; accentRc = 0.0; sweep = 0.0
        y1 = 0.0; y2 = 0.0; y3 = 0.0; y4 = 0.0
        feedbackHp.reset(); preHp.reset(); antiAlias.reset()
        postHp.reset(); postAp.reset(); postNotch.reset()
        declick1.reset(); declick2.reset()
    }

    /** Renders one sample at the base sample rate. */
    fun render(): Double {
        // Pitch: RC glide in the CV (semitone) domain.
        pitch = targetPitch + slideCoeff * (pitch - targetPitch)
        val freq = 440.0 * 2.0.pow((pitch + tuneSemitones - 69.0) / 12.0)
        val inc = minOf(freq * invFsO, 0.45)

        // Main envelope generator and accent circuit.
        mainEnv *= mainCoeff
        val accIn = if (accentActive) mainEnv else 0.0
        accentRc = accIn + accentRcCoeff * (accentRc - accIn)
        val acc = accentRc * accentNorm
        val sweepLeak = exp(-1.0 / ((SWEEP_LEAK_MIN_S + SWEEP_LEAK_RANGE_S * resonance) * sampleRate))
        sweep = (sweep + acc * sweepRate) * sweepLeak
        val sweepOut = sweep / (1.0 + sweep)

        // Cutoff: Open303 calibration of knob position, env-mod scaler and offset.
        val c = cutoff
        val e = envMod
        val baseHz = CUTOFF_MIN_HZ * (CUTOFF_MAX_HZ / CUTOFF_MIN_HZ).pow(c)
        val envScaler = (1.0 - c) * (S_LO_F * e + S_LO_C) + c * (S_HI_F * e + S_HI_C)
        val envOffset = O_F * c + O_C
        val octaves = envScaler * (mainEnv - envOffset) +
            accent * (ACCENT_DEPTH_OCT * acc + SWEEP_DEPTH_OCT * sweepOut)
        val fc = (baseHz * 2.0.pow(octaves)).coerceIn(20.0, 22000.0)
        updateFilter(fc)

        // Oversampled oscillator + ladder, decimated.
        val w = waveform
        var out = 0.0
        for (i in 0 until OVERSAMPLING) {
            val saw = 2.0 * phase - 1.0 - polyBlep(phase, inc)
            var osc = saw
            if (w > 0.0) {
                var p2 = phase + 0.5
                if (p2 >= 1.0) p2 -= 1.0
                val sq = (if (phase < 0.5) 1.0 else -1.0) + polyBlep(phase, inc) - polyBlep(p2, inc)
                osc = saw + w * (sq - saw)
            }
            phase += inc
            if (phase >= 1.0) phase -= 1.0

            val x = preHp.process(-osc)
            val fb = feedbackHp.process(fK * shape(y4))
            val y0 = x - fb
            y1 += 2.0 * fB0 * (y0 - y1 + y2)
            y2 += fB0 * (y1 - 2.0 * y2 + y3)
            y3 += fB0 * (y2 - 2.0 * y3 + y4)
            y4 += fB0 * (y3 - 2.0 * y4)
            out = antiAlias.process(2.0 * fG * y4)
        }

        var s = postHp.process(out)
        s = postAp.process(s)
        s = postNotch.process(s)

        // VCA: own envelope plus accent boost, de-clicked.
        ampEnv *= if (gate) ampDecayCoeff else ampReleaseCoeff
        val ampTarget = ampEnv + ACCENT_VCA * accent * acc * (if (gate) 1.0 else ampEnv)
        val amp = declick2.process(declick1.process(ampTarget))
        return s * amp * OUTPUT_GAIN
    }

    private fun updateFilter(fc: Double) {
        val r = (1.0 - exp(-3.0 * resonance)) / (1.0 - exp(-3.0))
        val fx = fc * invFsO * ONE_OVER_SQRT2
        fB0 = (0.00045522346 + 6.1922189 * fx) / (1.0 + 12.358354 * fx + 4.4156345 * fx * fx)
        var k = fx * (fx * (fx * (fx * (fx * (fx + 7198.6997) - 5837.7917) - 476.47308) +
            614.95611) + 213.87126) + 16.998792
        var g = k * (1.0 / 17.0)
        g = (g - 1.0) * r + 1.0
        g *= (1.0 + r)
        k *= r
        fK = k
        fG = g
    }

    companion object {
        const val OVERSAMPLING = 4

        private const val ONE_OVER_SQRT2 = 0.7071067811865476
        private const val SQRT2 = 1.4142135623730951

        // Open303 cutoff / env-mod calibration.
        private const val CUTOFF_MIN_HZ = 313.8152786059267
        private const val CUTOFF_MAX_HZ = 2394.411986817546
        private const val O_F = 0.048292930943553
        private const val O_C = 0.294391201442418
        private const val S_LO_F = 3.773996325111173
        private const val S_LO_C = 0.736965594166206
        private const val S_HI_F = 4.194548788411135
        private const val S_HI_C = 0.864344900642434

        private const val SLIDE_TIME_S = 0.060
        private const val DECAY_MIN_S = 0.200 // knob sweeps 200 ms .. 2 s
        private const val ACCENT_DECAY_S = 0.200
        private const val ACCENT_RC_S = 0.015
        private const val ACCENT_DEPTH_OCT = 1.4
        private const val ACCENT_VCA = 0.9
        private const val SWEEP_DEPTH_OCT = 1.6
        private const val SWEEP_STEP = 0.35
        private const val SWEEP_LEAK_MIN_S = 0.12
        private const val SWEEP_LEAK_RANGE_S = 0.9
        private const val AMP_DECAY_S = 1.23
        private const val AMP_RELEASE_S = 0.004
        private const val DECLICK_HZ = 300.0
        private const val OUTPUT_GAIN = 0.7

        /** Feedback-path saturation of the diode ladder (cubic soft clipper). */
        private fun shape(v: Double): Double {
            val x = v.coerceIn(-SQRT2, SQRT2)
            return x - x * x * x * (1.0 / 6.0)
        }

        private fun polyBlep(t: Double, dt: Double): Double {
            if (t < dt) {
                val u = t / dt
                return u + u - u * u - 1.0
            }
            if (t > 1.0 - dt) {
                val u = (t - 1.0) / dt
                return u * u + u + u + 1.0
            }
            return 0.0
        }
    }
}
