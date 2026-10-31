package com.vtlo.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder
import com.vtlo.core.R

/**
 * `mediaPlayback` foreground service that keeps the sequencer running while the
 * app is in the background. It exists only while the transport is playing and
 * exposes a MediaSession, so the notification, lock screen, headset buttons and
 * Bluetooth controls can stop playback.
 */
class PlaybackService : Service() {
    private val app get() = application as InstrumentApp
    private lateinit var session: MediaSession

    override fun onCreate() {
        super.onCreate()
        app.transport.service = this
        session = MediaSession(this, packageName).apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPause() = app.transport.stop()
                override fun onStop() = app.transport.stop()
                override fun onPlay() {
                    app.transport.start()
                }
            })
            setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setSessionActivity(openAppIntent())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            app.transport.stop()
            finish()
            return START_NOT_STICKY
        }
        // startForeground must always follow startForegroundService, even if
        // playback was stopped again in the meantime.
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        if (!app.transport.playing) finish()
        return START_NOT_STICKY
    }

    /** Swiping the app away from recents ends the session. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        app.transport.stop()
        finish()
    }

    fun finish() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (app.transport.service === this) app.transport.service = null
        session.isActive = false
        session.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.channel_playback), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.channel_playback_description)
                setShowBadge(false)
            }
        )

        val title = app.playbackTitle()
        val text = app.playbackText()

        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, applicationInfo.loadLabel(packageManager).toString())
                .build()
        )
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_STOP or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(PlaybackState.STATE_PLAYING, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build()
        )

        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, R.drawable.ic_stop), getString(R.string.action_stop), stopIntent
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setDeleteIntent(stopIntent)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(stopAction)
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0))
            .build()
    }

    private fun openAppIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, app.mainActivity).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    private companion object {
        const val CHANNEL_ID = "playback"
        const val NOTIFICATION_ID = 303
        const val ACTION_STOP = "com.vtlo.core.action.STOP"
    }
}
