package com.vtlo.sampler.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class SamplerEngineTest {
    private val fs = 48000

    /** 0.25 s decaying sine burst at [freq]. */
    private fun tone(freq: Double, seconds: Double = 0.25) = FloatArray((seconds * fs).toInt()) {
        (0.8 * sin(2 * PI * freq * it / fs) * Math.exp(-it / (0.1 * fs))).toFloat()
    }

    private fun engine(vararg pads: FloatArray) = SamplerEngine(fs).apply {
        set(SamplerParam.REVERB, 0f)
        set(SamplerParam.DELAY, 0f)
        pads.forEachIndexed { i, d -> setSample(i, d) }
    }

    private fun render(e: SamplerEngine, seconds: Double, block: Int = 256): FloatArray {
        val frames = (seconds * fs).toInt()
        val out = FloatArray(frames * 2)
        val buf = FloatArray(block * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            e.render(buf, n)
            System.arraycopy(buf, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    private fun left(out: FloatArray) = FloatArray(out.size / 2) { out[it * 2] }
    private fun rms(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var s = 0.0
        for (i in from until to) s += x[i] * x[i]
        return sqrt(s / maxOf(1, to - from))
    }
    /** Index of the last sample louder than -60 dB. */
    private fun lastAudible(x: FloatArray) = x.indexOfLast { abs(it) > 1e-3f }

    @Test
    fun padPlaysItsSample() {
        val e = engine(tone(440.0))
        e.trigger(0, false)
        val out = left(render(e, 0.5))
        assertTrue(rms(out, 0, fs / 10) > 0.05)
        assertEquals(1, e.hits.get(0))
    }

    @Test
    fun emptyPadIsSilentAndSafe() {
        val e = engine()
        e.trigger(3, true)
        assertTrue(render(e, 0.2).all { it == 0f })
    }

    @Test
    fun tuneUpAnOctaveHalvesTheLength() {
        fun length(tune: Float): Int {
            val e = engine(FloatArray(fs / 2) { (0.5 * sin(2 * PI * 1000.0 * it / fs)).toFloat() }) // steady 0.5 s tone
            e.set(0, PadParam.TUNE, tune)
            e.trigger(0, false)
            return lastAudible(left(render(e, 1.0)))
        }
        val normal = length(0.5f)
        val up = length(0.5f + 12f / 48f)
        assertEquals(normal / 2.0, up.toDouble(), fs * 0.01)
    }

    @Test
    fun trimAndReverse() {
        // Tone swelling over one second: the trimmed, reversed hit must start loud and fall.
        val ramp = FloatArray(fs) { (it / fs.toDouble() * 0.8 * sin(2 * PI * 1000.0 * it / fs)).toFloat() }
        val e = engine(ramp)
        e.set(0, PadParam.START, 0.5f)
        e.set(0, PadParam.END, 0.75f)
        e.set(0, PadParam.REVERSE, 1f)
        e.trigger(0, false)
        val out = left(render(e, 0.5))
        val len = lastAudible(out)
        assertEquals(fs * 0.25, len.toDouble(), fs * 0.01)
        assertTrue(rms(out, 0, 2000) > rms(out, len - 2000, len))
    }

    @Test
    fun lowPassRemovesHighs() {
        fun level(filter: Float): Double {
            val e = engine(tone(6000.0))
            e.set(0, PadParam.FILTER, filter)
            e.trigger(0, false)
            return rms(left(render(e, 0.2)))
        }
        val open = level(0.5f)
        val closed = level(0.2f) // ~750 Hz low-pass
        println("LP at 6 kHz: %.1f dB".format(20 * log10(closed / open)))
        assertTrue(closed < open * 0.05)
    }

    @Test
    fun highPassRemovesLows() {
        fun level(filter: Float): Double {
            val e = engine(tone(80.0, 0.5))
            e.set(0, PadParam.FILTER, filter)
            e.trigger(0, false)
            return rms(left(render(e, 0.4)))
        }
        assertTrue(level(0.85f) < level(0.5f) * 0.1) // ~1.3 kHz high-pass
    }

    @Test
    fun crusherDistorts() {
        fun out(crush: Float): FloatArray {
            val e = engine(tone(220.0))
            e.set(0, PadParam.CRUSH, crush)
            e.trigger(0, false)
            return left(render(e, 0.1))
        }
        val clean = out(0f)
        val crushed = out(1f)
        val err = FloatArray(clean.size) { crushed[it] - clean[it] }
        val ratio = rms(err) / rms(clean)
        println("crusher distortion: %.0f%%".format(ratio * 100))
        assertTrue(ratio > 0.2)
        val mild = FloatArray(clean.size) { out(0.05f)[it] - clean[it] }
        assertTrue(rms(mild) / rms(clean) < ratio)
    }

    @Test
    fun decayShortensAndAttackFadesIn() {
        val flat = FloatArray(fs) { (0.5 * sin(2 * PI * 1000.0 * it / fs)).toFloat() }
        val e = engine(flat)
        e.set(0, PadParam.DECAY, 0f)
        e.trigger(0, false)
        val short = left(render(e, 1.0))
        assertTrue(rms(short, fs / 2, fs) < 1e-3)

        val e2 = engine(flat)
        e2.set(0, PadParam.ATTACK, 0.5f) // 250 ms
        e2.trigger(0, false)
        val slow = left(render(e2, 0.5))
        assertTrue(rms(slow, 0, fs / 50) < rms(slow, fs / 5, fs / 5 + fs / 50) * 0.2)
    }

    @Test
    fun sequencerHitsAndMutes() {
        val e = engine(tone(300.0), tone(500.0))
        e.bank.toggle(0, 0, 0)
        e.bank.toggle(0, 0, 4)
        e.bank.toggle(0, 1, 2)
        e.setMuted(1, true)
        e.play()
        render(e, 60.0 / 130.0) // one beat at 130 BPM = steps 0..3, plus the start of step 4
        render(e, 0.05)
        assertEquals(2, e.hits.get(0))
        assertEquals(0, e.hits.get(1))
    }

    @Test
    fun sequencerIsSampleAccurate() {
        val e = engine(tone(300.0)).apply { set(SamplerParam.TEMPO, SamplerParam.bpmToNorm(120f)) }
        e.play()
        val buf = FloatArray(2)
        var last = -1
        val changes = ArrayList<Int>()
        for (n in 0 until fs * 2) {
            e.render(buf, 1)
            if (e.currentStep != last) {
                changes.add(n); last = e.currentStep
            }
        }
        for (i in 1 until changes.size) assertEquals(6000, changes[i] - changes[i - 1])
    }

    @Test
    fun everythingAtMaximumStaysBounded() {
        val e = SamplerEngine(fs)
        for (p in 0 until SamplerEngine.PADS) {
            e.setSample(p, tone(100.0 * (p + 1)))
            PadParam.entries.forEach { e.set(p, it, 1f) }
            for (s in 0 until 16) e.bank.toggleAccent(0, p, s)
        }
        SamplerParam.entries.forEach { e.set(it, 1f) }
        e.play()
        val out = render(e, 3.0)
        assertTrue(out.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun sampleTools() {
        val d = FloatArray(fs) { if (it in 12000 until 24000) 0.25f else 0f }
        val trimmed = SampleTools.trimSilence(d, fs)
        assertEquals(12000 + fs / 200 + fs / 20, trimmed.size)
        val n = SampleTools.normalize(trimmed)
        assertEquals(0.97f, n.max(), 1e-6f)
        val f = SampleTools.fadeEdges(FloatArray(1000) { 1f }, fs)
        assertEquals(0f, f[0], 0f)
        assertEquals(0f, f[999], 0.25f)
        assertTrue(SampleTools.trimSilence(FloatArray(100), fs).isEmpty())
    }

    @Test
    fun rendersMuchFasterThanRealTime() {
        val e = SamplerEngine(fs)
        for (p in 0 until SamplerEngine.PADS) {
            e.setSample(p, tone(200.0 + p * 50, 1.0))
            e.set(p, PadParam.FILTER, 0.3f); e.set(p, PadParam.CRUSH, 0.3f); e.set(p, PadParam.TUNE, 0.6f)
            e.set(p, PadParam.SEND, 0.5f)
            for (s in 0 until 16 step 2) e.bank.toggle(0, p, s)
        }
        e.play()
        render(e, 3.0)
        val t0 = System.nanoTime()
        render(e, 20.0)
        val factor = 20.0 / ((System.nanoTime() - t0) / 1e9)
        println("realtime factor: %.1fx".format(factor))
        assertTrue(factor > 5)
    }

    @Test
    fun padAudioRoundTripsThroughFloatWav() {
        val d = tone(330.0)
        val bytes = java.io.ByteArrayOutputStream()
        com.vtlo.core.audio.WavWriter.writeMonoFloat(bytes, d, fs)
        val back = com.vtlo.core.dsp.WavReader.read(bytes.toByteArray().inputStream())
        assertEquals(fs, back.sampleRate)
        assertTrue(d.contentEquals(back.data))
    }
}
