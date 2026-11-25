package com.example.glassplayer

import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class Loop { OFF, ALL, ONE }

enum class Tab(val label: String) {
    SONGS("Songs"), MIXES("Mixes"), FAV("Favorites"), LISTS("Playlists"), ARTISTS("Artists"),
    ALBUMS("Albums"), GENRES("Genres"), QUEUE("Queue"), SETTINGS("Settings"),
}

data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val durationMs: Long,
    val accent: Color,
    val path: String? = null,
)

class Player(private val ctx: Context) {
    val covers = ArtLoader(ctx)
    private val prefs = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
    val fx = Fx(prefs)
    val ui = UiSettings(prefs)
    val mixer = Mixer(prefs)
    private val handler = Handler(Looper.getMainLooper())
    private val am = ctx.getSystemService(AudioManager::class.java)
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        )
        .setOnAudioFocusChangeListener { c ->
            if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause()
        }
        .build()

    init {
        handler.post(object : Runnable {
            override fun run() { tick(); handler.postDelayed(this, 250) }
        })
    }

    val library = mutableStateListOf<Track>()
    val queue = mutableStateListOf<Track>()
    var qi by mutableIntStateOf(-1)
    var playing by mutableStateOf(false)
    var position by mutableLongStateOf(0L)
    var shuffle by mutableStateOf(prefs.getBoolean("shuffle", false))
    var loop by mutableStateOf(Loop.values()[prefs.getInt("loop", 0).coerceIn(0, 2)])
    var speed by mutableFloatStateOf(1f)
    var sleepMin by mutableIntStateOf(0)
    var scanning by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)
    private var sleepAt = 0L
    private var mp: MediaPlayer? = null

    val favorites = mutableStateListOf<String>().apply { addAll(prefs.getStringSet("fav", emptySet()) ?: emptySet()) }
    val playlists = mutableStateMapOf<String, List<String>>().apply {
        runCatching {
            val o = JSONObject(prefs.getString("pl", "{}") ?: "{}")
            o.keys().forEach { k ->
                val a = o.getJSONArray(k)
                put(k, List(a.length()) { a.getString(it) })
            }
        }
    }

    val current: Track? get() = queue.getOrNull(qi)

    fun accent(t: Track?): Color = ui.fixedAccent() ?: t?.let { covers.accents[it.uri] ?: it.accent } ?: Color(0xFF5B6CFF)

    private fun save() {
        val pl = JSONObject().also { o -> playlists.forEach { (k, v) -> o.put(k, JSONArray(v)) } }
        prefs.edit().putStringSet("fav", favorites.toSet()).putBoolean("shuffle", shuffle)
            .putInt("loop", loop.ordinal).putString("pl", pl.toString()).apply()
    }

    // ---- Favorites and playlists ----

    fun isFav(t: Track) = t.uri.toString() in favorites
    fun toggleFav(t: Track) { val k = t.uri.toString(); if (!favorites.remove(k)) favorites.add(k); save() }
    fun resolve(keys: List<String>): List<Track> {
        val m = library.associateBy { it.uri.toString() }
        return keys.mapNotNull { m[it] }
    }
    fun favTracks() = resolve(favorites.toList().reversed())
    fun createPlaylist(n: String) { if (n.isNotBlank() && n !in playlists) { playlists[n] = emptyList(); save() } }
    fun addTo(n: String, t: Track) { val l = playlists[n] ?: return; val k = t.uri.toString(); if (k !in l) playlists[n] = l + k; save() }
    fun removeFrom(n: String, t: Track) { playlists[n] = (playlists[n] ?: return).filter { it != t.uri.toString() }; save() }
    fun deletePlaylist(n: String) { playlists.remove(n); save() }

    // ---- Library ----

    private fun clean(s: String?, d: String) = s?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: d
    private fun hue(name: String) = Color.hsv(name.hashCode().mod(360).toFloat(), 0.6f, 0.85f)

    private fun fileName(uri: Uri): String =
        runCatching {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (c.moveToFirst() && i >= 0) c.getString(i) else null
            }
        }.getOrNull()?.substringBeforeLast('.') ?: "Unknown track"

    fun add(uris: List<Uri>) {
        val have = library.map { it.uri }.toSet()
        uris.filter { it !in have }.forEach { library.add(load(it)) }
        if (qi < 0 && library.isNotEmpty()) playList(library.toList(), 0)
    }

    private fun load(uri: Uri): Track {
        val r = MediaMetadataRetriever()
        val m = { k: Int -> runCatching { r.extractMetadata(k) }.getOrNull() }
        try {
            r.setDataSource(ctx, uri)
            val title = clean(m(MediaMetadataRetriever.METADATA_KEY_TITLE), fileName(uri))
            return Track(
                uri, title,
                clean(m(MediaMetadataRetriever.METADATA_KEY_ARTIST), "Unknown artist"),
                clean(m(MediaMetadataRetriever.METADATA_KEY_ALBUM), "Unknown album"),
                clean(m(MediaMetadataRetriever.METADATA_KEY_GENRE), "Unknown genre"),
                m(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                hue(title),
            )
        } catch (_: Exception) {
            val n = fileName(uri)
            return Track(uri, n, "Unknown artist", "Unknown album", "Unknown genre", 0L, hue(n))
        } finally {
            r.release()
        }
    }

    suspend fun scan() {
        if (scanning) return
        scanning = true
        notice = null
        val found = withContext(Dispatchers.IO) {
            val out = mutableListOf<Track>()
            val base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val proj = buildList {
                add(MediaStore.Audio.Media._ID); add(MediaStore.Audio.Media.TITLE)
                add(MediaStore.Audio.Media.ARTIST); add(MediaStore.Audio.Media.DURATION)
                add(MediaStore.Audio.Media.DATA); add(MediaStore.Audio.Media.ALBUM)
                if (Build.VERSION.SDK_INT >= 30) add(MediaStore.Audio.Media.GENRE)
            }.toTypedArray()
            val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000"
            runCatching {
                ctx.contentResolver.query(base, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
                    while (c.moveToNext()) {
                        val title = clean(c.getString(1), "Unknown track")
                        out.add(
                            Track(
                                ContentUris.withAppendedId(base, c.getLong(0)), title,
                                clean(c.getString(2), "Unknown artist"), clean(c.getString(5), "Unknown album"),
                                clean(if (Build.VERSION.SDK_INT >= 30) c.getString(6) else null, "Unknown genre"),
                                c.getLong(3), hue(title), c.getString(4),
                            )
                        )
                    }
                }
            }
            out
        }
        val have = library.map { it.uri }.toSet()
        library.addAll(found.filter { it.uri !in have })
        scanning = false
        if (library.isEmpty()) notice = "No songs found. Tap Add songs to pick files."
    }

    // ---- Playback ----

    fun playList(list: List<Track>, start: Int) {
        queue.clear(); queue.addAll(list); startAt(start)
    }

    fun startAt(i: Int) {
        if (i !in queue.indices) return
        mp?.release()
        qi = i
        position = 0
        am.requestAudioFocus(focus)
        mp = MediaPlayer.create(ctx, queue[i].uri)?.apply {
            setOnCompletionListener { next(true) }
            start()
        }
        playing = mp != null
        mp?.let { fx.attach(it.audioSessionId) }
        applySpeed()
        mixer.played(queue[i])
        sync()
    }

    // Keeps the media notification and lock screen controls in step with playback
    fun sync() {
        if (current == null) return
        runCatching { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java)) }
    }

    fun pause() { val m = mp ?: return; if (m.isPlaying) m.pause(); playing = false; sync() }
    fun resume() { if (!playing) toggle() }

    private fun applySpeed() {
        runCatching { mp?.let { if (it.isPlaying) it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    fun playNext(t: Track) { if (queue.isEmpty()) playList(listOf(t), 0) else queue.add(qi + 1, t) }
    fun addToQueue(t: Track) { if (queue.isEmpty()) playList(listOf(t), 0) else queue.add(t) }

    fun toggle() {
        val m = mp ?: return
        if (m.isPlaying) { m.pause(); playing = false } else { am.requestAudioFocus(focus); m.start(); playing = true; applySpeed() }
        sync()
    }

    fun next(auto: Boolean = false) {
        if (queue.isEmpty()) return
        if (auto && loop == Loop.ONE) { startAt(qi); return }
        val n = if (shuffle && queue.size > 1) (queue.indices - qi).random() else qi + 1
        when {
            n < queue.size -> startAt(n)
            loop == Loop.ALL -> startAt(0)
            else -> { mp?.pause(); mp?.seekTo(0); playing = false; position = 0; sync() }
        }
    }

    fun prev() {
        if (queue.isEmpty()) return
        if (position > 3000) seek(0) else startAt(if (qi > 0) qi - 1 else queue.size - 1)
    }

    fun seek(ms: Long) { mp?.seekTo(ms.toInt()); position = ms }
    fun toggleShuffle() { shuffle = !shuffle; save() }
    fun cycleLoop() { loop = Loop.values()[(loop.ordinal + 1) % 3]; save() }
    fun cycleSpeed() {
        val s = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        speed = s[(s.indexOf(speed) + 1) % s.size]
        applySpeed()
    }
    fun cycleSleep() {
        val o = listOf(0, 15, 30, 60)
        sleepMin = o[(o.indexOf(sleepMin) + 1) % o.size]
        sleepAt = if (sleepMin > 0) System.currentTimeMillis() + sleepMin * 60_000L else 0L
    }

    fun tick() {
        if (playing) position = mp?.currentPosition?.toLong() ?: 0L
        if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) {
            mp?.pause(); playing = false; sleepAt = 0; sleepMin = 0; sync()
        }
    }

    fun release() { mp?.release(); mp = null }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            App((application as GlassApp).player)
        }
    }
}

