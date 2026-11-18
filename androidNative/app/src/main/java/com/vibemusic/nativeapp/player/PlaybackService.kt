package com.vibemusic.nativeapp.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.view.KeyEvent
import androidx.core.app.NotificationCompat
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaStyleNotificationHelper
import com.vibemusic.nativeapp.MainActivity
import com.vibemusic.nativeapp.R
import com.vibemusic.nativeapp.VibeApplication

class PlaybackService : MediaSessionService() {

    companion object {
        const val CHANNEL_ID = "vibe_playback_channel"
        const val NOTIFICATION_ID = 1001

        const val ACTION_PLAY = "com.vibemusic.nativeapp.action.PLAY"
        const val ACTION_PAUSE = "com.vibemusic.nativeapp.action.PAUSE"
        const val ACTION_TOGGLE = "com.vibemusic.nativeapp.action.TOGGLE"
        const val ACTION_NEXT = "com.vibemusic.nativeapp.action.NEXT"
        const val ACTION_PREV = "com.vibemusic.nativeapp.action.PREV"
        const val ACTION_UPDATE = "com.vibemusic.nativeapp.action.UPDATE"
        const val ACTION_STOP = "com.vibemusic.nativeapp.action.STOP"
    }

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as? VibeApplication ?: return
        val playerManager = app.playerManager
        val forwardingPlayer = VibeForwardingPlayer(playerManager.exoPlayer, playerManager)

        createNotificationChannel()

        val sessionIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            sessionIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, forwardingPlayer)
            .setId("VibeMusicSession")
            .setSessionActivity(pendingIntent)
            .setCallback(object : MediaSession.Callback {
                override fun onMediaButtonEvent(
                    session: MediaSession,
                    controllerInfo: MediaSession.ControllerInfo,
                    intent: Intent
                ): Boolean {
                    val keyEvent = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
                    if (keyEvent?.action == KeyEvent.ACTION_DOWN) {
                        when (keyEvent.keyCode) {
                            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                                playerManager.resume()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                                playerManager.pause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> {
                                playerManager.togglePlayPause()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                                playerManager.playNext()
                                return true
                            }
                            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                                playerManager.playPrevious()
                                return true
                            }
                        }
                    }
                    return super.onMediaButtonEvent(session, controllerInfo, intent)
                }
            })
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "VibeMusic Playback",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows now playing controls and artwork on lockscreen and notification center"
                setShowBadge(false)
                setSound(null, null)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? VibeApplication
        val pm = app?.playerManager
        when (intent?.action) {
            ACTION_PLAY -> pm?.resume()
            ACTION_PAUSE -> pm?.pause()
            ACTION_TOGGLE -> pm?.togglePlayPause()
            ACTION_NEXT -> pm?.playNext()
            ACTION_PREV -> pm?.playPrevious()
            ACTION_STOP -> {
                pm?.pause()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        updateForegroundNotification()
        return super.onStartCommand(intent, flags, startId)
    }

    fun updateForegroundNotification() {
        val app = application as? VibeApplication ?: return
        val pm = app.playerManager
        val track = pm.currentTrack.value
        val session = mediaSession ?: return

        if (track == null) {
            // Ensure startForegroundService callers don't trigger ForegroundServiceDidNotStartInTimeException
            val fallback = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle("Vibe Music")
                .setContentText("Ready")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, fallback, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                } else {
                    startForeground(NOTIFICATION_ID, fallback)
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
            } catch (_: Exception) {}
            return
        }

        val isPlaying = pm.isPlaying.value

        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPI = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val prevIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_PREV }
        val prevPI = PendingIntent.getService(this, 1, prevIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val toggleIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_TOGGLE }
        val togglePI = PendingIntent.getService(this, 2, toggleIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val nextIntent = Intent(this, PlaybackService::class.java).apply { action = ACTION_NEXT }
        val nextPI = PendingIntent.getService(this, 3, nextIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        val coverFile = app.store.getCoverFile(track.id)
        val largeBmp: Bitmap? = if (coverFile.exists() && coverFile.length() > 0) {
            try {
                BitmapFactory.decodeFile(coverFile.absolutePath)
            } catch (_: Exception) { null }
        } else null

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentTitle(track.displayTitle)
            .setContentText(track.displayArtist)
            .setSubText(track.album.ifBlank { null })
            .setLargeIcon(largeBmp)
            .setContentIntent(openPI)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .addAction(R.drawable.ic_skip_previous, "Previous", prevPI)
            .addAction(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play, if (isPlaying) "Pause" else "Play", togglePI)
            .addAction(R.drawable.ic_skip_next, "Next", nextPI)
            .setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        val notification = builder.build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            android.util.Log.e("PlaybackService", "startForeground failed: ${e.message}")
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as? VibeApplication
        if (app?.playerManager?.isPlaying?.value != true) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        mediaSession?.run {
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
