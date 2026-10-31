package com.vtlo.core.ui

import android.app.Activity
import android.view.View
import com.vtlo.core.audio.Transport
import java.io.OutputStream

/** What a screen needs from the activity hosting it. */
interface ScreenHost {
    val activity: Activity
    val transport: Transport

    /** Starts or stops the shared transport (all instruments together). */
    fun toggleTransport()

    /**
     * Asks the user where to save [fileName], then runs [render] on a background
     * thread with that file. [render] must only use state captured beforehand.
     */
    fun requestExport(fileName: String, render: (OutputStream) -> Unit)

    fun toast(message: String)
}

/** One instrument page of the studio. Main thread only. */
abstract class InstrumentScreen(protected val host: ScreenHost) {
    protected val context get() = host.activity

    /** Builds the page for the current orientation; called again after every recreation. */
    abstract fun build(landscape: Boolean): View

    /** Re-reads every control from the engine (another page may have changed shared state). */
    abstract fun refreshAll()

    /** Called once per display frame while the page is attached: playhead, LEDs. */
    abstract fun onFrame(playing: Boolean)

    /** Transport state changed (RUN/STOP button). */
    abstract fun onTransportChanged(playing: Boolean)
}
