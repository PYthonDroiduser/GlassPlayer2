package com.example.glassplayer

import android.graphics.Bitmap
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun VizBars(bars: FloatArray, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (bars.isEmpty()) return@Canvas
        val w = size.width / bars.size
        bars.forEachIndexed { i, v ->
            val h = (size.height * v).coerceAtLeast(3f)
            drawRoundRect(color.copy(0.85f), Offset(i * w + w * 0.2f, size.height - h), Size(w * 0.6f, h), CornerRadius(w * 0.3f))
        }
    }
}

@Composable
fun MiniPlayer(p: Player, open: () -> Unit) {
    val t = p.current ?: return
    val art by produceState<Bitmap?>(null, t.uri) { value = p.covers.art(t) }
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).clickable(onClick = open).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(p.accent(t)), contentAlignment = Alignment.Center) {
            val a = art
            if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("♪", color = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(t.artist, color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Ctrl(if (p.playing) "❚❚" else "▶", true) { p.toggle() }
        Ctrl("⏭", false) { p.next() }
    }
}

@Composable
fun NowPlayingScreen(p: Player, close: () -> Unit) {
    val t = p.current
    val accent = p.accent(t)
    val art by produceState<Bitmap?>(null, t?.uri) { value = t?.let { p.covers.art(it) } }
    val spin by rememberInfiniteTransition(label = "spin")
        .animateFloat(0f, 360f, infiniteRepeatable(tween(14000, easing = LinearEasing)), label = "s")
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B14))) {
        Background(p.ui, true, accent)
        Backdrop(accent, p.ui.blobs, if (p.ui.hasBg(true)) 0.45f else 1f, p.ui.blurDp)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Ctrl("⌄", true) { close() }
                Text("Now playing", color = Color.White, modifier = Modifier.weight(1f), textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(46.dp))
            }
            Spacer(Modifier.weight(1f))
            val vinyl = p.ui.vinyl
            Box(
                Modifier.fillMaxWidth(0.8f).aspectRatio(1f)
                    .rotate(if (vinyl && p.playing) spin else 0f)
                    .clip(if (vinyl) CircleShape else RoundedCornerShape(28.dp))
                    .background(Brush.linearGradient(listOf(accent, Color(0xFF1B1B2F)))),
                contentAlignment = Alignment.Center,
            ) {
                val a = art
                if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else Text("♪", color = Color.White.copy(0.85f), fontSize = 84.sp)
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t?.title ?: "Nothing playing", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(t?.artist ?: "", color = Color.White.copy(0.7f), fontSize = 15.sp, maxLines = 1)
                }
                if (t != null) Text(
                    if (p.isFav(t)) "♥" else "♡", color = if (p.isFav(t)) Color(0xFFFF5C8A) else Color.White,
                    fontSize = 28.sp, modifier = Modifier.clickable { p.toggleFav(t) }.padding(8.dp),
                )
            }
            if (p.ui.viz && p.viz.bars.isNotEmpty()) VizBars(p.viz.bars, accent, Modifier.fillMaxWidth().height(48.dp))
            val dur = (t?.durationMs ?: 0L).coerceAtLeast(1L)
            Slider(
                value = (p.position.toFloat() / dur).coerceIn(0f, 1f), onValueChange = { p.seek((it * dur).toLong()) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.White.copy(0.25f)),
            )
            Row(Modifier.fillMaxWidth()) {
                Text(fmt(p.position), color = Color.White.copy(0.7f), fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(fmt(t?.durationMs ?: 0L), color = Color.White.copy(0.7f), fontSize = 12.sp)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Ctrl("⇄", p.shuffle) { p.toggleShuffle() }
                Ctrl("⏮", false) { p.prev() }
                val tap = LocalTap.current
                Box(Modifier.size(72.dp).glass(CircleShape).clickable { tap(); p.toggle() }, contentAlignment = Alignment.Center) {
                    Text(if (p.playing) "❚❚" else "▶", color = Color.White, fontSize = 26.sp)
                }
                Ctrl("⏭", false) { p.next() }
                Ctrl(if (p.loop == Loop.ONE) "↻1" else "↻", p.loop != Loop.OFF) { p.cycleLoop() }
            }
            Spacer(Modifier.height(10.dp))
            ChipRow {
                Chip("${p.speed}x", p.speed != 1f) { p.cycleSpeed() }
                Chip(if (p.sleepMin > 0) "Sleep ${p.sleepMin}m" else "Sleep off", p.sleepMin > 0) { p.cycleSleep() }
                Chip(if (p.xfade > 0) "Crossfade ${p.xfade}s" else "Crossfade off", p.xfade > 0) { p.cycleXfade() }
            }
            Spacer(Modifier.weight(1f))
        }
    }
}
