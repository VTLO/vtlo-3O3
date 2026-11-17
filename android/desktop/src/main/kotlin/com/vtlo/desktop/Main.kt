package com.vtlo.desktop

import com.vtlo.desktop.ui.BassScreen
import com.vtlo.desktop.ui.DrumScreen
import com.vtlo.desktop.ui.SamplerScreen
import com.vtlo.desktop.ui.StudioWindow
import java.io.File
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import javax.swing.UIManager
import kotlin.system.exitProcess

/**
 * VTLO 3O3 desktop. `--screenshots <dir>` renders the five screens to PNG and
 * exits (used to check the layout without a sound card).
 */
fun main(args: Array<String>) {
    System.setProperty("awt.useSystemAAFontSettings", "on")
    val shots = args.indexOf("--screenshots").takeIf { it >= 0 }?.let { File(args.getOrElse(it + 1) { "." }) }
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        val dataDir = if (shots != null) File(shots, "data") else Studio.defaultDataDir()
        val studio = Studio(dataDir)
        val window = StudioWindow(studio) { host -> listOf(BassScreen(host), DrumScreen(host), SamplerScreen(host), BassScreen(host, 2), DrumScreen(host, 2)) }
        if (shots != null) {
            shots.mkdirs()
            listOf("bass", "drums", "sampler", "bass2", "drums2").forEachIndexed { i, name ->
                ImageIO.write(window.snapshot(1600, 992, i), "png", File(shots, "desktop-$name.png"))
            }
            listOf(0, 3).forEach { g -> ImageIO.write(window.snapshot(1600, 992, 0, guidePage = g), "png", File(shots, "desktop-guide-$g.png")) }
            exitProcess(0)
        }
        window.show()
    }
}
