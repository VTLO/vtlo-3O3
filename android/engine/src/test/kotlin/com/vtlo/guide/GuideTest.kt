package com.vtlo.guide

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuideTest {
    /** Records text and button-sized shapes; measures text as a fixed-width font. */
    private class FakePen : GuidePen {
        val texts = ArrayList<Pair<String, Float>>() // text, left edge
        var maxRight = 0f
        override fun fillRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {}
        override fun strokeRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float) {}
        override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {}
        override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {}
        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {}
        override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int, width: Float) {}
        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Int) {
            texts.add(s to x)
            if (align < 0) maxRight = maxOf(maxRight, x + measure(s, size, bold))
        }
        override fun measure(s: String, size: Float, bold: Boolean) = s.length * size * 0.55f
        override fun coin(cx: Float, cy: Float, r: Float, angle: Float) {}
    }

    @Test
    fun bothVariantsHaveSevenFrenchPages() {
        for (input in GuideInput.values()) {
            val pages = Guide.pages(input)
            assertEquals(7, pages.size)
            assertEquals("Bienvenue dans VTLO 3O3", pages[0].title)
            assertTrue(pages.all { it.title.isNotBlank() && it.body.length > 40 })
        }
        assertTrue(Guide.pages(GuideInput.TOUCH)[1].body.contains("Glissez"))
        assertTrue(Guide.pages(GuideInput.DESKTOP)[1].body.contains("TAB"))
    }

    @Test
    fun textStaysInsideTheCardOnAPhoneInPortrait() {
        // A 411 x 891 dp phone in portrait: card is 387 wide, starting at x = 12.
        for (input in GuideInput.values()) {
            val overlay = GuideOverlay(Guide.pages(input), animate = false)
            repeat(overlay.pages.size) {
                val pen = FakePen()
                overlay.paint(pen, 0f, 0f, 411f, 891f, 0L)
                assertTrue("page ${overlay.page} overflows", pen.maxRight <= 12f + 387f - 20f + 0.5f)
                overlay.next()
            }
        }
    }

    @Test
    fun buttonsStepThroughAndFinish() {
        val overlay = GuideOverlay(Guide.pages(GuideInput.TOUCH), animate = false)
        val w = 1000f
        val h = 620f
        overlay.paint(FakePen(), 0f, 0f, w, h, 0L)
        // SUIVANT is at the bottom right of the 560 x 380 card.
        val nextX = (w + 560f) / 2f - 20f - 60f
        val buttonY = (h + 380f) / 2f - 20f - 18f
        overlay.tap(nextX, buttonY)
        assertEquals(1, overlay.page)
        overlay.tap(10f, 10f) // outside the card: swallowed, nothing happens
        assertEquals(1, overlay.page)
        overlay.paint(FakePen(), 0f, 0f, w, h, 10L)
        // PASSER at the bottom left ends the guide.
        overlay.tap((w - 560f) / 2f + 20f + 40f, buttonY)
        assertTrue(overlay.finished)

        val full = GuideOverlay(Guide.pages(GuideInput.DESKTOP), animate = false)
        repeat(6) { full.next() }
        assertTrue(full.isLast)
        assertFalse(full.finished)
        full.next()
        assertTrue(full.finished)
    }
}
