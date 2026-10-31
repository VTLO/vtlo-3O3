package com.vtlo.acid303

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import com.vtlo.core.audio.Transport
import com.vtlo.core.ui.InstrumentScreen
import com.vtlo.core.ui.PadButton
import com.vtlo.core.ui.PagerLayout
import com.vtlo.core.ui.Palette
import com.vtlo.core.ui.ScreenHost
import com.vtlo.core.ui.applySystemInsets
import com.vtlo.core.ui.dpi
import com.vtlo.core.ui.enableEdgeToEdge
import com.vtlo.core.ui.hideSystemBars
import com.vtlo.core.ui.isLandscape
import com.vtlo.drums.DrumScreen
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * The studio window: a tab strip (pages, parts in the mix, orientation) over a
 * swipeable pager holding the bass line and drum machine screens.
 */
class MainActivity : Activity(), ScreenHost {

    private val app get() = application as StudioApp
    override val activity: Activity get() = this
    override val transport: Transport get() = app.transport

    private lateinit var screens: List<InstrumentScreen>
    private lateinit var pager: PagerLayout
    private val tabs = ArrayList<PadButton>()
    private lateinit var bassPart: PadButton
    private lateinit var drumsPart: PadButton
    private lateinit var orientationButton: PadButton

    private val mainHandler = Handler(Looper.getMainLooper())
    private val exportExecutor = Executors.newSingleThreadExecutor()
    private var pendingExport: ((OutputStream) -> Unit)? = null

