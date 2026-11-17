package com.vtlo.desktop.ui

import com.vtlo.guide.Guide
import com.vtlo.guide.GuideInput
import com.vtlo.guide.GuideOverlay
import com.vtlo.guide.GuidePen
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Graphics2D
import java.awt.event.KeyEvent
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D

/** Desktop host for the shared French guide: draws it with Java2D in design units and maps keys. */
class GuideLayer(startPage: Int = 0, animate: Boolean = true) {
    val overlay = GuideOverlay(Guide.pages(GuideInput.DESKTOP), animate).apply { repeat(startPage) { next() } }
    private val coin = Coin()
    private lateinit var g: Graphics2D

    private val pen = object : GuidePen {
        private fun color(argb: Int) = Color(argb, true)

        override fun fillRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
            g.color = color(color)
            g.fill(RoundRectangle2D.Float(x, y, w, h, 2 * r, 2 * r))
        }

        override fun strokeRound(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int, width: Float) {
            g.color = color(color); g.stroke = BasicStroke(width)
            g.draw(RoundRectangle2D.Float(x, y, w, h, 2 * r, 2 * r))
        }

        override fun fillCircle(cx: Float, cy: Float, r: Float, color: Int) {
            g.color = color(color)
            g.fill(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        }

        override fun strokeCircle(cx: Float, cy: Float, r: Float, color: Int, width: Float) {
            g.color = color(color); g.stroke = BasicStroke(width)
            g.draw(Ellipse2D.Float(cx - r, cy - r, 2 * r, 2 * r))
        }

        override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
            g.color = color(color); g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Float(x1, y1, x2, y2))
        }

        override fun arc(cx: Float, cy: Float, r: Float, startDeg: Float, sweepDeg: Float, color: Int, width: Float) {
            g.color = color(color); g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            // Java2D arcs run counter-clockwise from 3 o'clock; the guide's run clockwise.
            g.draw(Arc2D.Float(cx - r, cy - r, 2 * r, 2 * r, -startDeg, -sweepDeg, Arc2D.OPEN))
        }

        override fun text(s: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean, align: Int) {
            val f = font(size, bold)
            g.font = f
            g.color = color(color)
            val w = g.fontMetrics.stringWidth(s)
            val left = when { align < 0 -> x; align > 0 -> x - w; else -> x - w / 2f }
            g.drawString(s, left, y)
        }

        override fun measure(s: String, size: Float, bold: Boolean): Float = g.getFontMetrics(font(size, bold)).stringWidth(s).toFloat()

        override fun coin(cx: Float, cy: Float, r: Float, angle: Float) = coin.draw(g, cx, cy, r, angle)
    }

    fun paint(g: Graphics2D, x0: Float, y0: Float, w: Float, h: Float) {
        this.g = g
        overlay.paint(pen, x0, y0, w, h, System.currentTimeMillis())
    }

    /** Arrow keys, Enter and Space page through; Esc closes. */
    fun key(code: Int) {
        when (code) {
            KeyEvent.VK_RIGHT, KeyEvent.VK_ENTER, KeyEvent.VK_SPACE -> overlay.next()
            KeyEvent.VK_LEFT -> overlay.previous()
            KeyEvent.VK_ESCAPE -> overlay.skip()
        }
    }
}
