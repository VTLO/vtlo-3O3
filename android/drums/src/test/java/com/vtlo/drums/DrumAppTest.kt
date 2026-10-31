package com.vtlo.drums

import com.vtlo.core.audio.PlaybackService
import com.vtlo.drums.dsp.Kit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** App start-up with the bundled kits, and background playback through the shared service. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land")
class DrumAppTest {
    @Test(timeout = 120000)
    fun startsWithPresetsAndPlaysInBackground() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val app = ctl.get().application as DrumApp
        assertEquals(Kit.TR909, app.engine.kit(0))
        assertTrue(app.engine.bank.row(0, 0) != 0) // House preset kick row

        assertTrue(app.transport.start())
        ctl.stop() // leave the app; also stops the UI frame loop Robolectric would spin forever
        assertTrue(app.transport.playing)
        val started = shadowOf(app).nextStartedService
        assertEquals(PlaybackService::class.java.name, started.component!!.className)
        val svc = Robolectric.buildService(PlaybackService::class.java, started).create().startCommand(0, 1)
        val n = shadowOf(svc.get()).lastForegroundNotification
        assertEquals("Pattern A1 · 909", n.extras.getString("android.title"))

        app.transport.stop()
        assertFalse(app.transport.playing)
        svc.destroy()
        ctl.destroy()
    }
}
