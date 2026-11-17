package com.vtlo.acid303

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
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
import com.vtlo.sampler.SamplerScreen
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * The studio window: a tab strip (pages, parts in the mix, orientation) over a
 * swipeable pager holding the bass line, drum machine and sampler screens.
 */
class MainActivity : Activity(), ScreenHost {

    private val app get() = application as StudioApp
    override val activity: Activity get() = this
    override val transport: Transport get() = app.transport

    private lateinit var screens: List<InstrumentScreen>
    private lateinit var pager: PagerLayout
    private val tabs = ArrayList<PadButton>()
    private val partButtons = ArrayList<PadButton>()
    private lateinit var orientationButton: PadButton
    private lateinit var root: FrameLayout
    private var guide: GuideView? = null

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
            SamplerScreen(this, app.sampler),
            BassScreen(this, app.bass2, number = 2),
            DrumScreen(this, app.drums2, app::drumSamplesFor, number = 2),
        )
        val page = savedInstanceState?.getInt(STATE_PAGE) ?: app.prefs().getInt(KEY_PAGE, 0)

        setContentView(buildUi())
        enableEdgeToEdge()
        if (isLandscape()) hideSystemBars()
        pager.setPage(page, animate = false)
        refreshStrip()
        // French guide on first launch (and kept open across a rotation).
        val guidePage = savedInstanceState?.getInt(STATE_GUIDE, -1) ?: -1
        if (guidePage >= 0) showGuide(guidePage)
        else if (savedInstanceState == null && !app.prefs().getBoolean(KEY_GUIDE_SEEN, false)) showGuide(0)
    }

    private fun showGuide(startPage: Int = 0) {
        if (guide != null) return
        val view = GuideView(this, startPage) { closeGuide() }
        guide = view
        root.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        view.requestFocus()
    }

    private fun closeGuide() {
        val view = guide ?: return
        guide = null
        root.removeView(view)
        app.prefs().edit().putBoolean(KEY_GUIDE_SEEN, true).apply()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_PAGE, pager.currentPage)
        guide?.let { outState.putInt(STATE_GUIDE, it.overlay.page) }
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
        if (!isChangingConfigurations) screens.forEach { it.onHostStop() }
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
        root = FrameLayout(this).apply { setBackgroundColor(Palette.chassis) }
        applySystemInsets(root)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        column.addView(strip(isLandscape()))
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

    /**
     * Pages, parts in the mix and the orientation option. One row in landscape;
     * in portrait the part switches and orientation move to a second row.
     */
    private fun strip(landscape: Boolean): View {
        fun row() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val pad = dpi(6)
            setPadding(pad, dpi(2), pad, 0)
        }
        // Five pages and five mix switches fit on one row only on a wide landscape screen; otherwise two rows.
        val oneRow = landscape && resources.configuration.screenWidthDp >= ONE_ROW_MIN_WIDTH_DP
        val first = row()
        val second = if (oneRow) first else row()
        val tabWeight = 1f
        val partWeight = if (oneRow) 0.75f else 0.9f
        fun add(r: LinearLayout, v: View, weight: Float) = r.addView(v, LinearLayout.LayoutParams(0, MATCH_PARENT, weight).apply {
            marginEnd = dpi(4)
        })
        PAGES.forEachIndexed { i, label ->
            val tab = PadButton(this, label, dark = false).apply {
                contentDescription = "Show $label"
                setOnClickListener { pager.setPage(i, animate = true) }
            }
            tabs.add(tab)
            add(first, tab, tabWeight)
        }
        if (oneRow) first.addView(View(this), LinearLayout.LayoutParams(0, 1, 0.3f))
        app.engine.parts.forEachIndexed { i, part ->
            val b = PadButton(this, PART_NAMES[i]).apply {
                setOnClickListener {
                    part.on = !part.on
                    refreshStrip()
                }
            }
            partButtons.add(b)
            add(second, b, partWeight)
        }
        orientationButton = PadButton(this, "").apply {
            setOnClickListener {
                val next = Orientation.saved(app.prefs()).next()
                app.prefs().edit().putString(KEY_ORIENTATION, next.name).apply()
                applyOrientation(next)
                refreshStrip()
            }
        }
        add(second, orientationButton, if (oneRow) 1.2f else 1.7f)
        val help = PadButton(this, "?", dark = false).apply {
            contentDescription = "Afficher le guide"
            setOnClickListener { showGuide() }
        }
        add(second, help, if (oneRow) 0.45f else 0.55f)
        val height = dpi(STRIP_HEIGHT_DP)
        // Upper-right emblem: decorative, square, never in the way of a tap.
        first.addView(CoinView(this), LinearLayout.LayoutParams(height - dpi(4), MATCH_PARENT))
        if (oneRow) return first.apply { layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, height) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(first, LinearLayout.LayoutParams(MATCH_PARENT, height))
            addView(second, LinearLayout.LayoutParams(MATCH_PARENT, height))
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, height * 2)
        }
    }

    private fun refreshStrip() {
        tabs.forEachIndexed { i, t -> t.active = i == pager.currentPage }
        app.engine.parts.forEachIndexed { i, part ->
            val b = partButtons[i]
            val name = PART_NAMES[i]
            b.active = part.on
            b.setLabel(if (part.on) "$name ON" else "$name OFF")
            b.contentDescription = "${PAGES[i]} in the mix: " + if (part.on) "on" else "off"
        }
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

    private var pendingPermission: ((Boolean) -> Unit)? = null
    private var pendingOpen: ((Uri) -> Unit)? = null

    override fun requestPermission(permission: String, onResult: (Boolean) -> Unit) {
        if (checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return onResult(true)
        pendingPermission = onResult
        requestPermissions(arrayOf(permission), REQUEST_PERMISSION)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_PERMISSION) return
        val callback = pendingPermission
        pendingPermission = null
        callback?.invoke(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
    }

    override fun requestOpenAudio(onPicked: (Uri) -> Unit) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "audio/*"
        }
        try {
            pendingOpen = onPicked
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_OPEN)
        } catch (e: Exception) {
            pendingOpen = null
            toast("No file picker available")
        }
    }

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
        if (requestCode == REQUEST_OPEN) {
            val callback = pendingOpen
            pendingOpen = null
            val uri = data?.data
            if (resultCode == RESULT_OK && uri != null) callback?.invoke(uri)
            return
        }
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
        const val STATE_GUIDE = "guide_page"
        const val KEY_PAGE = "page"
        const val KEY_ORIENTATION = "orientation"
        const val KEY_GUIDE_SEEN = "guide_seen"
        const val STRIP_HEIGHT_DP = 40
        const val REQUEST_EXPORT = 1
        const val REQUEST_NOTIFICATIONS = 2
        const val REQUEST_PERMISSION = 3
        const val REQUEST_OPEN = 4
        /** Page order: the original three first, then the second bass line and second drum machine. */
        val PAGES = listOf("BASS 1", "DRUMS 1", "SAMPLER", "BASS 2", "DRUMS 2")
        val PART_NAMES = listOf("B1", "D1", "SMP", "B2", "D2")
        const val ONE_ROW_MIN_WIDTH_DP = 720
        const val KEY_ASKED_NOTIFICATIONS = "asked_notifications"
    }
}
