package com.vtlo.acid303.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class EngineTest {
    private val fs = 48000

    private fun engineWith(preset: Preset, configure: AcidEngine.() -> Unit = {}): AcidEngine =
        AcidEngine(fs).apply {
            Presets.load(bank, 0, preset)
            set(Param.TEMPO, Param.bpmToNorm(preset.bpm.toFloat()))
            configure()
        }

    private fun render(engine: AcidEngine, seconds: Double, block: Int = 192): FloatArray {
        val frames = (seconds * fs).toInt()
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
    fun allPresetsRenderFiniteBoundedAndAudible() {
        for (p in Presets.all) {
            for (settings in listOf(0f, 0.5f, 1f)) {
                val e = engineWith(p) {
                    Param.entries.filter { it != Param.TEMPO && it != Param.VOLUME }.forEach { set(it, settings) }
                }
                e.play()
                val out = render(e, 3.0)
                var peak = 0.0
                var sum = 0.0
                for (v in out) {
                    assertTrue("${p.name}: non-finite sample", v.isFinite())
                    peak = maxOf(peak, abs(v.toDouble()))
                    sum += v * v
                }
                val rmsDb = 10 * log10(sum / out.size + 1e-20)
                println("%-15s knobs=%.1f peak=%.3f rms=%.1f dBFS".format(p.name, settings, peak, rmsDb))
                assertTrue("${p.name}: clipping beyond full scale", peak <= 1.0)
                assertTrue("${p.name}: too quiet ($rmsDb dBFS)", rmsDb > -40)
            }
        }
    }

    @Test
    fun defaultSoundSitsInHealthyLevelRange() {
        val e = engineWith(Presets.all[0])
        e.play()
        val out = render(e, 4.0)
        var sum = 0.0
        var peak = 0.0
        for (v in out) {
            sum += v * v; peak = maxOf(peak, abs(v.toDouble()))
        }
        val rmsDb = 10 * log10(sum / out.size)
        println("default rms=%.1f dBFS peak=%.2f".format(rmsDb, peak))
        assertTrue(rmsDb in -24.0..-6.0)
    }

    @Test
    fun sequencerIsSampleAccurate() {
        val e = engineWith(Presets.all[0]) { set(Param.TEMPO, Param.bpmToNorm(120f)) }
        e.play()
        val buf = FloatArray(2)
        val changes = ArrayList<Int>()
        var last = -1
        for (n in 0 until fs * 3) {
            e.render(buf, 1)
            if (e.currentStep != last) {
                changes.add(n); last = e.currentStep
            }
        }
        // 120 BPM sixteenths = 6000 samples at 48 kHz, exactly.
        for (i in 1 until changes.size) assertEquals(6000, changes[i] - changes[i - 1])
    }

    @Test
    fun patternSwitchHappensAtLoopEnd() {
        val e = engineWith(Presets.all[0])
        Presets.load(e.bank, 1, Presets.all[1])
        e.play()
        val buf = FloatArray(2)
        var switchedAtStep = -1
        e.render(buf, 1)
        e.selectedPattern = 1
        for (n in 0 until fs * 4) {
            e.render(buf, 1)
            if (e.playingPattern == 1) {
                switchedAtStep = e.currentStep; break
            }
        }
        assertEquals(0, switchedAtStep)
    }

    @Test
    fun oscillatorAliasingIsSuppressed() {
        // High note, filter fully open, no resonance: the worst case for aliasing.
        val v = Voice303(fs.toDouble()).apply {
            cutoff = 1.0; envMod = 1.0; resonance = 0.0; decay = 1.0
        }
        v.noteOn(72 + 12, accented = false, legato = false)
        val n = 1 shl 15
        repeat(2000) { v.render() }
        val x = DoubleArray(n) { v.render() }
        val f0 = 440.0 * Math.pow(2.0, (84 - 69) / 12.0)
        // Blackman window DFT magnitudes at probe frequencies.
        fun mag(f: Double): Double {
            var re = 0.0; var im = 0.0
            for (i in 0 until n) {
                val w = 0.42 - 0.5 * cos(2 * PI * i / (n - 1)) + 0.08 * cos(4 * PI * i / (n - 1))
                val ph = 2 * PI * f * i / fs
                re += x[i] * w * cos(ph); im += x[i] * w * sin(ph)
            }
            return sqrt(re * re + im * im)
        }
        val fundamental = mag(f0)
        // Exact frequencies where folded harmonics land, both from the oversampled
        // oscillator (folding around 4*fs) and from decimation (folding around fs).
        var worst = -200.0
        for (fold in intArrayOf(fs, fs * Voice303.OVERSAMPLING)) {
            var k = 1
            while (k * f0 < fold * 2) {
                val alias = abs(fold - k * f0)
                val nearest = Math.round(alias / f0) * f0
                if (alias in 100.0..16000.0 && abs(alias - nearest) > 40) {
                    worst = maxOf(worst, 20 * log10(mag(alias) / fundamental + 1e-12))
                }
                k++
            }
        }
        println("worst alias component below 16 kHz: %.1f dB".format(worst))
        assertTrue("aliasing too high: $worst dB", worst < -50)
    }

    @Test
    fun patternBankRoundTrips() {
        val a = PatternBank()
        Presets.all.forEachIndexed { i, p -> Presets.load(a, i, p) }
        a.setLength(3, 7)
        val b = PatternBank()
        assertTrue(b.deserialize(a.serialize()))
        assertEquals(a.serialize(), b.serialize())
        assertTrue(!b.deserialize("garbage"))
    }

    @Test
    fun presetSlidesUseHardwareSemantics() {
        // "Acid Classic" web step 4 slid *into* 48; here step 3 (rest) cannot slide,
        // so the flag only lands on gated steps preceding a slid-into note.
        val steps = Presets.all[0].steps
        for (i in steps.indices) {
            if (PatternBank.slide(steps[i])) assertTrue(PatternBank.gate(steps[i]))
        }
    }

    @Test
    fun wavExportProducesValidFile() {
        val bytes = ByteArrayOutputStream()
        val frames = WavExporter.export(bytes, loops = 1, patternLength = 16) { e ->
            Presets.load(e.bank, 0, Presets.all[0])
        }
        val data = bytes.toByteArray()
        assertEquals("RIFF", String(data, 0, 4))
        assertEquals(44 + frames * 6, data.size)
    }

    @Test
    fun rendersMuchFasterThanRealTime() {
        val e = engineWith(Presets.all[2]) { set(Param.DELAY, 0.5f); set(Param.REVERB, 0.5f); set(Param.DRIVE, 0.6f) }
        e.play()
        render(e, 5.0) // JIT warm-up
        val t0 = System.nanoTime()
        val seconds = 30.0
        render(e, seconds)
        val elapsed = (System.nanoTime() - t0) / 1e9
        println("realtime factor: %.1fx".format(seconds / elapsed))
        assertTrue(seconds / elapsed > 5)
    }
}
