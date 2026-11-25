package com.example.glassplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class Actions(val pickBg: (Boolean) -> Unit, val askMic: () -> Unit, val rescan: () -> Unit, val addSongs: () -> Unit)

// Copies the picked image or video into app storage so it keeps working after restarts
suspend fun importBg(ctx: Context, u: Uri, video: Boolean, now: Boolean): String? = withContext(Dispatchers.IO) {
    runCatching {
        val tag = if (now) "now" else "home"
        ctx.filesDir.listFiles()?.filter { it.name.startsWith("bg_$tag") }?.forEach { it.delete() }
        val f = File(ctx.filesDir, "bg_${tag}_${System.currentTimeMillis()}.${if (video) "mp4" else "img"}")
        ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        Uri.fromFile(f).toString()
    }.getOrNull()
}

private fun decodeBg(ctx: Context, u: Uri, max: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / (s * 2) >= max && o.outHeight / (s * 2) >= max) s *= 2
    ctx.contentResolver.openInputStream(u)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
    }
}.getOrNull()

@Composable
fun Background(ui: UiSettings, now: Boolean, accent: Color) {
    val (uri, video) = ui.bg(now) ?: return
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().alpha(ui.bgAlpha)) {
            if (video) key(uri) {
                AndroidView(
                    factory = { c ->
                        VideoView(c).apply {
                            setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                            setVideoURI(Uri.parse(uri))
                            setOnPreparedListener { m ->
                                m.isLooping = true
                                m.setVolume(0f, 0f)
                                val ar = m.videoWidth.toFloat() / m.videoHeight
                                val vr = width.toFloat() / height
                                val s = maxOf(ar / vr, vr / ar)
                                scaleX = s; scaleY = s
                            }
                            start()
                        }
                    },
                    modifier = Modifier.fillMaxSize().blur(ui.bgBlur.dp),
                )
            } else {
                val bmp by produceState<Bitmap?>(null, uri, ui.quality) {
                    value = withContext(Dispatchers.IO) { decodeBg(ctx, Uri.parse(uri), ui.bgMax) }
                }
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().blur(ui.bgBlur.dp), contentScale = ContentScale.Crop) }
            }
        }
        val b = ui.bright
        Box(Modifier.fillMaxSize().background(if (b < 0) Color.Black.copy(-b * 0.9f) else Color.White.copy(b * 0.5f)))
        if (ui.tint > 0f) Box(Modifier.fillMaxSize().background(accent.copy(ui.tint)))
    }
}

@Composable
fun Panel(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
fun Knob(label: String, v: Float, r: ClosedFloatingPointRange<Float>, on: (Float) -> Unit, done: () -> Unit) {
    Column {
        Text(label, color = Color.White.copy(0.8f), fontSize = 13.sp)
        Slider(
            value = v.coerceIn(r.start, r.endInclusive), onValueChange = on, valueRange = r, onValueChangeFinished = done,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.25f)),
        )
    }
}

@Composable
fun Toggle(label: String, v: Boolean, on: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, modifier = Modifier.weight(1f))
        Switch(v, on)
    }
}

@Composable
fun Note(s: String) = Text(s, color = Color.White.copy(0.6f), fontSize = 12.sp)

@Composable
fun ChipRow(content: @Composable RowScope.() -> Unit) =
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
fun NamedList(items: Collection<String>, apply: (String) -> Unit, delete: (String) -> Unit) {
    items.sorted().forEach { n ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(n, color = Color.White, modifier = Modifier.weight(1f), maxLines = 1)
            GlassButton("Apply") { apply(n) }
            Spacer(Modifier.width(8.dp))
            GlassButton("Remove", R.drawable.ic_close) { delete(n) }
        }
    }
}

private val sections = listOf(
    "Appearance", "Backgrounds", "Audio & Equalizer", "Playback", "Notifications",
    "Library", "Gestures", "Storage", "About GlassPlayer",
)

@Composable
fun SettingsBody(p: Player, act: Actions, modifier: Modifier) {
    var sec by remember { mutableStateOf<String?>(null) }
    val s = sec
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s == null) {
            items(sections) { n ->
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).clickable { sec = n }.padding(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(n, color = Color.White, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    Icon(painterResource(R.drawable.ic_chevron_right), null, tint = Color.White.copy(0.6f))
                }
            }
        } else {
            item { GlassButton("Settings", R.drawable.ic_back) { sec = null } }
            item {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (s) {
                        "Appearance" -> Appearance(p)
                        "Backgrounds" -> Backgrounds(p, act)
                        "Audio & Equalizer" -> AudioFx(p, act)
                        "Playback" -> PlaybackSettings(p)
                        "Notifications" -> NotificationSettings(p)
                        "Library" -> LibrarySettings(p, act)
                        "Gestures" -> GestureSettings(p)
                        "Storage" -> StorageSettings(p)
                        else -> About()
                    }
                }
            }
        }
    }
}

