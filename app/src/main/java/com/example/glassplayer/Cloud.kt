package com.example.glassplayer

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.text.Html
import android.util.Base64
import android.util.LruCache
import androidx.compose.runtime.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

object Net {
    suspend fun req(method: String, url: String, headers: Map<String, String> = emptyMap(), body: String? = null): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val c = URL(url).openConnection() as HttpURLConnection
            try {
                c.requestMethod = method
                c.connectTimeout = 10_000
                c.readTimeout = 15_000
                headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
                if (method != "GET") {
                    val b = (body ?: "").toByteArray()
                    c.doOutput = true
                    c.setFixedLengthStreamingMode(b.size)
                    c.outputStream.use { it.write(b) }
                }
                val code = c.responseCode
                val stream = if (code in 200..299) c.inputStream else c.errorStream
                code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
            } catch (e: Exception) {
                -1 to (e.message ?: "network error")
            } finally {
                c.disconnect()
            }
        }

    private val cache = LruCache<String, Bitmap>(80)

    suspend fun bitmap(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val b = withContext(Dispatchers.IO) {
            runCatching {
                URL(url).openStream().use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 }) }
            }.getOrNull()
        }
        if (b != null) cache.put(url, b)
        return b
    }
}

class Hit(val svc: Svc, val title: String, val sub: String, val art: String?, val link: String, val uri: String? = null)

/**
 * Official APIs only: Spotify Web API (sign-in with PKCE), YouTube Data API v3 (API key) and the SoundCloud API (client credentials).
 * Keys and tokens are entered by the user and kept in this app's private storage.
 */