@Composable
fun App(p: Player) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { p.add(it) }
        val ctx = LocalContext.current
        val scope = rememberCoroutineScope()
        val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
        val wanted = if (Build.VERSION.SDK_INT >= 33)
            listOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.POST_NOTIFICATIONS)
        else listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r[audioPerm] == true || ctx.checkSelfPermission(audioPerm) == PackageManager.PERMISSION_GRANTED) {
                scope.launch { p.scan() }
            } else {
                p.notice = "Allow audio access so songs can be scanned."
            }
        }
        val startScan: () -> Unit = {
            val need = wanted.filter { ctx.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (need.isEmpty()) scope.launch { p.scan() } else permLauncher.launch(need.toTypedArray())
        }
        LaunchedEffect(Unit) { startScan() }
        val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
            if (u != null) {
                runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
                p.ui.setBackground(u.toString(), ctx.contentResolver.getType(u)?.startsWith("video") == true)
            }
        }

        var tab by remember { mutableStateOf(Tab.SONGS) }
        var open by remember { mutableStateOf<String?>(null) }
        var query by remember { mutableStateOf("") }
        var menu by remember { mutableStateOf<Track?>(null) }
        var newPl by remember { mutableStateOf(false) }

        CompositionLocalProvider(LocalGlass provides p.ui.glass) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B14))) {
            Background(p.ui)
            Backdrop(p.accent(p.current), p.ui.blobs, if (p.ui.hasBg()) 0.45f else 1f)
            Column(
                Modifier.fillMaxSize().systemBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    GlassButton(if (p.scanning) "Scanning…" else "Scan") { startScan() }
                    Spacer(Modifier.width(8.dp))
                    GlassButton("Add songs") { picker.launch(arrayOf("audio/*")) }
                }
                NowPlaying(p)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(Tab.values().toList()) { t -> Chip(t.label, t == tab) { tab = t; open = null } }
                }
                Body(p, tab, open, { open = it }, query, { query = it }, { menu = it }, { newPl = true }, { bgPicker.launch(arrayOf("image/*", "video/*")) }, Modifier.weight(1f))
            }
        }
        menu?.let { t -> TrackMenu(p, t, if (tab == Tab.LISTS) open else null) { menu = null } }
        if (newPl) NewPlaylistDialog(p) { newPl = false }
        }
    }
}

