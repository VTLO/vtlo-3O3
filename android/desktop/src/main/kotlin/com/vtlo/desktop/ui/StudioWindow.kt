package com.vtlo.desktop.ui

import com.vtlo.desktop.Studio
import java.awt.BorderLayout
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.KeyboardFocusManager
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.io.File
import java.io.OutputStream
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JFrame
import javax.swing.JMenuItem
import javax.swing.JPopupMenu
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.concurrent.thread
import kotlin.math.min

/** What a screen needs from the window. Main (Swing) thread only. */
interface Host {
    val studio: Studio
    val playing: Boolean
    fun toggleTransport()
    fun toast(message: String)
    /** Asks where to save [name], then renders on a background thread. */
    fun saveWav(name: String, render: (OutputStream) -> Unit)
    /** Lets the user pick an audio file. */
    fun openAudio(onFile: (File) -> Unit)
    fun popup(items: List<String>, x: Float, y: Float, onPick: (Int) -> Unit)
}

/** One instrument page, laid out in design units below the tab strip. */
abstract class Screen(protected val host: Host) {
    val widgets = ArrayList<Widget>()

    protected fun <T : Widget> add(w: T, x: Float, y: Float, width: Float, height: Float): T {
        w.place(x, y, width, height)
        widgets.add(w)
        return w
    }

    /** Re-reads every control from the engine (another page may have changed shared state). */
    abstract fun refresh()
    abstract fun onFrame(playing: Boolean)
    /** Computer keyboard; return true if handled. */
    open fun key(code: Int, pressed: Boolean): Boolean = false
    /** The window is closing: stop anything still running. */
    open fun close() {}

    companion object {
        const val TOP = 40f
    }
}

/**
 * The studio window: a tab strip over one of three screens, all painted on a
 * canvas at a fixed design size and scaled to fit the window.
 */
class StudioWindow(override val studio: Studio, screenFactory: (Host) -> List<Screen>) : Host {
    private val frame = JFrame("VTLO 3O3")
    private val canvas = Canvas()
    private val screens: List<Screen>
    private var page = studio.props.getProperty("ui.page")?.toIntOrNull()?.coerceIn(0, 2) ?: 0
    private val strip = ArrayList<Widget>()
    private val tabs = ArrayList<Pad>()
    private val parts = ArrayList<Pad>()
    private var toastText = ""
    private var toastUntil = 0L
    private var lastPlaying = false

    private var transportOn = false
    override val playing get() = transportOn

    init {
        screens = screenFactory(this)
        buildStrip()
        frame.defaultCloseOperation = JFrame.DO_NOTHING_ON_CLOSE
        frame.contentPane.add(canvas, BorderLayout.CENTER)
        frame.minimumSize = Dimension(800, 520)
        frame.size = Dimension(1320, 860)
        frame.setLocationRelativeTo(null)
        frame.iconImages = listOf(16, 32, 48, 256).map(::icon)
        frame.addWindowListener(object : WindowAdapter() {
            override fun windowClosing(e: WindowEvent) = close()
        })
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher { e ->
            if (!frame.isActive) return@addKeyEventDispatcher false
            handleKey(e)
        }
        Timer(16) { frameTick() }.start()
        screens.forEach { it.refresh() }
        refreshStrip()
    }

    fun show() {
        frame.isVisible = true
        studio.output.start()
        Timer(500) { studio.output.error?.let(::toast) }.apply { isRepeats = false; start() }
    }

    // ─── Strip ───────────────────────────────────────────────

    private fun buildStrip() {
        listOf("BASS LINE", "DRUMS", "SAMPLER").forEachIndexed { i, label ->
            val t = Pad(label, dark = false, hint = "Show $label (F${i + 1})") { setPage(i) }
            t.place(8f + i * 140f, 4f, 132f, 32f)
            tabs.add(t); strip.add(t)
        }
        listOf("BASS", "DRUMS", "SAMPLER").forEachIndexed { i, name ->
            val p = Pad(name, hint = "Take $name in or out of the mix") {
                val part = studio.engine.parts[i]
                part.on = !part.on
                refreshStrip()
            }
            p.place(452f + i * 116f, 4f, 110f, 32f)
            parts.add(p); strip.add(p)
        }
        strip.add(TextBox({ "SPACE run/stop · TAB next screen" }, 9.5f, Colors.panelEdge, bold = false).place(808f, 14f, 190f, 16f))
    }

