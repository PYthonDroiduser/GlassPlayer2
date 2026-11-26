package com.example.glassplayer

import android.Manifest
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.res.painterResource
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
    SONGS("Songs"), MIXES("Mixes"), FAV("Liked Songs"), LISTS("Playlists"), ARTISTS("Artists"),
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
    val sources = Sources(ctx)
    val cloud = Cloud(ctx, prefs)
    val viz = Viz()
    private val vib = ctx.getSystemService(Vibrator::class.java)
    private val shake = Shake(ctx, { ui.shakeLevel }) { onShake() }
    private var fg = true
    private var saveTick = 0
    private var restored = false
    var xfade by mutableIntStateOf(prefs.getInt("xfade", 0))
    private var fading = false
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
            val sel = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ${ui.minSec * 1000}"
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
        restore()
        if (library.isEmpty()) notice = "No songs found. Tap Add songs to pick files."
    }

    // ---- Playback ----

    fun playList(list: List<Track>, start: Int) {
        queue.clear(); queue.addAll(list); startAt(start)
    }

    fun startAt(i: Int, from: Long = 0L, autoplay: Boolean = true) {
        if (i !in queue.indices) return
        if (autoplay && from == 0L && xfade > 0 && mp?.isPlaying == true) { crossfadeTo(i); return }
        mp?.release()
        qi = i
        position = 0
        if (autoplay) am.requestAudioFocus(focus)
        mp = MediaPlayer.create(ctx, queue[i].uri)?.apply {
            setOnCompletionListener { next(true) }
            if (from > 0) seekTo(from.toInt())
            if (autoplay) start()
        }
        playing = mp != null && autoplay
        position = from
        mp?.let { fx.attach(it.audioSessionId); attachViz() }
        applySpeed()
        if (autoplay) { mixer.played(queue[i]); sync() }
        saveState()
    }

    // Keeps the media notification and lock screen controls in step with playback
    fun sync() {
        if (current == null) return
        runCatching { ctx.startForegroundService(Intent(ctx, PlaybackService::class.java)) }
    }

    fun pause() { val m = mp ?: return; if (m.isPlaying) m.pause(); playing = false; saveState(); sync() }
    fun resume() { if (!playing) toggle() }
    fun updateSpeed(s: Float) { speed = s; applySpeed() }
    fun setSleep(m: Int) { sleepMin = m; sleepAt = if (m > 0) System.currentTimeMillis() + m * 60_000L else 0L }

    fun updateXfade(s: Int) { xfade = s; prefs.edit().putInt("xfade", s).apply() }
    fun cycleXfade() { val o = listOf(0, 3, 6, 9, 12); updateXfade(o[(o.indexOf(xfade) + 1) % o.size]) }

    private fun nextIndex(): Int {
        if (queue.isEmpty()) return -1
        val n = if (shuffle && queue.size > 1) (queue.indices - qi).random() else qi + 1
        return when { n < queue.size -> n; loop == Loop.ALL -> 0; else -> -1 }
    }

    // Overlaps the end of the current song with the start of the next using an equal-power fade
    private fun crossfadeTo(i: Int) {
        val old = mp ?: return
        val nm = MediaPlayer.create(ctx, queue[i].uri) ?: return
        val ms = (xfade * 1000L).coerceAtLeast(1L)
        fading = true
        old.setOnCompletionListener(null)
        nm.setVolume(0f, 0f)
        nm.setOnCompletionListener { next(true) }
        nm.start()
        mp = nm; qi = i; position = 0; playing = true
        fx.attach(nm.audioSessionId); attachViz(); applySpeed()
        mixer.played(queue[i]); saveState(); sync()
        val t0 = System.currentTimeMillis()
        handler.post(object : Runnable {
            override fun run() {
                val f = ((System.currentTimeMillis() - t0) / ms.toFloat()).coerceIn(0f, 1f)
                val down = kotlin.math.cos(f * Math.PI / 2).toFloat()
                val up = kotlin.math.sin(f * Math.PI / 2).toFloat()
                runCatching { old.setVolume(down, down) }
                runCatching { nm.setVolume(up, up) }
                if (f < 1f) handler.postDelayed(this, 40) else { runCatching { old.release() }; fading = false }
            }
        })
    }

    fun tap() {
        if (ui.haptic == 0) return
        runCatching { vib?.vibrate(VibrationEffect.createOneShot(if (ui.haptic == 1) 12L else 30L, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }

    private fun onShake() { if (!shuffle) toggleShuffle(); next(); tap() }

    fun attachViz() {
        val m = mp
        if (!fg || !ui.viz || m == null || ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            viz.release(); return
        }
        viz.attach(m.audioSessionId, ui.barCount, ui.vizRateDiv)
    }

    // Called when the app is shown or hidden: sensors and visualizer only run on screen
    fun foreground(on: Boolean) {
        fg = on
        if (on && ui.shake) shake.start() else shake.stop()
        if (on) attachViz() else viz.release()
        if (on) sources.refresh() else sources.stop()
    }

    private fun saveState() {
        prefs.edit().putString("q", queue.joinToString("\n") { it.uri.toString() })
            .putInt("qi", qi).putLong("qpos", position).apply()
    }

    private fun restore() {
        if (restored || !ui.restore || queue.isNotEmpty()) return
        restored = true
        val tr = resolve((prefs.getString("q", "") ?: "").split("\n").filter { it.isNotEmpty() })
        if (tr.isEmpty()) return
        queue.addAll(tr)
        startAt(prefs.getInt("qi", 0).coerceIn(0, tr.size - 1), prefs.getLong("qpos", 0L), autoplay = false)
    }

    private fun applySpeed() {
        runCatching { mp?.let { if (it.isPlaying) it.playbackParams = it.playbackParams.setSpeed(speed) } }
    }

    fun playNext(t: Track) { if (queue.isEmpty()) playList(listOf(t), 0) else queue.add(qi + 1, t) }
    fun addToQueue(t: Track) { if (queue.isEmpty()) playList(listOf(t), 0) else queue.add(t) }

    fun toggle() {
        val m = mp ?: return
        if (m.isPlaying) { m.pause(); playing = false } else { am.requestAudioFocus(focus); m.start(); playing = true; applySpeed() }
        saveState(); sync()
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
        sources.tick()
        if (playing && ++saveTick >= 80) { saveTick = 0; saveState() }
        if (xfade > 0 && playing && !fading && loop != Loop.ONE) {
            val m = mp
            if (m != null) {
                val d = m.duration
                val left = d - m.currentPosition
                if (d > xfade * 2000 && left in 1..(xfade * 1000 + 250)) { val n = nextIndex(); if (n >= 0) startAt(n) }
            }
        }
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
        handleIntent(intent)
        setContent {
            App((application as GlassApp).player)
        }
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); handleIntent(intent) }
    private fun handleIntent(i: Intent?) { i?.data?.let { (application as GlassApp).player.cloud.onRedirect(it) } }

    override fun onStart() { super.onStart(); (application as GlassApp).player.foreground(true) }
    override fun onStop() { (application as GlassApp).player.foreground(false); super.onStop() }
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
        LaunchedEffect(Unit) { if (p.ui.scanOnLaunch) startScan() }
        var bgTarget by remember { mutableStateOf(false) }
        val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { u ->
            if (u != null) scope.launch {
                val video = ctx.contentResolver.getType(u)?.startsWith("video") == true
                importBg(ctx, u, video, bgTarget)?.let { p.ui.setBg(bgTarget, it, video) }
            }
        }
        val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            p.ui.viz = ok; p.ui.save(); p.attachViz()
        }
        val act = Actions(
            pickBg = { now ->
                bgTarget = now
                bgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo))
            },
            askMic = {
                if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    p.ui.viz = true; p.ui.save(); p.attachViz()
                } else micLauncher.launch(Manifest.permission.RECORD_AUDIO)
            },
            rescan = { startScan() },
            addSongs = { picker.launch(arrayOf("audio/*")) },
        )

        var nav by remember { mutableStateOf(Nav.HOME) }
        var libTab by remember { mutableStateOf(Tab.LISTS) }
        var libOpen by remember { mutableStateOf<String?>(null) }
        var menu by remember { mutableStateOf<Track?>(null) }
        var newPl by remember { mutableStateOf(false) }
        var showNow by remember { mutableStateOf(false) }
        val nowState = remember { MutableTransitionState(false) }
        nowState.targetState = showNow
        val covered = nowState.currentState && nowState.isIdle
        val homeOpened = remember { mutableStateOf<Pair<Tab, String>?>(null) }
        val searchQ = remember { mutableStateOf("") }
        val searchGenre = remember { mutableStateOf<String?>(null) }
        val libQ = remember { mutableStateOf("") }
        BackHandler(showNow) { showNow = false }

        CompositionLocalProvider(LocalGlass provides p.ui.glass, LocalTap provides { p.tap() }) {
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0B14))) {
            if (!covered) Background(p.ui, false, p.accent(p.current))
            Backdrop(p.accent(p.current), p.ui.blobs, if (p.ui.hasBg()) 0.45f else 1f, p.ui.blurDp)
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).padding(top = 8.dp)) {
                    AnimatedContent(
                        nav,
                        transitionSpec = {
                            val dir = if (targetState.ordinal >= initialState.ordinal) 1 else -1
                            (fadeIn(tween(340, delayMillis = 80)) + slideInHorizontally(tween(460, easing = FastOutSlowInEasing)) { dir * it / 10 }) togetherWith
                                (fadeOut(tween(200)) + slideOutHorizontally(tween(460, easing = FastOutSlowInEasing)) { -dir * it / 10 })
                        },
                        label = "screen",
                    ) { n ->
                        when (n) {
                            Nav.HOME -> HomeScreen(p, act, { menu = it }, { newPl = true }, { nav = Nav.SETTINGS }, homeOpened)
                            Nav.SEARCH -> SearchScreen(p, act, { menu = it }, { newPl = true }, searchQ, searchGenre)
                            Nav.LIBRARY -> LibraryScreen(p, act, libTab, { libTab = it }, libOpen, { libOpen = it }, { menu = it }, { newPl = true }, libQ)
                            Nav.SERVICES -> ServicesScreen(p)
                            Nav.SETTINGS -> Column {
                                GlassButton("Back", R.drawable.ic_back) { nav = Nav.HOME }
                                Spacer(Modifier.height(12.dp))
                                SettingsBody(p, act, Modifier.weight(1f))
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    p.current != null,
                    enter = fadeIn(tween(300)) + slideInVertically(tween(380, easing = FastOutSlowInEasing)) { it },
                    exit = fadeOut(tween(200)),
                ) {
                    Box(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) { MiniPlayer(p) { showNow = true } }
                }
                BottomBar(nav) { nav = it }
            }
            AnimatedVisibility(
                visibleState = nowState,
                enter = fadeIn(tween(320)) + slideInVertically(tween(480, easing = FastOutSlowInEasing)) { it / 5 },
                exit = fadeOut(tween(260)) + slideOutVertically(tween(420, easing = FastOutSlowInEasing)) { it / 4 },
            ) { NowPlayingScreen(p) { showNow = false } }
        }
        menu?.let { t -> TrackMenu(p, t, if (nav == Nav.LIBRARY && libTab == Tab.LISTS) libOpen else null) { menu = null } }
        if (newPl) NewPlaylistDialog(p) { newPl = false }
        }
    }
}