@Composable
fun NowPlaying(p: Player) {
    val t = p.current
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp)).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val art by produceState<Bitmap?>(null, t?.uri) { value = t?.let { p.covers.art(it) } }
        val spin by rememberInfiniteTransition(label = "spin").animateFloat(0f, 360f, infiniteRepeatable(tween(12000, easing = LinearEasing)), label = "s")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(110.dp).rotate(if (p.ui.vinyl && p.playing) spin else 0f)
                    .clip(if (p.ui.vinyl) CircleShape else RoundedCornerShape(22.dp))
                    .background(Brush.linearGradient(listOf(p.accent(t), Color(0xFF1B1B2F)))),
                contentAlignment = Alignment.Center,
            ) {
                val a = art
                if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("♪", color = Color.White.copy(0.85f), fontSize = 48.sp)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(t?.title ?: "Nothing playing", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t?.artist ?: "Pick a song to start", color = Color.White.copy(0.7f), fontSize = 14.sp, maxLines = 1)
                if (t != null) Text(t.album, color = Color.White.copy(0.5f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t != null) Text(
                if (p.isFav(t)) "♥" else "♡", color = if (p.isFav(t)) Color(0xFFFF5C8A) else Color.White,
                fontSize = 26.sp, modifier = Modifier.clickable { p.toggleFav(t) }.padding(8.dp),
            )
        }
        val dur = (t?.durationMs ?: 0L).coerceAtLeast(1L)
        Slider(
            value = (p.position.toFloat() / dur).coerceIn(0f, 1f),
            onValueChange = { p.seek((it * dur).toLong()) },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(0.25f),
            ),
        )
        Row(Modifier.fillMaxWidth()) {
            Text(fmt(p.position), color = Color.White.copy(0.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(fmt(t?.durationMs ?: 0L), color = Color.White.copy(0.7f), fontSize = 12.sp)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Ctrl("⇄", p.shuffle) { p.toggleShuffle() }
            Ctrl("⏮", false) { p.prev() }
            Box(
                Modifier.size(64.dp).glass(CircleShape).clickable { p.toggle() },
                contentAlignment = Alignment.Center,
            ) { Text(if (p.playing) "❚❚" else "▶", color = Color.White, fontSize = 22.sp) }
            Ctrl("⏭", false) { p.next() }
            Ctrl(if (p.loop == Loop.ONE) "↻1" else "↻", p.loop != Loop.OFF) { p.cycleLoop() }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("${p.speed}x", p.speed != 1f) { p.cycleSpeed() }
            Chip(if (p.sleepMin > 0) "Sleep ${p.sleepMin}m" else "Sleep off", p.sleepMin > 0) { p.cycleSleep() }
        }
    }
}

