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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun decodeBg(ctx: Context, u: Uri): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1
    while (o.outWidth / (s * 2) >= 1400 && o.outHeight / (s * 2) >= 1400) s *= 2
    ctx.contentResolver.openInputStream(u)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s })
    }
}.getOrNull()

@Composable
fun Background(ui: UiSettings) {
    val uri = ui.bgUri ?: return
    val ctx = LocalContext.current
    Box(Modifier.fillMaxSize()) {
        if (ui.bgVideo) {
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
            val bmp by produceState<Bitmap?>(null, uri) { value = withContext(Dispatchers.IO) { decodeBg(ctx, Uri.parse(uri)) } }
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().blur(ui.bgBlur.dp), contentScale = ContentScale.Crop) }
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(ui.bgDim)))
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
fun SettingsBody(p: Player, pickBg: () -> Unit, modifier: Modifier) {
    val ui = p.ui
    val fx = p.fx
    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel("Background") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Choose image or video", pickBg)
                    if (ui.hasBg()) GlassButton("Remove") { ui.setBackground(null, false) }
                }
                Text("PNG, JPG, WebP and GIF (still frame), or MP4 video.", color = Color.White.copy(0.6f), fontSize = 12.sp)
                Knob("Dim", ui.bgDim, 0f..0.9f, { ui.bgDim = it }, ui::save)
                Knob("Blur", ui.bgBlur, 0f..30f, { ui.bgBlur = it }, ui::save)
            }
        }
        item {
            Panel("Look") {
                Knob("Glass strength", ui.glass, 0.4f..2f, { ui.glass = it }, ui::save)
                Toggle("Animated color blobs", ui.blobs) { ui.blobs = it; ui.save() }
                Toggle("Vinyl mode (spinning round art)", ui.vinyl) { ui.vinyl = it; ui.save() }
                Text("Accent color", color = Color.White.copy(0.8f), fontSize = 13.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.accents.forEachIndexed { i, (n, _) -> Chip(n, ui.accentIdx == i) { ui.accentIdx = i; ui.save() } }
                }
            }
        }
        item {
            Panel("Equalizer") {
                Toggle("Equalizer", fx.on) { fx.setOn(it) }
                if (fx.levels.isEmpty()) {
                    Text("Play a song once to unlock the bands.", color = Color.White.copy(0.6f), fontSize = 12.sp)
                } else {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fx.presets.forEach { (n, c) -> Chip(n, false) { fx.preset(c) } }
                    }
                    fx.levels.forEachIndexed { b, lv ->
                        Knob(fx.freqs.getOrElse(b) { "Band ${b + 1}" }, lv.toFloat(), fx.min.toFloat()..fx.max.toFloat(), { fx.setBand(b, it.toInt()) }, {})
                    }
                }
                Knob("Bass boost", fx.bass.toFloat(), 0f..1000f, { fx.setBass(it.toInt()) }, {})
                Knob("Virtualizer (wider sound)", fx.virt.toFloat(), 0f..1000f, { fx.setVirt(it.toInt()) }, {})
            }
        }
    }
}
