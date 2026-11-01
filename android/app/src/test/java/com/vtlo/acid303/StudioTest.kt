package com.vtlo.acid303

import android.content.pm.ActivityInfo
import com.vtlo.core.ui.PagerLayout
import com.vtlo.drums.dsp.Kit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The combined app: all three instruments load, three swipeable pages, user-selected orientation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "w891dp-h411dp-land")
class StudioTest {
    private fun pager(a: MainActivity): PagerLayout {
        fun find(v: android.view.View): PagerLayout? = when (v) {
            is PagerLayout -> v
            is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { find(v.getChildAt(it)) }
            else -> null
        }
        return find(a.window.decorView)!!
    }

    @Test(timeout = 120000)
    fun allInstrumentsOnThreePages() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create()
        val a = ctl.get()
        val app = a.application as StudioApp
        assertEquals(Kit.TR909, app.drums.kit(0))
        assertTrue(app.drums.bank.row(0, 0) != 0) // House preset kick row
        assertTrue(app.bass.bank.step(0, 0) != 0) // Acid Classic first step

        val pager = pager(a)
        assertEquals(3, pager.childCount)
        assertEquals(0, pager.currentPage)
        pager.setPage(2, animate = false)
        assertEquals(2, pager.currentPage)

        // First run: every sampler pad holds a sound from the bundled kits.
        for (p in 0 until 8) assertTrue(app.sampler.sample(p) != null)
        assertEquals("808 BD", app.sampler.padNames[0])

        // Landscape is the default orientation.
        assertEquals(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE, a.requestedOrientation)
        ctl.destroy()
    }

    @Test(timeout = 120000)
    @Config(qualifiers = "w411dp-h891dp-port")
    fun portraitBuildsAllPages() {
        val ctl = Robolectric.buildActivity(MainActivity::class.java).create()
        assertEquals(3, pager(ctl.get()).childCount)
        ctl.destroy()
    }
}