@Composable
fun Body(
    p: Player, tab: Tab, open: String?, setOpen: (String?) -> Unit,
    query: String, setQuery: (String) -> Unit, onMenu: (Track) -> Unit, onNew: () -> Unit, pickBg: () -> Unit, modifier: Modifier,
) {
    if (tab == Tab.SETTINGS) { SettingsBody(p, pickBg, modifier); return }
    val mixes = if (tab == Tab.MIXES) remember(p.library.size, p.mixer.recent.size, p.mixer.plays.size) { p.mixer.mixes(p.library) } else emptyList()
    val grouped = tab == Tab.ARTISTS || tab == Tab.ALBUMS || tab == Tab.GENRES
    if (open == null && (grouped || tab == Tab.LISTS || tab == Tab.MIXES)) {
        val names: List<Pair<String, Int>> =
            if (tab == Tab.MIXES) mixes.map { it.first to it.second.size }
            else if (tab == Tab.LISTS) p.playlists.map { it.key to it.value.size }.sortedBy { it.first.lowercase() }
            else remember(p.library.size, tab) {
                p.library.groupBy { when (tab) { Tab.ARTISTS -> it.artist; Tab.ALBUMS -> it.album; else -> it.genre } }
                    .map { it.key to it.value.size }.sortedBy { it.first.lowercase() }
            }
        LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tab == Tab.LISTS) item { GlassButton("＋ New playlist", onNew) }
            items(names) { (n, c) ->
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).clickable { setOpen(n) }.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("$c songs", color = Color.White.copy(0.65f), fontSize = 12.sp)
                }
            }
        }
        return
    }
    val list: List<Track> = when (tab) {
        Tab.SONGS -> p.library.filter { query.isBlank() || it.title.contains(query, true) || it.artist.contains(query, true) }
        Tab.MIXES -> mixes.firstOrNull { it.first == open }?.second ?: emptyList()
        Tab.SETTINGS -> emptyList()
        Tab.FAV -> p.favTracks()
        Tab.QUEUE -> p.queue.toList()
        Tab.LISTS -> p.resolve(p.playlists[open] ?: emptyList())
        Tab.ARTISTS -> p.library.filter { it.artist == open }
        Tab.ALBUMS -> p.library.filter { it.album == open }
        Tab.GENRES -> p.library.filter { it.genre == open }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (open != null) Row(verticalAlignment = Alignment.CenterVertically) {
            GlassButton("‹ Back") { setOpen(null) }
            Text(open, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (tab == Tab.LISTS) GlassButton("Delete") { p.deletePlaylist(open); setOpen(null) }
        }
        if (tab == Tab.SONGS) OutlinedTextField(
            query, setQuery, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("Search songs or artists") },
        )
        if (tab != Tab.SONGS && tab != Tab.QUEUE && list.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Play") { p.playList(list, 0) }
            GlassButton("Shuffle") { if (!p.shuffle) p.toggleShuffle(); p.playList(list, list.indices.random()) }
        }
        val onPlay: (Int) -> Unit = if (tab == Tab.QUEUE) { i -> p.startAt(i) } else { i -> p.playList(list, i) }
        val empty = when (tab) {
            Tab.SONGS -> if (p.scanning) "Scanning your music…" else p.notice ?: "No songs yet. Tap Scan or Add songs."
            Tab.FAV -> "Tap ♡ on a song to add it to favorites."
            Tab.QUEUE -> "The queue is empty."
            else -> "No songs here yet. Use ⋯ on a song to add it."
        }
        TrackList(p, list, onPlay, onMenu, empty)
    }
}

