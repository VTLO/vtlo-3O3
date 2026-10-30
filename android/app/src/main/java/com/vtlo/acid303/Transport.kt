package com.vtlo.acid303

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Starts and stops the sequencer and everything that has to follow it: audio
 * focus, the headphone-unplug receiver, the audio device and the foreground
 * service that keeps playback alive while the UI is in the background.
 *
 * Main thread only.
 */
class Transport(private val app: AcidApp) {
    private val audioManager = app.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    var playing = false
        private set

    /** True while an activity is visible; the audio device stays open for previews then. */
    var uiVisible = false
        set(v) {
            field = v
            releaseOutputIfIdle()
        }

    /** The running service, registered by the service itself. */
    var service: PlaybackService? = null

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
        )
        .setOnAudioFocusChangeListener({ change ->
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop()
        }, handler)
        .build()

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) stop()
        }
    }

    /** @return false if another app holds audio focus. */
    fun start(): Boolean {
        if (playing) return true
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        playing = true
        app.output.start(app.engine)
        app.engine.play()
        val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(noisyReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(noisyReceiver, filter)
        try {
            app.startForegroundService(Intent(app, PlaybackService::class.java))
        } catch (e: Exception) {
            // Only reachable if started while not in the foreground; play on without the service.
            Log.w(TAG, "Could not start playback service", e)
        }
        return true
    }

    fun stop() {
        app.engine.stop()
        if (!playing) return
        playing = false
        audioManager.abandonAudioFocusRequest(focusRequest)
        app.unregisterReceiver(noisyReceiver)
        // A service that has not reached onStartCommand yet sees playing == false there and stops itself.
        service?.finish()
        releaseOutputIfIdle()
    }

    fun panic() {
        stop()
        app.engine.panic()
    }

    private fun releaseOutputIfIdle() {
        if (!playing && !uiVisible) app.output.stop()
    }

    private companion object {
        const val TAG = "Transport"
    }
}