    private var lastPlaying = false
    private var animating = false

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!animating) return
            val playing = transport.playing
            if (playing != lastPlaying) {
                lastPlaying = playing
                screens.forEach { it.onTransportChanged(playing) }
            }
            screens[pager.currentPage].onFrame(playing)
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyOrientation(Orientation.saved(app.prefs()))
        volumeControlStream = AudioManager.STREAM_MUSIC
        screens = listOf(
            BassScreen(this, app.bass),
            DrumScreen(this, app.drums, app::drumSamplesFor),
        )
        val page = savedInstanceState?.getInt(STATE_PAGE) ?: app.prefs().getInt(KEY_PAGE, 0)

        setContentView(buildUi())
        enableEdgeToEdge()
        if (isLandscape()) hideSystemBars()
        pager.setPage(page, animate = false)
        refreshStrip()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, pager.currentPage)
    }

    override fun onStart() {
        super.onStart()
        transport.uiVisible = true
        app.output.start(app.engine)
        lastPlaying = transport.playing
        screens.forEach { it.refreshAll() }
        animating = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onStop() {
        animating = false
        app.prefs().edit().putInt(KEY_PAGE, pager.currentPage).apply()
        app.save()
        // Playback continues in the foreground service; an idle audio device is released.
        if (!isChangingConfigurations) transport.uiVisible = false
        super.onStop()
    }

    override fun onDestroy() {
        exportExecutor.shutdown()
        super.onDestroy()
    }

    // ─── UI ──────────────────────────────────────────────────

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Palette.chassis) }
        applySystemInsets(root)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        column.addView(strip(), LinearLayout.LayoutParams(MATCH_PARENT, dpi(STRIP_HEIGHT_DP)))
        pager = PagerLayout(this).apply {
            val landscape = isLandscape()
            screens.forEach { addView(it.build(landscape)) }
            onPageChanged = { p ->
                screens[p].refreshAll()
                refreshStrip()
            }
        }
        column.addView(pager, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        return root
    }

    private fun strip(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dpi(6)
            setPadding(pad, dpi(2), pad, 0)
        }
        fun add(v: View, weight: Float) = row.addView(v, LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply {
            marginEnd = dpi(4)
        })
        listOf("BASS LINE", "DRUMS").forEachIndexed { i, label ->
            val tab = PadButton(this, label, dark = false).apply {
                contentDescription = "Show $label"
                setOnClickListener { pager.setPage(i, animate = true) }
            }
            tabs.add(tab)
            add(tab, 1.3f)
        }
        row.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.4f))
        bassPart = PadButton(this, "BASS").apply {
            setOnClickListener {
                app.engine.bassOn = !app.engine.bassOn
                refreshStrip()
            }
        }
        add(bassPart, 1f)
        drumsPart = PadButton(this, "DRUMS").apply {
            setOnClickListener {
                app.engine.drumsOn = !app.engine.drumsOn
                refreshStrip()
            }
        }
        add(drumsPart, 1f)
        orientationButton = PadButton(this, "").apply {
            setOnClickListener {
                val next = Orientation.saved(app.prefs()).next()
                app.prefs().edit().putString(KEY_ORIENTATION, next.name).apply()
                applyOrientation(next)
                refreshStrip()
            }
        }
        add(orientationButton, 1.3f)
        return row
    }

    private fun refreshStrip() {
        tabs.forEachIndexed { i, t -> t.active = i == pager.currentPage }
        fun part(b: PadButton, name: String, on: Boolean) {
            b.active = on
            b.setLabel(if (on) "$name ON" else "$name OFF")
            b.contentDescription = "$name in the mix: " + if (on) "on" else "off"
        }
        part(bassPart, "BASS", app.engine.bassOn)
        part(drumsPart, "DRUMS", app.engine.drumsOn)
        val o = Orientation.saved(app.prefs())
        orientationButton.setLabel(o.label)
        orientationButton.contentDescription = "Screen orientation: ${o.label.lowercase()}. Tap to change"
    }

    /**
     * The user's choice of screen orientation. Large screens on Android 16+ may
     * ignore it; the pages then adapt to the window shape instead.
     */
    @SuppressLint("SourceLockedOrientationActivity")
    private fun applyOrientation(o: Orientation) {
        if (requestedOrientation != o.request) requestedOrientation = o.request
    }

    private enum class Orientation(val label: String, val request: Int) {
        LANDSCAPE("LANDSCAPE", ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE),
        PORTRAIT("PORTRAIT", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT),
        AUTO("AUTO ROTATE", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        fun next() = entries[(ordinal + 1) % entries.size]

        companion object {
            fun saved(prefs: android.content.SharedPreferences) =
                entries.firstOrNull { it.name == prefs.getString(KEY_ORIENTATION, null) } ?: LANDSCAPE
        }
    }

    // ─── ScreenHost ──────────────────────────────────────────

    override fun toggleTransport() {
        if (transport.playing) {
            transport.stop()
        } else {
            if (!transport.start()) {
                toast("Audio is in use by another app")
                return
            }
            requestNotificationPermissionOnce()
        }
        lastPlaying = transport.playing
        screens.forEach { it.onTransportChanged(lastPlaying) }
    }

    override fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()

    /** The playback notification needs this on Android 13+; playback works without it. */
    private fun requestNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        val prefs = app.prefs()
        if (prefs.getBoolean(KEY_ASKED_NOTIFICATIONS, false)) return
        prefs.edit().putBoolean(KEY_ASKED_NOTIFICATIONS, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    /** WAV export through the Storage Access Framework: no storage permission needed. */
    override fun requestExport(fileName: String, render: (OutputStream) -> Unit) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/wav"
            putExtra(Intent.EXTRA_TITLE, fileName)
        }
        try {
            pendingExport = render
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_EXPORT)
        } catch (e: Exception) {
            pendingExport = null
            toast("No file picker available")
        }
    }

    @Deprecated("Platform API; no AndroidX dependency")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT) return
        val render = pendingExport
        pendingExport = null
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null || render == null) return
        toast("Rendering…")
        exportExecutor.execute {
            val ok = try {
                contentResolver.openOutputStream(uri)?.use(render) != null
            } catch (e: Exception) {
                false
            }
            mainHandler.post { toast(if (ok) "WAV exported" else "Export failed") }
        }
    }

    private companion object {
        const val STATE_PAGE = "page"
        const val KEY_PAGE = "page"
        const val KEY_ORIENTATION = "orientation"
        const val STRIP_HEIGHT_DP = 40
        const val REQUEST_EXPORT = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