@Composable
fun TrackList(p: Player, list: List<Track>, onPlay: (Int) -> Unit, onMenu: (Track) -> Unit, empty: String) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(24.dp)) {
            Text(empty, color = Color.White.copy(0.75f))
        }
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        itemsIndexed(list) { i, t ->
            val art by produceState<Bitmap?>(null, t.uri) { value = p.covers.art(t) }
            val sel = t.uri == p.current?.uri
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp))
                    .background(if (sel) Color.White.copy(0.16f) else Color.Transparent)
                    .clickable { onPlay(i) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.accent(t).copy(0.8f)),
                    contentAlignment = Alignment.Center,
                ) {
                    val a = art
                    if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else Text("♪", color = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    if (p.isFav(t)) "♥" else "♡", color = if (p.isFav(t)) Color(0xFFFF5C8A) else Color.White.copy(0.7f),
                    fontSize = 20.sp, modifier = Modifier.clickable { p.toggleFav(t) }.padding(8.dp),
                )
                Text("⋯", color = Color.White, fontSize = 20.sp, modifier = Modifier.clickable { onMenu(t) }.padding(horizontal = 12.dp, vertical = 8.dp))
            }
        }
    }
}

@Composable
fun Opt(label: String, onClick: () -> Unit) {
    Text(label, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp), fontSize = 16.sp)
}

@Composable
fun TrackMenu(p: Player, t: Track, fromList: String?, close: () -> Unit) {
    var adding by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        confirmButton = { TextButton(onClick = close) { Text("Close") } },
        title = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (!adding) {
                    Opt("Play next") { p.playNext(t); close() }
                    Opt("Add to queue") { p.addToQueue(t); close() }
                    Opt(if (p.isFav(t)) "Remove from favorites" else "Add to favorites") { p.toggleFav(t); close() }
                    Opt("Add to playlist…") { adding = true }
                    if (fromList != null) Opt("Remove from this playlist") { p.removeFrom(fromList, t); close() }
                } else {
                    if (p.playlists.isEmpty()) Text("Create a playlist first in the Playlists tab.")
                    p.playlists.keys.sorted().forEach { n -> Opt(n) { p.addTo(n, t); close() } }
                }
            }
        },
    )
}

@Composable
fun NewPlaylistDialog(p: Player, close: () -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = close,
        confirmButton = { TextButton(onClick = { p.createPlaylist(name.trim()); close() }) { Text("Create") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
        title = { Text("New playlist") },
        text = { OutlinedTextField(name, { name = it }, singleLine = true, placeholder = { Text("Playlist name") }) },
    )
}
