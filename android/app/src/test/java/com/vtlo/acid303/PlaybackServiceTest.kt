package com.vtlo.acid303

import android.content.Intent
import com.vtlo.core.audio.PlaybackService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Background playback: foreground service lifecycle driven by [Transport]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PlaybackServiceTest {
    @Test(timeout = 120000)
    fun playsInBackgroundAndStopsFromNotification() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val app = ctl.get().application as StudioApp
        assertTrue(app.transport.start())
        // Leave the app (also stops the UI frame loop, which Robolectric would spin forever).
        ctl.stop()
        assertFalse(app.transport.uiVisible)
        assertTrue(app.transport.playing)
        val started = shadowOf(app).nextStartedService
        assertEquals(PlaybackService::class.java.name, started.component!!.className)

        // Service comes up and goes foreground with a media notification.
        val svcCtl = Robolectric.buildService(PlaybackService::class.java, started).create().startCommand(0, 1)
        val svc = svcCtl.get()
        val sh = shadowOf(svc)
        assertFalse(sh.isForegroundStopped)
        val n = sh.lastForegroundNotification
        assertNotNull(n)
        assertEquals("Bass A1 · Drums A1", n.extras.getString("android.title"))
        assertEquals(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, svc.foregroundServiceType)

        assertTrue(app.transport.playing)
        assertSame(svc, app.transport.service)

        // Stop action from the notification.
        svc.onStartCommand(Intent(app, PlaybackService::class.java).setAction("com.vtlo.core.action.STOP"), 0, 2)
        assertFalse(app.transport.playing)
        assertTrue(sh.isStoppedBySelf)
        assertTrue(sh.isForegroundStopped)
        svcCtl.destroy()
        assertNull(app.transport.service)
        ctl.destroy()
    }

    @Test(timeout = 120000)
    fun serviceStartedAfterQuickStopStillCallsStartForegroundThenExits() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create().start()
        val app = ctl.get().application as StudioApp
        app.transport.start()
        ctl.stop()
        val started = shadowOf(app).nextStartedService
        app.transport.stop() // before the service reached onStartCommand
        val svcCtl = Robolectric.buildService(PlaybackService::class.java, started).create().startCommand(0, 1)
        val sh = shadowOf(svcCtl.get())
        assertTrue(sh.isForegroundStopped) // went foreground, then removed it
        assertTrue(sh.isStoppedBySelf)
        ctl.destroy()
    }
}
