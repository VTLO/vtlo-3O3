package com.vtlo.desktop

import com.vtlo.acid303.dsp.Param
import com.vtlo.drums.dsp.DrumParam
import com.vtlo.drums.dsp.Kit
import com.vtlo.sampler.dsp.PadParam
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The desktop studio without a window or sound card: start-up, kits, persistence. */
class StudioTest {
    private fun tempDir(): File = Files.createTempDirectory("vtlo-studio").toFile().apply { deleteOnExit() }

    @Test
    fun firstRunLoadsPresetsKitsAndSeedsPads() {
        val s = Studio(tempDir(), sampleRate = 48000)
        assertTrue(s.bass.bank.step(0, 0) != 0)
        assertTrue(s.drums.bank.row(0, 0) != 0)
        for (p in 0 until 8) assertNotNull(s.sampler.sample(p))
        assertEquals("808 BD", s.sampler.padNames[0])
        // Rendering the whole studio works without an audio device.
        s.engine.play()
        val buf = FloatArray(512)
        repeat(200) { s.engine.render(buf, 256) }
        assertTrue(buf.all { it.isFinite() && kotlin.math.abs(it) <= 1f })
    }

    @Test
    fun stateSurvivesRestart() {
        val dir = tempDir()
        val a = Studio(dir, sampleRate = 48000)
        a.bass.set(Param.CUTOFF, 0.77f)
        a.bass.selectedPattern = 5
        a.drums.setKit(3, Kit.TR606)
        a.drums.setMuted(2, true)
        a.drums.set(DrumParam.REVERB, 0.42f)
        a.sampler.set(4, PadParam.CRUSH, 0.6f)
        a.sampler.padNames[4] = "MIC 1"
        a.sampler.bank.toggle(0, 4, 7)
        a.engine.parts[1].on = false
        a.save()
        Thread.sleep(500) // pad files are written in the background

        val b = Studio(dir, sampleRate = 48000)
        assertEquals(0.77f, b.bass.get(Param.CUTOFF), 0f)
        assertEquals(5, b.bass.selectedPattern)
        assertEquals(Kit.TR606, b.drums.kit(3))
        assertTrue(b.drums.isMuted(2))
        assertEquals(0.42f, b.drums.get(DrumParam.REVERB), 0f)
        assertEquals(0.6f, b.sampler.get(4, PadParam.CRUSH), 0f)
        assertEquals("MIC 1", b.sampler.padNames[4])
        assertTrue(b.sampler.bank.isOn(0, 4, 7))
        assertTrue(!b.engine.parts[1].on)
        assertNotNull(b.sampler.sample(0)) // seeded pad audio was stored and reloaded
    }

    @Test
    fun coinPaintsBothFacesWithoutTakingClicks() {
        val coin = com.vtlo.desktop.ui.Coin().place(0f, 0f, 64f, 64f)
        assertTrue(!coin.interactive)
        val img = java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        coin.paint(g)
        g.dispose()
        // The face images are bundled, so the centre of the coin is painted.
        assertTrue((img.getRGB(32, 32) ushr 24) != 0)
    }

    @Test
    fun secondBassAndDrumsStartEmptyAndSurviveRestart() {
        val dir = tempDir()
        val a = Studio(dir, sampleRate = 48000)
        assertEquals(5, a.engine.parts.size)
        // Fresh: nothing programmed on the new pages, the original mix is unchanged.
        assertTrue((0 until 16).all { a.bass2.bank.step(0, it) and (1 shl 8) == 0 })
        assertTrue(a.drums2.bank.row(0, 0) == 0)
        assertTrue(a.engine.parts.all { it.on })

        a.bass2.set(Param.CUTOFF, 0.31f)
        a.bass2.bank.setStep(0, 3, com.vtlo.acid303.dsp.PatternBank.pack(40, true, true, false))
        a.drums2.setKit(1, Kit.TR606)
        a.drums2.bank.toggle(0, 2, 4)
        a.engine.parts[4].on = false
        a.save()

        val b = Studio(dir, sampleRate = 48000)
        assertEquals(0.31f, b.bass2.get(Param.CUTOFF), 0f)
        assertEquals(40, b.bass2.bank.step(0, 3) and 0x7f)
        assertEquals(Kit.TR606, b.drums2.kit(1))
        assertTrue(b.drums2.bank.row(0, 2) != 0)
        assertTrue(!b.engine.parts[4].on)
        // The first instruments are separate: untouched by the second ones' edits.
        assertTrue(b.bass.get(Param.CUTOFF) != 0.31f)
        assertTrue(b.drums.bank.row(0, 2) != b.drums2.bank.row(0, 2))
    }
}
