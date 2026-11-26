package com.example.glassplayer

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun ServicesScreen(p: Player) {
    val ctx = LocalContext.current
    val src = p.sources
    var q by remember { mutableStateOf("") }
    var showAcc by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { src.refresh() }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { Text("Services", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold) }
        if (!src.allowed) item {
            Panel("Show what's playing in your music apps") {
                Text(
                    "Allow notification access and GlassPlayer can show and control playback from Spotify, YouTube Music and SoundCloud right here.",
                    color = Color.White.copy(0.85f), fontSize = 14.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassButton("Allow access") {
                        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }
                    GlassButton("Refresh", R.drawable.ic_refresh) { src.refresh() }
                }
                Note("If Android says the setting is restricted: Settings → Apps → GlassPlayer → ⋮ → Allow restricted settings, then try again. GlassPlayer does not read your notifications. It only uses this permission to see media players.")
            }
        }
        item {
            OutlinedTextField(
                q, { q = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search a song or paste a link") },
            )
        }
        cloudItems(p, q, showAcc) { showAcc = !showAcc }
        items(Svc.values().toList()) { s -> ServiceCard(p, s, q) }
        if (src.list.isNotEmpty()) item {
            Text("Playing in other apps", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        items(src.list) { e -> ExtCard(e) }
        item {
            Note("Audio from these apps plays inside the apps themselves, so GlassPlayer's equalizer, crossfade and visualizer only apply to your own music.")
        }
    }
}

@Composable
fun ServiceCard(p: Player, s: Svc, q: String) {
    val ctx = LocalContext.current
    val now = p.sources.list.firstOrNull { it.pkg == s.pkg }
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(22.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(s.color, s.color.copy(0.55f)))),
            contentAlignment = Alignment.Center,
        ) { Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(26.dp), tint = Color.White) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(s.label, color = Color.White, fontWeight = FontWeight.SemiBold)
            val status = when {
                now != null && now.title.isNotEmpty() -> (if (now.playing) "Playing: " else "Paused: ") + now.title
                p.sources.installed(s) -> "Installed"
                else -> "Not installed, opens in the browser"
            }
            Text(status, color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassButton(if (q.isBlank()) "Open" else if (isLink(q)) "Open link" else "Search") { openIn(ctx, s, q) }
    }
}

@Composable
fun ExtCard(e: Ext) {
    val tc = e.c.transportControls
    Column(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val brand = e.svc?.color ?: Color(0xFF5B6CFF)
            Box(
                Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(brand, Color(0xFF1B1B2F)))),
                contentAlignment = Alignment.Center,
            ) {
                val a = e.art
                if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(28.dp), tint = Color.White.copy(0.85f))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(e.label, color = brand, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(e.title.ifEmpty { "Nothing playing" }, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(e.artist, color = Color.White.copy(0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Slider(
            value = if (e.dur > 0) (e.pos.toFloat() / e.dur).coerceIn(0f, 1f) else 0f,
            onValueChange = { if (e.dur > 0) tc.seekTo((it * e.dur).toLong()) },
            enabled = e.dur > 0,
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.25f)),
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Ctrl(R.drawable.ic_prev, false) { tc.skipToPrevious() }
            Ctrl(if (e.playing) R.drawable.ic_pause else R.drawable.ic_play, true) { if (e.playing) tc.pause() else tc.play() }
            Ctrl(R.drawable.ic_next, false) { tc.skipToNext() }
        }
    }
}
