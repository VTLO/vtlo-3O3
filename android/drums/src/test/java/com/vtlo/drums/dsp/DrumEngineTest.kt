package com.vtlo.drums.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class DrumEngineTest {
    companion object {
        private const val FS = 48000
        private val assets = File("src/main/assets")
        private lateinit var samples: SampleBank

        @BeforeClass
        @JvmStatic
        fun loadSamples() {
            val t0 = System.nanoTime()
            samples = SampleBank.load(FS) { File(assets, it).inputStream() }
            println("loaded and resampled all kits in %.0f ms".format((System.nanoTime() - t0) / 1e6))
        }
    }

    private fun engineWith(preset: DrumPreset) = DrumEngine(FS, samples).apply {
        DrumPresets.load(bank, 0, preset)
        for (t in 0 until Kit.TRACKS) setKit(t, preset.kit)
        set(DrumParam.TEMPO, DrumParam.bpmToNorm(preset.bpm.toFloat()))
    }

    private fun render(engine: DrumEngine, seconds: Double, block: Int = 192): FloatArray {
        val frames = (seconds * FS).toInt()
        val out = FloatArray(frames * 2)
        val buf = FloatArray(block * 2)
        var done = 0
        while (done < frames) {
            val n = minOf(block, frames - done)
            engine.render(buf, n)
            System.arraycopy(buf, 0, out, done * 2, n * 2)
            done += n
        }
        return out
    }

    @Test
    fun everyKitSampleLoads() {
        for (kit in Kit.entries) for (t in 0 until Kit.TRACKS) {
            val d = samples.get(kit, t)
            var peak = 0f
            for (v in d) peak = maxOf(peak, abs(v))
            assertTrue("${kit.label} ${kit.files[t]} is silent", peak > 0.05f)
            assertTrue("${kit.label} ${kit.files[t]} too short", d.size > FS / 100)
        }
    }

    @Test
    fun resamplerKeepsSineCleanFrom441To48k() {
        val f = 1000.0
        val input = FloatArray(44100) { (0.5 * sin(2 * PI * f * it / 44100)).toFloat() }
        val out = Resampler.resample(input, 44100, FS)
        assertEquals(48000, out.size)
        // Compare the middle against the ideal sine at 48 kHz.
        var err = 0.0
        var sig = 0.0
        for (n in 4800 until 43200) {
            val ideal = 0.5 * sin(2 * PI * f * n / FS)
            err += (out[n] - ideal) * (out[n] - ideal)
            sig += ideal * ideal
        }
        val snr = 10 * log10(sig / err)
        println("resampler SNR: %.1f dB".format(snr))
        assertTrue("SNR $snr dB", snr > 80)
    }

    @Test
    fun allPresetsRenderFiniteBoundedAndAudible() {
        for (p in DrumPresets.all) {
            val e = engineWith(p)
            e.play()
            val out = render(e, 4.0)
            var peak = 0.0
            var sum = 0.0
            for (v in out) {
                assertTrue("${p.name}: non-finite", v.isFinite())
                peak = maxOf(peak, abs(v.toDouble()))
                sum += v * v
            }
            val rms = 10 * log10(sum / out.size)
            println("%-11s %s peak=%.2f rms=%.1f dBFS".format(p.name, p.kit.label, peak, rms))
            assertTrue(peak <= 1.0)
            assertTrue("${p.name} too quiet: $rms", rms > -30)
        }
    }

    @Test
    fun sequencerIsSampleAccurate() {
        val e = engineWith(DrumPresets.all[0]).apply { set(DrumParam.TEMPO, DrumParam.bpmToNorm(120f)) }
        e.play()
        val buf = FloatArray(2)
        var last = -1
        val changes = ArrayList<Int>()
        for (n in 0 until FS * 2) {
            e.render(buf, 1)
            if (e.currentStep != last) {
                changes.add(n); last = e.currentStep
            }
        }
        for (i in 1 until changes.size) assertEquals(6000, changes[i] - changes[i - 1])
    }

    @Test
    fun hitsFollowThePattern() {
        val e = DrumEngine(FS, samples)
        e.bank.toggle(0, 0, 0)
        e.bank.toggle(0, 0, 8)
        e.bank.toggleAccent(0, 5, 4)
        e.play()
        render(e, 60.0 / 130.0 * 4) // one bar at 130 BPM
        assertEquals(2, e.hits.get(0))
        assertEquals(1, e.hits.get(5))
        assertEquals(0, e.hits.get(1))
    }

    @Test
    fun mutedTracksStaySilent() {
        val e = DrumEngine(FS, samples)
        e.bank.toggle(0, 0, 0)
        e.setMuted(0, true)
        e.play()
        val out = render(e, 0.5)
        assertEquals(0, e.hits.get(0))
        assertTrue(out.all { abs(it) < 1e-3f })
    }

    @Test
    fun closedHatChokesOpenHat() {
        fun tail(choke: Boolean): Double {
            val e = DrumEngine(FS, samples)
            e.set(DrumParam.REVERB, 0f)
            e.set(Kit.OPEN_HAT, TrackParam.LEVEL, 1f)
            e.set(Kit.CLOSED_HAT, TrackParam.LEVEL, if (choke) 0f else 1f) // silent closed hat, only its choke
            e.bank.toggle(0, Kit.OPEN_HAT, 0)
            if (choke) e.bank.toggle(0, Kit.CLOSED_HAT, 1)
            e.play()
            val out = render(e, 0.4)
            // Energy 200-400 ms after the open hat.
            var s = 0.0
            for (i in FS / 5 * 2 until out.size) s += out[i] * out[i]
            return sqrt(s)
        }
        assertTrue(tail(choke = true) < tail(choke = false) * 0.05)
    }

    @Test
    fun decayKnobShortensTheHit() {
        fun tailEnergy(decay: Float): Double {
            val e = DrumEngine(FS, samples)
            e.set(DrumParam.REVERB, 0f)
            e.set(0, TrackParam.DECAY, decay)
            e.trigger(0, false)
            val out = render(e, 0.5)
            var s = 0.0
            for (i in FS / 5 * 2 until out.size) s += out[i] * out[i]
            return s
        }
        assertTrue(tailEnergy(0f) < tailEnergy(1f) * 0.01)
    }

    @Test
    fun sampleCacheRoundTrips() {
        val bytes = java.io.ByteArrayOutputStream()
        samples.writeTo(bytes)
        val back = SampleBank.readFrom(bytes.toByteArray().inputStream(), FS)!!
        for (kit in Kit.entries) for (t in 0 until Kit.TRACKS) {
            assertTrue(samples.get(kit, t).contentEquals(back.get(kit, t)))
        }
        assertTrue(SampleBank.readFrom(bytes.toByteArray().inputStream(), 44100) == null)
    }

    @Test
    fun bankRoundTrips() {
        val a = DrumPatternBank()
        DrumPresets.all.forEachIndexed { i, p -> DrumPresets.load(a, i, p) }
        a.setLength(2, 12)
        val b = DrumPatternBank()
        assertTrue(b.deserialize(a.serialize()))
        assertEquals(a.serialize(), b.serialize())
        assertTrue(!b.deserialize("nope"))
    }

    @Test
    fun rendersFasterThanRealTime() {
        val e = engineWith(DrumPresets.all[1]).apply { set(DrumParam.DELAY, 0.4f); set(DrumParam.REVERB, 0.5f) }
        e.play()
        render(e, 3.0)
        val t0 = System.nanoTime()
        render(e, 20.0)
        val factor = 20.0 / ((System.nanoTime() - t0) / 1e9)
        println("realtime factor: %.1fx".format(factor))
        assertTrue(factor > 5)
    }
}
