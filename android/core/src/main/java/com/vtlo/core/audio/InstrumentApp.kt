package com.vtlo.core.audio

import android.app.Activity
import android.app.Application

/**
 * Process-wide owner of an instrument's engine, audio output and transport, so
 * playback survives configuration changes and continues in the background.
 */
abstract class InstrumentApp : Application() {
    lateinit var output: AudioOutput
        private set
    lateinit var transport: Transport
        private set

    abstract val engine: SequencerEngine

    /** Activity the playback notification opens. */
    abstract val mainActivity: Class<out Activity>

    /** Notification title and text while playing in the background. */
    abstract fun playbackTitle(): String
    abstract fun playbackText(): String

    /** Creates the engine; called once the native output rate is known. */
    protected abstract fun createEngine(sampleRate: Int)

    override fun onCreate() {
        super.onCreate()
        output = AudioOutput(this)
        createEngine(output.sampleRate)
        transport = Transport(this)
    }
}
