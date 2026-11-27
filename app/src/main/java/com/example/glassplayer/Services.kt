package com.example.glassplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import java.net.URLEncoder

/** Intentionally empty. Android only lets an app see other apps' media sessions if it holds a notification-listener grant. */
class MediaListener : NotificationListenerService()

enum class Svc(val label: String, val pkg: String, val color: Color, val home: String, val search: String) {
    SPOTIFY("Spotify", "com.spotify.music", Color(0xFF1DB954), "https://open.spotify.com", "https://open.spotify.com/search/"),
    YTMUSIC("YouTube Music", "com.google.android.apps.youtube.music", Color(0xFFFF0033), "https://music.youtube.com", "https://music.youtube.com/search?q="),
    SOUNDCLOUD("SoundCloud", "com.soundcloud.android", Color(0xFFFF5500), "https://soundcloud.com", "https://soundcloud.com/search?q="),
}

fun isLink(s: String) = s.trim().let { it.startsWith("http", true) || it.startsWith("spotify:", true) }

// Opens a link or search in the service's own app, or in the browser if the app isn't installed
fun openIn(ctx: Context, svc: Svc, input: String) {
    val q = input.trim()
    val url = when {
        q.isEmpty() -> svc.home
        isLink(q) -> q
        else -> svc.search + URLEncoder.encode(q, "UTF-8").replace("+", "%20")
    }
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(Intent(view).setPackage(svc.pkg)) }.onFailure {
        runCatching { ctx.startActivity(view) }
    }
}

/** One playing (or paused) media session from another app */
class Ext(val pkg: String, val c: MediaController) {
    val svc: Svc? = Svc.values().firstOrNull { it.pkg == pkg }
    val label: String = svc?.label ?: pkg
    var title by mutableStateOf("")
    var artist by mutableStateOf("")
    var art by mutableStateOf<Bitmap?>(null)
    var playing by mutableStateOf(false)
    var pos by mutableLongStateOf(0L)
    var dur by mutableLongStateOf(0L)

    private val cb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) { readMeta(metadata) }
        override fun onPlaybackStateChanged(state: PlaybackState?) { readState(state) }
    }

    init {
        c.registerCallback(cb)
        readMeta(c.metadata)
        readState(c.playbackState)
    }

    private fun readMeta(m: MediaMetadata?) {
        title = m?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
        artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
        art = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        dur = m?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
    }

    private fun readState(s: PlaybackState?) {
        playing = s?.state == PlaybackState.STATE_PLAYING
        pos = s?.position ?: 0L
    }

    fun tick() {
        val s = c.playbackState ?: return
        if (s.state == PlaybackState.STATE_PLAYING) {
            pos = s.position + ((SystemClock.elapsedRealtime() - s.lastPositionUpdateTime) * s.playbackSpeed).toLong()
        }
    }

    fun release() { runCatching { c.unregisterCallback(cb) } }
}

/** Watches other music apps (Spotify, YouTube Music, SoundCloud and any other media app) through Android's MediaSession API */
class Sources(private val ctx: Context) {
    val list = mutableStateListOf<Ext>()
    var allowed by mutableStateOf(false)
    private val msm = ctx.getSystemService(MediaSessionManager::class.java)
    private val comp = ComponentName(ctx, MediaListener::class.java)
    private var registered = false
    private val changed = MediaSessionManager.OnActiveSessionsChangedListener { rebuild(it ?: emptyList()) }

    private fun hasAccess() =
        Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners")?.contains(ctx.packageName) == true

    fun installed(s: Svc) = runCatching { ctx.packageManager.getPackageInfo(s.pkg, 0) }.isSuccess

    fun refresh() {
        allowed = hasAccess()
        if (!allowed) { stop(); return }
        runCatching {
            if (!registered) { msm.addOnActiveSessionsChangedListener(changed, comp); registered = true }
            rebuild(msm.getActiveSessions(comp))
        }.onFailure { allowed = false }
    }

    private fun rebuild(cs: List<MediaController>) {
        val keep = cs.filter { it.packageName != ctx.packageName }
        list.filter { e -> keep.none { it.packageName == e.pkg } }.forEach { it.release(); list.remove(it) }
        keep.forEach { c -> if (list.none { it.pkg == c.packageName }) list.add(Ext(c.packageName, c)) }
    }

    // Called when the app is hidden so nothing keeps listening in the background
    fun stop() {
        list.forEach { it.release() }
        list.clear()
        if (registered) { runCatching { msm.removeOnActiveSessionsChangedListener(changed) }; registered = false }
    }

    fun tick() { if (list.isNotEmpty()) list.forEach { it.tick() } }
}