class Cloud(private val ctx: Context, private val prefs: SharedPreferences) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val redirect = "glassplayer://spotify-callback"
    private val spScopes = "user-read-playback-state user-modify-playback-state user-read-currently-playing playlist-read-private user-library-read"

    var spotifyId by mutableStateOf(prefs.getString("spId", "") ?: "")
    var ytKey by mutableStateOf(prefs.getString("ytKey", "") ?: "")
    var scId by mutableStateOf(prefs.getString("scId", "") ?: "")
    var scSecret by mutableStateOf(prefs.getString("scSecret", "") ?: "")
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    val results = mutableStateListOf<Hit>()

    private var spToken by mutableStateOf(prefs.getString("spTok", null))
    private var spRefresh: String? = prefs.getString("spRef", null)
    private var spExpires = prefs.getLong("spExp", 0L)
    private var verifier: String? = prefs.getString("spVer", null)
    private var scTok: String? = null
    private var scExpires = 0L

    val spConnected get() = spToken != null

    fun saveKeys() {
        prefs.edit().putString("spId", spotifyId.trim()).putString("ytKey", ytKey.trim())
            .putString("scId", scId.trim()).putString("scSecret", scSecret.trim()).apply()
        status = "Saved"
    }

    private fun open(url: String) {
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // ---------- Spotify ----------

    fun spotifyLogin() {
        if (spotifyId.isBlank()) { status = "Enter your Spotify Client ID first"; return }
        saveKeys()
        val raw = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val v = Base64.encodeToString(raw, flags)
        verifier = v
        prefs.edit().putString("spVer", v).apply()
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(v.toByteArray()), flags)
        val url = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", spotifyId.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirect)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("scope", spScopes)
            .build()
        ctx.startActivity(Intent(Intent.ACTION_VIEW, url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    // Called by MainActivity when the browser returns to glassplayer://spotify-callback
    fun onRedirect(u: Uri) {
        if (u.scheme != "glassplayer" || u.host != "spotify-callback") return
        val code = u.getQueryParameter("code")
        if (code == null) { status = "Spotify sign-in was cancelled"; return }
        scope.launch {
            tokenRequest("grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(redirect)}&client_id=${enc(spotifyId.trim())}&code_verifier=${enc(verifier ?: "")}")
        }
    }

    private suspend fun tokenRequest(form: String): Boolean {
        val (code, body) = Net.req("POST", "https://accounts.spotify.com/api/token", mapOf("Content-Type" to "application/x-www-form-urlencoded"), form)
        if (code != 200) { status = "Spotify sign-in failed ($code)"; return false }
        return runCatching {
            val j = JSONObject(body)
            spToken = j.getString("access_token")
            j.optString("refresh_token").takeIf { it.isNotEmpty() }?.let { spRefresh = it }
            spExpires = System.currentTimeMillis() + j.getLong("expires_in") * 1000 - 30_000
            prefs.edit().putString("spTok", spToken).putString("spRef", spRefresh).putLong("spExp", spExpires).apply()
            status = "Spotify connected"
            true
        }.getOrElse { status = "Spotify sent an unexpected reply"; false }
    }

    fun spotifyLogout() {
        spToken = null; spRefresh = null
        prefs.edit().remove("spTok").remove("spRef").remove("spExp").apply()
        status = "Spotify disconnected"
    }

    private suspend fun sp(method: String, path: String, body: String? = null): Pair<Int, String>? {
        if (spToken == null) { status = "Connect Spotify first"; return null }
        if (System.currentTimeMillis() > spExpires) {
            val r = spRefresh
            if (r == null || !tokenRequest("grant_type=refresh_token&refresh_token=${enc(r)}&client_id=${enc(spotifyId.trim())}")) {
                spToken = null; status = "Spotify session expired, connect again"; return null
            }
        }
        return Net.req(method, "https://api.spotify.com/v1$path", mapOf("Authorization" to "Bearer $spToken", "Content-Type" to "application/json"), body)
    }

    private fun spHit(t: JSONObject): Hit {
        val artists = t.optJSONArray("artists")
        val names = if (artists == null) "" else (0 until artists.length()).joinToString { artists.getJSONObject(it).optString("name") }
        return Hit(
            Svc.SPOTIFY, t.optString("name"), names,
            t.optJSONObject("album")?.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
            t.optJSONObject("external_urls")?.optString("spotify") ?: "", t.optString("uri"),
        )
    }

    private suspend fun spSearch(q: String): List<Hit> {
        if (!spConnected) return emptyList()
        val r = sp("GET", "/search?type=track&limit=10&q=${enc(q)}") ?: return emptyList()
        if (r.first != 200) { status = "Spotify search failed (${r.first})"; return emptyList() }
        return runCatching {
            val items = JSONObject(r.second).getJSONObject("tracks").getJSONArray("items")
            List(items.length()) { spHit(items.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    fun loadSpotifyLibrary() {
        scope.launch {
            busy = true; results.clear()
            val pl = sp("GET", "/me/playlists?limit=30")
            if (pl != null && pl.first == 200) runCatching {
                val a = JSONObject(pl.second).getJSONArray("items")
                for (i in 0 until a.length()) {
                    val o = a.optJSONObject(i) ?: continue
                    results.add(Hit(
                        Svc.SPOTIFY, o.optString("name"), "Playlist",
                        o.optJSONArray("images")?.optJSONObject(0)?.optString("url"),
                        o.optJSONObject("external_urls")?.optString("spotify") ?: "", o.optString("uri"),
                    ))
                }
            }
            val liked = sp("GET", "/me/tracks?limit=30")
            if (liked != null && liked.first == 200) runCatching {
                val a = JSONObject(liked.second).getJSONArray("items")
                for (i in 0 until a.length()) a.optJSONObject(i)?.optJSONObject("track")?.let { results.add(spHit(it)) }
            }
            if (results.isEmpty() && status == null) status = "Nothing found in your Spotify library"
            busy = false
        }
    }

    private fun playOnSpotify(h: Hit) {
        val uri = h.uri ?: return
        scope.launch {
            val body = if (":track:" in uri) """{"uris":["$uri"]}""" else """{"context_uri":"$uri"}"""
            val r = sp("PUT", "/me/player/play", body) ?: return@launch
            when {
                r.first in 200..299 -> status = "Playing on Spotify"
                r.first == 404 || r.first == 403 -> { status = "No active Spotify device, opening the Spotify app instead"; if (h.link.isNotEmpty()) open(h.link) }
                else -> status = "Spotify replied ${r.first}"
            }
        }
    }

    // ---------- YouTube Data API ----------

    private suspend fun ytSearch(q: String): List<Hit> {
        if (ytKey.isBlank()) return emptyList()
        val r = Net.req("GET", "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&videoCategoryId=10&maxResults=10&q=${enc(q)}&key=${enc(ytKey.trim())}")
        if (r.first != 200) { status = "YouTube search failed (${r.first}). Check the API key and quota"; return emptyList() }
        return runCatching {
            val items = JSONObject(r.second).getJSONArray("items")
            List(items.length()) {
                val o = items.getJSONObject(it)
                val sn = o.getJSONObject("snippet")
                Hit(
                    Svc.YTMUSIC, Html.fromHtml(sn.optString("title"), Html.FROM_HTML_MODE_LEGACY).toString(), sn.optString("channelTitle"),
                    sn.optJSONObject("thumbnails")?.optJSONObject("medium")?.optString("url"),
                    "https://music.youtube.com/watch?v=" + o.getJSONObject("id").optString("videoId"),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---------- SoundCloud API ----------

    private suspend fun scToken(): String? {
        if (scId.isBlank() || scSecret.isBlank()) return null
        val t = scTok
        if (t != null && System.currentTimeMillis() < scExpires) return t
        val basic = Base64.encodeToString("${scId.trim()}:${scSecret.trim()}".toByteArray(), Base64.NO_WRAP)
        val r = Net.req(
            "POST", "https://secure.soundcloud.com/oauth/token",
            mapOf("Authorization" to "Basic $basic", "Content-Type" to "application/x-www-form-urlencoded", "Accept" to "application/json; charset=utf-8"),
            "grant_type=client_credentials",
        )
        if (r.first != 200) { status = "SoundCloud sign-in failed (${r.first}). Check the Client ID and secret"; return null }
        return runCatching {
            val j = JSONObject(r.second)
            scTok = j.getString("access_token")
            scExpires = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 30_000
            scTok
        }.getOrNull()
    }

    private suspend fun scSearch(q: String): List<Hit> {
        val t = scToken() ?: return emptyList()
        val r = Net.req("GET", "https://api.soundcloud.com/tracks?q=${enc(q)}&limit=10", mapOf("Authorization" to "OAuth $t", "Accept" to "application/json; charset=utf-8"))
        if (r.first != 200) { status = "SoundCloud search failed (${r.first})"; return emptyList() }
        return runCatching {
            val a = if (r.second.trimStart().startsWith("[")) JSONArray(r.second) else JSONObject(r.second).getJSONArray("collection")
            List(a.length()) {
                val o = a.getJSONObject(it)
                Hit(
                    Svc.SOUNDCLOUD, o.optString("title"), o.optJSONObject("user")?.optString("username") ?: "",
                    o.optString("artwork_url").takeIf { u -> u.isNotEmpty() && u != "null" }, o.optString("permalink_url"),
                )
            }
        }.getOrDefault(emptyList())
    }

    // ---------- Search and play ----------

    fun search(q: String) {
        saveKeys()
        status = null
        scope.launch {
            busy = true
            results.clear()
            val all = coroutineScope {
                listOf(async { spSearch(q) }, async { ytSearch(q) }, async { scSearch(q) }).awaitAll().flatten()
            }
            results.addAll(all)
            if (all.isEmpty() && status == null) status = "No results. Connect a service under Accounts first."
            busy = false
        }
    }

    fun play(h: Hit) {
        if (h.svc == Svc.SPOTIFY && h.uri != null && spConnected) playOnSpotify(h) else openIn(ctx, h.svc, h.link)
    }
}