    private fun refreshStrip() {
        tabs.forEachIndexed { i, t -> t.active = i == page }
        studio.engine.parts.forEachIndexed { i, part ->
            val name = listOf("BASS", "DRUMS", "SAMPLER")[i]
            parts[i].active = part.on
            parts[i].text = if (part.on) "$name ON" else "$name OFF"
        }
    }

    private fun setPage(p: Int) {
        page = (p + screens.size) % screens.size
        screens[page].refresh()
        refreshStrip()
        canvas.repaint()
    }

    // ─── Host ────────────────────────────────────────────────

    override fun toggleTransport() {
        if (transportOn) {
            studio.engine.stop()
            transportOn = false
        } else {
            studio.engine.play()
            transportOn = true
        }
    }

    override fun toast(message: String) {
        toastText = message
        toastUntil = System.currentTimeMillis() + 2500
        canvas.repaint()
    }

    override fun saveWav(name: String, render: (OutputStream) -> Unit) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Export WAV"
            selectedFile = File(System.getProperty("user.home"), name)
            fileFilter = FileNameExtensionFilter("WAV audio", "wav")
        }
        if (chooser.showSaveDialog(frame) != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile.let { if (it.name.endsWith(".wav", true)) it else File(it.path + ".wav") }
        toast("Rendering…")
        thread(name = "vtlo-export", isDaemon = true) {
            val ok = runCatching { file.outputStream().use(render) }.isSuccess
            SwingUtilities.invokeLater { toast(if (ok) "Exported ${file.name}" else "Export failed") }
        }
    }

    override fun openAudio(onFile: (File) -> Unit) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Load a sample"
            fileFilter = FileNameExtensionFilter("Audio (WAV, AIFF, AU)", "wav", "aif", "aiff", "au")
        }
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) onFile(chooser.selectedFile)
    }

    override fun popup(items: List<String>, x: Float, y: Float, onPick: (Int) -> Unit) {
        val menu = JPopupMenu()
        items.forEachIndexed { i, s -> menu.add(JMenuItem(s).apply { addActionListener { onPick(i) } }) }
        val (sx, sy) = canvas.toScreen(x, y)
        menu.show(canvas, sx, sy)
    }

    // ─── Loop, keys, close ───────────────────────────────────

    private fun frameTick() {
        val playing = playing
        if (playing != lastPlaying) {
            lastPlaying = playing
            screens.forEach { it.refresh() }
        }
        screens[page].onFrame(playing)
        canvas.repaint()
    }

    private fun handleKey(e: KeyEvent): Boolean {
        val pressed = e.id == KeyEvent.KEY_PRESSED
        if (e.id != KeyEvent.KEY_PRESSED && e.id != KeyEvent.KEY_RELEASED) return false
        if (pressed) when (e.keyCode) {
            KeyEvent.VK_SPACE -> { toggleTransport(); return true }
            KeyEvent.VK_TAB -> { setPage(page + if (e.isShiftDown) -1 else 1); return true }
            KeyEvent.VK_F1 -> { setPage(0); return true }
            KeyEvent.VK_F2 -> { setPage(1); return true }
            KeyEvent.VK_F3 -> { setPage(2); return true }
            KeyEvent.VK_ESCAPE -> {
                studio.engine.panic(); transportOn = false
                return true
            }
        }
        if (e.isControlDown || e.isAltDown) return false
        return screens[page].key(e.keyCode, pressed)
    }

    private fun close() {
        screens.forEach { it.close() }
        studio.props["ui.page"] = page.toString()
        runCatching { studio.save() }
        studio.output.stop()
        frame.dispose()
        System.exit(0)
    }

    /** Renders the current page to an image (used for screenshots and tests). */
    fun snapshot(width: Int, height: Int, pageIndex: Int = page): BufferedImage {
        page = pageIndex
        screens[page].refresh()
        refreshStrip()
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        canvas.paintAll(g, width, height)
        g.dispose()
        return img
    }

    // ─── Canvas ──────────────────────────────────────────────

    private inner class Canvas : JComponent() {
        private var scale = 1f
        private var ox = 0f
        private var oy = 0f
        private var captured: Widget? = null

        init {
            isFocusable = true
            preferredSize = Dimension(DW.toInt(), DH.toInt())
            toolTipText = ""
            val mouse = object : MouseAdapter() {
                override fun mousePressed(e: MouseEvent) {
                    requestFocusInWindow()
                    val (x, y) = toDesign(e.x, e.y)
                    val w = hit(x, y) ?: return
                    val p = Press(x, y, SwingUtilities.isRightMouseButton(e), e.clickCount, e.isShiftDown)
                    if (w.press(p)) captured = w
                    afterInput()
                }

                override fun mouseDragged(e: MouseEvent) {
                    val (x, y) = toDesign(e.x, e.y)
                    captured?.drag(x, y)
                    afterInput()
                }

                override fun mouseReleased(e: MouseEvent) {
                    val (x, y) = toDesign(e.x, e.y)
                    captured?.release(x, y)
                    captured = null
                    afterInput()
                }

                override fun mouseMoved(e: MouseEvent) {
                    val (x, y) = toDesign(e.x, e.y)
                    cursor = if (hit(x, y) != null) Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) else Cursor.getDefaultCursor()
                }

                override fun mouseWheelMoved(e: MouseWheelEvent) {
                    val (x, y) = toDesign(e.x, e.y)
                    hit(x, y)?.wheel(e.wheelRotation)
                    afterInput()
                }
            }
            addMouseListener(mouse)
            addMouseMotionListener(mouse)
            addMouseWheelListener(mouse)
        }

        private fun afterInput() {
            refreshStrip()
            repaint()
        }

        private fun widgets() = strip + screens[page].widgets

        private fun hit(x: Float, y: Float): Widget? = widgets().lastOrNull { it.interactive && it.contains(x, y) }

        fun toDesign(px: Int, py: Int) = (px - ox) / scale to (py - oy) / scale
        fun toScreen(x: Float, y: Float) = (x * scale + ox).toInt() to (y * scale + oy).toInt()

        override fun getToolTipText(e: MouseEvent): String? {
            val (x, y) = toDesign(e.x, e.y)
            return hit(x, y)?.tooltip
        }

        override fun paintComponent(g: Graphics) = paintAll(g as Graphics2D, width, height)

        fun paintAll(g: Graphics2D, width: Int, height: Int) {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g.color = Colors.chassis
            g.fillRect(0, 0, width, height)
            scale = min(width / DW, height / DH)
            ox = (width - DW * scale) / 2
            oy = (height - DH * scale) / 2
            val saved: AffineTransform = g.transform
            g.translate(ox.toDouble(), oy.toDouble())
            g.scale(scale.toDouble(), scale.toDouble())
            for (w in widgets()) w.paint(g)
            if (System.currentTimeMillis() < toastUntil) {
                g.font = font(13f)
                val tw = g.fontMetrics.stringWidth(toastText) + 32f
                g.roundRect(DW / 2 - tw / 2, DH - 52f, tw, 34f, 17f, java.awt.Color(0x2E2C2A), Colors.panelEdge)
                g.textCentered(toastText, DW / 2, DH - 30f, font(13f), Colors.buttonText)
            }
            g.transform = saved
        }
    }

    companion object {
        const val DW = 1000f
        const val DH = 620f

        /** The app icon: a resonant sawtooth, as on Android. */
        fun icon(size: Int): BufferedImage {
            val img = BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val s = size / 108f
            g.roundRect(0f, 0f, size.toFloat(), size.toFloat(), size * 0.18f, Colors.chassis)
            g.stroke = java.awt.BasicStroke(5f * s, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            g.color = Colors.panel
            val saw = java.awt.geom.Path2D.Float().apply {
                moveTo(22 * s, 70 * s); lineTo(40 * s, 36 * s); lineTo(40 * s, 70 * s); lineTo(58 * s, 36 * s)
                lineTo(58 * s, 70 * s); lineTo(76 * s, 36 * s); lineTo(76 * s, 70 * s)
            }
            g.draw(saw)
            g.color = Colors.ledOn
            g.stroke = java.awt.BasicStroke(4f * s, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
            g.draw(java.awt.geom.Path2D.Float().apply {
                moveTo(22 * s, 82 * s); curveTo(46 * s, 82 * s, 60 * s, 80 * s, 68 * s, 74 * s)
                curveTo(73 * s, 68 * s, 75 * s, 62 * s, 77 * s, 62 * s); curveTo(80 * s, 62 * s, 81 * s, 74 * s, 86 * s, 82 * s)
            })
            g.dispose()
            return img
        }
    }
}