@Composable
fun Appearance(p: Player) {
    val ui = p.ui
    var hex by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    Panel("Accent color") {
        ChipRow {
            ui.accents.forEachIndexed { i, (n, _) -> Chip(n, ui.accentIdx == i) { ui.accentIdx = i; ui.save() } }
            Chip("Custom", ui.accentIdx == ui.accents.size) { ui.accentIdx = ui.accents.size; ui.save() }
        }
        OutlinedTextField(hex, { hex = it.take(7) }, singleLine = true, label = { Text("Custom hex, e.g. FF5C8A") })
        GlassButton("Apply custom color") { ui.setCustom(hex) }
        Note("Auto follows each song's album art.")
    }
    Panel("Style") {
        Knob("Glass strength", ui.glass, 0.4f..2f, { ui.glass = it }, ui::save)
        Toggle("Animated color blobs", ui.blobs) { ui.blobs = it; ui.save() }
        Toggle("Vinyl mode (spinning round art)", ui.vinyl) { ui.vinyl = it; ui.save() }
        Text("Visual quality", color = Color.White.copy(0.8f), fontSize = 13.sp)
        ChipRow { listOf("Balanced", "High", "Ultra").forEachIndexed { i, n -> Chip(n, ui.quality == i) { ui.quality = i; ui.save(); p.attachViz() } } }
        Note("Sets blur radius, visualizer detail and background image resolution.")
    }
    Panel("Saved themes") {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Theme name") })
        GlassButton("Save current look") { if (name.isNotBlank()) { ui.saveTheme(name.trim()); name = "" } }
        NamedList(ui.themes.keys, { ui.applyTheme(it) }, { ui.deleteTheme(it) })
    }
}

@Composable
fun Backgrounds(p: Player, act: Actions) {
    val ui = p.ui
    Panel("Home screen") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Choose image or video") { act.pickBg(false) }
            if (ui.homeUri != null) GlassButton("Remove") { ui.setBg(false, null, false) }
        }
    }
    Panel("Now Playing screen") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Choose image or video") { act.pickBg(true) }
            if (ui.nowUri != null) GlassButton("Remove") { ui.setBg(true, null, false) }
        }
        Note("Uses the home background if you leave this empty.")
    }
    Panel("Adjust") {
        Knob("Opacity", ui.bgAlpha, 0.1f..1f, { ui.bgAlpha = it }, ui::save)
        Knob("Brightness (left darker, right lighter)", ui.bright, -1f..1f, { ui.bright = it }, ui::save)
        Knob("Blur", ui.bgBlur, 0f..30f, { ui.bgBlur = it }, ui::save)
        Knob("Accent color overlay", ui.tint, 0f..0.6f, { ui.tint = it }, ui::save)
        Note("Images: PNG, JPG, WebP, GIF (first frame). Video: MP4, looped and muted.")
    }
}

@Composable
fun AudioFx(p: Player, act: Actions) {
    val fx = p.fx
    val ui = p.ui
    var name by remember { mutableStateOf("") }
    Panel("Equalizer") {
        Toggle("Equalizer", fx.on) { fx.setOn(it) }
        if (fx.levels.isEmpty()) Note("Play a song once to unlock the bands.")
        else {
            ChipRow { fx.presets.forEach { (n, c) -> Chip(n, false) { fx.preset(c) } } }
            fx.levels.forEachIndexed { b, lv ->
                Knob(fx.freqs.getOrElse(b) { "Band ${b + 1}" }, lv.toFloat(), fx.min.toFloat()..fx.max.toFloat(), { fx.setBand(b, it.toInt()) }, {})
            }
        }
    }
    Panel("Effects") {
        Knob("Bass boost", fx.bass.toFloat(), 0f..1000f, { fx.setBass(it.toInt()) }, {})
        Knob("Virtualizer (wider sound)", fx.virt.toFloat(), 0f..1000f, { fx.setVirt(it.toInt()) }, {})
        Note("Effects your device doesn't support are skipped.")
    }
    Panel("My EQ profiles") {
        OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Profile name") })
        GlassButton("Save current EQ") { if (name.isNotBlank() && fx.levels.isNotEmpty()) { fx.saveProfile(name.trim()); name = "" } }
        NamedList(fx.profiles.keys, { fx.applyProfile(it) }, { fx.deleteProfile(it) })
    }
    Panel("Visualizer") {
        Toggle("Audio-reactive visualizer", ui.viz) { if (it) act.askMic() else { ui.viz = false; ui.save(); p.attachViz() } }
        Note("Android requires the microphone permission for this API. It reads the player's output only and records nothing. It stops when the app is hidden.")
    }
}

