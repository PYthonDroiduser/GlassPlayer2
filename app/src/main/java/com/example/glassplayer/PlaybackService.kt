package com.example.glassplayer

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.IBinder

class GlassApp : Application() {
    val player by lazy { Player(this) }
}

class PlaybackService : Service() {
    private lateinit var session: MediaSession
    private val p get() = (application as GlassApp).player

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("play", "Playback", NotificationManager.IMPORTANCE_LOW))
        session = MediaSession(this, "GlassPlayer").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { p.resume() }
                override fun onPause() { p.pause() }
                override fun onSkipToNext() { p.next() }
                override fun onSkipToPrevious() { p.prev() }
                override fun onSeekTo(pos: Long) { p.seek(pos) }
            })
            isActive = true
        }
    }

    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        when (i?.action) {
            "toggle" -> p.toggle()
            "next" -> p.next()
            "prev" -> p.prev()
        }
        refresh()
        return START_NOT_STICKY
    }

    private fun act(icon: Int, label: String, a: String) = Notification.Action.Builder(
        Icon.createWithResource(this, icon), label,
        PendingIntent.getForegroundService(
            this, a.hashCode(), Intent(this, PlaybackService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE,
        ),
    ).build()

    private fun refresh() {
        val t = p.current
        val art = t?.let { p.covers.cached(it) }
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO,
                )
                .setState(
                    if (p.playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    p.position, if (p.playing) p.speed else 0f,
                ).build()
        )
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, t?.title ?: "")
                .putString(MediaMetadata.METADATA_KEY_ARTIST, t?.artist ?: "")
                .putLong(MediaMetadata.METADATA_KEY_DURATION, t?.durationMs ?: 0L)
                .apply { if (art != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art) }
                .build()
        )
        val n = Notification.Builder(this, "play")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t?.title ?: "Glass Player")
            .setContentText(t?.artist)
            .setLargeIcon(art)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .addAction(act(android.R.drawable.ic_media_previous, "Previous", "prev"))
            .addAction(act(if (p.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, "Play or pause", "toggle"))
            .addAction(act(android.R.drawable.ic_media_next, "Next", "next"))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .setOngoing(p.playing)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(1, n)
        if (!p.playing) stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onDestroy() {
        session.release()
        super.onDestroy()
    }
}