@Composable
fun Body(
    p: Player, tab: Tab, open: String?, setOpen: (String?) -> Unit,
    query: String, setQuery: (String) -> Unit, onMenu: (Track) -> Unit, onNew: () -> Unit, act: Actions, modifier: Modifier,
) {
    if (tab == Tab.SETTINGS) { SettingsBody(p, act, modifier); return }
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
            if (tab == Tab.LISTS) item { GlassButton("New playlist", R.drawable.ic_add, onNew) }
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
            GlassButton("Back", R.drawable.ic_back) { setOpen(null) }
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
            val sel = t.uri == p.current?.uri
            Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp))
                    .background(if (sel) Color.White.copy(0.16f) else Color.Transparent)
                    .clickable { onPlay(i) }.padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ArtBox(p, t, Modifier.size(44.dp), RoundedCornerShape(12.dp), 20.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist, color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val fav = p.isFav(t)
                Icon(
                    painterResource(if (fav) R.drawable.ic_heart else R.drawable.ic_heart_outline), null,
                    Modifier.clickable { p.toggleFav(t) }.padding(8.dp).size(22.dp),
                    tint = if (fav) Color(0xFFFF5C8A) else Color.White.copy(0.7f),
                )
                Icon(
                    painterResource(R.drawable.ic_more), null,
                    Modifier.clickable { onMenu(t) }.padding(horizontal = 10.dp, vertical = 8.dp).size(24.dp),
                    tint = Color.White,
                )
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