@Composable
fun PlaybackSettings(p: Player) {
    Panel("Crossfade") {
        Knob(if (p.xfade == 0) "Crossfade: off" else "Crossfade: ${p.xfade} s", p.xfade.toFloat(), 0f..12f, { p.setXfade(it.toInt()) }, {})
        Note("Fades the end of a song into the next, including when you skip. The equalizer and visualizer move to the new song when the fade starts.")
    }
    Panel("Speed") { ChipRow { listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> Chip("${s}x", p.speed == s) { p.setSpeed(s) } } } }
    Panel("Sleep timer") { ChipRow { listOf(0, 15, 30, 60).forEach { m -> Chip(if (m == 0) "Off" else "${m} min", p.sleepMin == m) { p.setSleep(m) } } } }
    Panel("Session") {
        Toggle("Resume last queue and position on launch", p.ui.restore) { p.ui.restore = it; p.ui.save() }
        Note("Shuffle, repeat, queue and play-next are on the player screen and the ⋯ menu.")
    }
}

@Composable
fun NotificationSettings(p: Player) {
    Panel("Media notification") {
        Toggle("Show album art", p.ui.notifArt) { p.ui.notifArt = it; p.ui.save(); p.sync() }
        Toggle("Show controls on lock screen", p.ui.lockScreen) { p.ui.lockScreen = it; p.ui.save(); p.sync() }
        Note("Bluetooth and headset buttons work through the media session while a song is loaded.")
    }
}

@Composable
fun LibrarySettings(p: Player, act: Actions) {
    val ui = p.ui
    Panel("Scanning") {
        Toggle("Scan automatically on launch", ui.scanOnLaunch) { ui.scanOnLaunch = it; ui.save() }
        Knob("Ignore songs shorter than ${ui.minSec} s", ui.minSec.toFloat(), 0f..120f, { ui.minSec = it.toInt() }, ui::save)
        GlassButton(if (p.scanning) "Scanning…" else "Scan now") { act.rescan() }
        Note("${p.library.size} songs in library.")
    }
}

@Composable
fun GestureSettings(p: Player) {
    val ui = p.ui
    Panel("Shake") {
        Toggle("Shake to shuffle", ui.shake) { ui.shake = it; ui.save(); p.foreground(true) }
        Knob("Shake strength needed", ui.shakeLevel, 6f..24f, { ui.shakeLevel = it }, ui::save)
    }
    Panel("Haptic feedback") {
        ChipRow { listOf("Off", "Light", "Strong").forEachIndexed { i, n -> Chip(n, ui.haptic == i) { ui.haptic = i; ui.save(); p.tap() } } }
    }
}

@Composable
fun StorageSettings(p: Player) {
    val ctx = LocalContext.current
    var bump by remember { mutableIntStateOf(0) }
    val mb = remember(bump, p.ui.homeUri, p.ui.nowUri) { ctx.filesDir.walk().filter { it.isFile }.sumOf { it.length() } / 1_048_576.0 }
    Panel("Background files") {
        Text("%.1f MB used".format(mb), color = Color.White)
        GlassButton("Clear backgrounds") {
            ctx.filesDir.listFiles()?.filter { it.name.startsWith("bg_") }?.forEach { it.delete() }
            p.ui.setBg(false, null, false); p.ui.setBg(true, null, false); bump++
        }
    }
    Panel("History and settings") {
        GlassButton("Clear play history and mixes") { p.mixer.clear() }
        GlassButton("Reset appearance settings") { p.ui.reset() }
    }
}

@Composable
fun About() {
    Panel("About GlassPlayer") {
        Text("GlassPlayer 1.0", color = Color.White, fontWeight = FontWeight.Medium)
        Note("A liquid-glass music player built with Kotlin and Jetpack Compose. Favorites, playlists, EQ profiles and themes are stored on this device only.")
    }
}
