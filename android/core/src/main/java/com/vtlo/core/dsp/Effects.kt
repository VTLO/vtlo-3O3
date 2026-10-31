package com.vtlo.core.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.tanh

/**
 * Asymmetric tube/pedal style overdrive with pre-emphasis and a tone low-pass,
 * so the squelch survives heavy drive instead of turning to mush.
 */
class Overdrive(sampleRate: Double) {
    private val preHp = OnePoleHighpass(60.0, sampleRate)
    private val tone = OnePoleLowpass().apply { setCutoff(6500.0, sampleRate) }
    private val dc = OnePoleHighpass(10.0, sampleRate)

    fun process(x: Double, amount: Double): Double {
        if (amount <= 0.001) return x
        val gain = 1.0 + amount * amount * 30.0
        val bias = 0.18 * amount
        val v = preHp.process(x) * gain
        val shaped = tanh(v + bias) - tanh(bias)
        val y = dc.process(tone.process(shaped)) * (0.9 / (1.0 + amount * 1.6))
        return x + amount.coerceAtMost(1.0) * (y * 2.4 - x)
    }
}

/** Tempo-synced ping-pong delay with damped, band-limited feedback. */
class PingPongDelay(private val sampleRate: Double) {
    private val size = (sampleRate * 2.5).toInt()
    private val bufL = DoubleArray(size)
    private val bufR = DoubleArray(size)
    private var write = 0
    private var delaySamples = sampleRate * 0.3
    private val dampL = OnePoleLowpass().apply { setCutoff(3200.0, sampleRate) }
    private val dampR = OnePoleLowpass().apply { setCutoff(3200.0, sampleRate) }
    private val hpL = OnePoleHighpass(180.0, sampleRate)
    private val hpR = OnePoleHighpass(180.0, sampleRate)
    private val smooth = exp(-1.0 / (0.08 * sampleRate))

    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun process(input: Double, targetSeconds: Double, feedback: Double) {
        val target = (targetSeconds * sampleRate).coerceIn(16.0, size - 4.0)
        delaySamples = target + smooth * (delaySamples - target)
        val l = read(bufL, delaySamples)
        val r = read(bufR, delaySamples)
        // Mono input enters the left line, lines cross-feed for ping-pong.
        bufL[write] = input + hpL.process(dampL.process(r)) * feedback
        bufR[write] = hpR.process(dampR.process(l)) * feedback
        write++
        if (write >= size) write = 0
        outL = l
        outR = r
    }

    private fun read(buf: DoubleArray, delay: Double): Double {
        var pos = write - delay
        if (pos < 0) pos += size
        val i0 = pos.toInt()
        val frac = pos - i0
        val i1 = if (i0 + 1 >= size) 0 else i0 + 1
        return buf[i0] + frac * (buf[i1] - buf[i0])
    }

    fun clear() {
        bufL.fill(0.0); bufR.fill(0.0)
    }
}

/** Compact 4-line feedback delay network reverb with a Householder mixing matrix. */
class FdnReverb(sampleRate: Double) {
    private val lengths = intArrayOf(1499, 1889, 2381, 2999).map { (it * sampleRate / 48000.0).toInt() }
    private val lines = Array(4) { DoubleArray(lengths[it]) }
    private val pos = IntArray(4)
    private val damp = Array(4) { OnePoleLowpass().apply { setCutoff(5200.0, sampleRate) } }
    private val inputHp = OnePoleHighpass(220.0, sampleRate)
    private val tmp = DoubleArray(4)

    var outL = 0.0
        private set
    var outR = 0.0
        private set

    fun process(input: Double, decay: Double) {
        val x = inputHp.process(input) * 0.35
        var sum = 0.0
        for (i in 0 until 4) {
            tmp[i] = damp[i].process(lines[i][pos[i]])
            sum += tmp[i]
        }
        val h = sum * 0.5
        for (i in 0 until 4) {
            lines[i][pos[i]] = x + (tmp[i] - h) * decay
            pos[i]++
            if (pos[i] >= lengths[i]) pos[i] = 0
        }
        outL = tmp[0] + tmp[2]
        outR = tmp[1] + tmp[3]
    }

    fun clear() {
        for (l in lines) l.fill(0.0)
    }
}

/** Gentle look-ahead-free limiter: transparent below threshold, tanh knee above. */
object SoftLimiter {
    private const val T = 0.8

    fun process(x: Double): Double {
        val a = abs(x)
        if (a <= T) return x
        val over = (a - T) / (1.0 - T)
        val y = T + (1.0 - T) * tanh(over)
        return if (x < 0) -y else y
    }
}
