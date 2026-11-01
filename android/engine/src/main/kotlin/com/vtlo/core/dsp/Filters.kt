package com.vtlo.core.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/** One-pole high-pass (matched-z), as used around the TB-303 filter core. */
class OnePoleHighpass(cutoffHz: Double, sampleRate: Double) {
    private val coeff = exp(-2.0 * PI * cutoffHz / sampleRate)
    private var x1 = 0.0
    private var y1 = 0.0

    fun process(x: Double): Double {
        val y = coeff * (y1 + x - x1)
        x1 = x
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; y1 = 0.0
    }
}

/** One-pole low-pass with an explicit time constant or cutoff. */
class OnePoleLowpass(private var coeff: Double = 0.0) {
    private var y = 0.0

    fun setCutoff(hz: Double, sampleRate: Double) {
        coeff = exp(-2.0 * PI * hz / sampleRate)
    }

    fun process(x: Double): Double {
        y = x + coeff * (y - x)
        return y
    }

    fun reset(value: Double = 0.0) {
        y = value
    }
}

/** First-order all-pass (bilinear). */
class OnePoleAllpass(cutoffHz: Double, sampleRate: Double) {
    private val a: Double
    private var x1 = 0.0
    private var y1 = 0.0

    init {
        val t = tan(PI * cutoffHz / sampleRate)
        a = (t - 1.0) / (t + 1.0)
    }

    fun process(x: Double): Double {
        val y = a * x + x1 - a * y1
        x1 = x
        y1 = y
        return y
    }

    fun reset() {
        x1 = 0.0; y1 = 0.0
    }
}

/** RBJ biquad in transposed direct form II. */
class Biquad {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var z1 = 0.0
    private var z2 = 0.0

    fun lowpass(freq: Double, q: Double, sampleRate: Double): Biquad {
        val w0 = 2.0 * PI * freq / sampleRate
        val cw = cos(w0)
        val alpha = sin(w0) / (2.0 * q)
        val a0 = 1.0 + alpha
        b0 = (1.0 - cw) * 0.5 / a0
        b1 = (1.0 - cw) / a0
        b2 = b0
        a1 = -2.0 * cw / a0
        a2 = (1.0 - alpha) / a0
        return this
    }

    fun notch(freq: Double, bandwidthOctaves: Double, sampleRate: Double): Biquad {
        val w0 = 2.0 * PI * freq / sampleRate
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw * sinh(ln(2.0) / 2.0 * bandwidthOctaves * w0 / sw)
        val a0 = 1.0 + alpha
        b0 = 1.0 / a0
        b1 = -2.0 * cw / a0
        b2 = 1.0 / a0
        a1 = -2.0 * cw / a0
        a2 = (1.0 - alpha) / a0
        return this
    }

    fun process(x: Double): Double {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y
    }

    fun reset() {
        z1 = 0.0; z2 = 0.0
    }
}

/**
 * Even-order Butterworth low-pass built from cascaded biquads.
 * Used as the anti-aliasing filter before decimating the oversampled voice.
 */
class ButterworthLowpass(order: Int, cutoffHz: Double, sampleRate: Double) {
    private val stages = Array(order / 2) { k ->
        val q = 1.0 / (2.0 * sin((2.0 * k + 1.0) * PI / (2.0 * order)))
        Biquad().lowpass(cutoffHz, q, sampleRate)
    }

    fun process(x: Double): Double {
        var y = x
        for (s in stages) y = s.process(y)
        return y
    }

    fun reset() {
        for (s in stages) s.reset()
    }
}
