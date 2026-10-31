package com.vtlo.acid303

import com.vtlo.acid303.dsp.AcidEngine
import com.vtlo.acid303.dsp.Param
import com.vtlo.drums.dsp.DrumEngine
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.SampleBank
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.math.abs

class StudioEngineTest {
    companion object {
        private const val FS = 48000
        private lateinit var samples: SampleBank

        @BeforeClass
        @JvmStatic
        fun load() {
            samples = SampleBank.load(FS) { File("../drums/src/main/assets", it).inputStream() }
        }
    }

    private fun studio() = StudioEngine(AcidEngine(FS), DrumEngine(FS, samples))

    @Test
    fun partsStartTogetherAndStayLocked() {
        val s = studio()
        s.drums.set(DrumParam.TEMPO, DrumParam.bpmToNorm(97f)) // overridden by the bass tempo at start
        s.play()
        val buf = FloatArray(2 * 37)
        repeat(FS * 4 / 37) {
            s.render(buf, 37)
            assertEquals(s.bass.currentStep, s.drums.currentStep)
        }
    }

    @Test
    fun tempoAndSwingFollowWhicheverPartChanged() {
        val s = studio()
        val buf = FloatArray(2 * 64)
        s.drums.set(DrumParam.TEMPO, DrumParam.bpmToNorm(150f))
        s.render(buf, 64)
        assertEquals(150f, Param.normToBpm(s.bass.get(Param.TEMPO)), 0.01f)
        s.bass.set(Param.TEMPO, Param.bpmToNorm(110f))
        s.bass.set(Param.SWING, 0.5f)
        s.render(buf, 64)
        assertEquals(110f, DrumParam.normToBpm(s.drums.get(DrumParam.TEMPO)), 0.01f)
        assertEquals(0.5f, s.drums.get(DrumParam.SWING), 0f)
    }

    @Test
    fun partOffTakesItOutOfTheMix() {
        val s = studio()
        s.bassOn = false
        s.drums.bank.toggle(0, 0, 0)
        s.drumsOn = false
        s.play()
        val buf = FloatArray(2 * 512)
        var peak = 0f
        repeat(FS / 512) {
            s.render(buf, 512)
            for (v in buf) peak = maxOf(peak, abs(v))
        }
        assertTrue("muted parts should be silent, peak $peak", peak < 1e-3f)
        assertTrue(s.drums.hits.get(0) > 0) // still running in time
    }
}
