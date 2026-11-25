package com.example.glassplayer

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---- Liquid glass ----

val LocalGlass = compositionLocalOf { 1f }

@Composable
fun Modifier.glass(shape: Shape): Modifier {
    val k = LocalGlass.current
    return this
        .clip(shape)
        .background(Brush.verticalGradient(listOf(Color.White.copy(0.26f * k), Color.White.copy(0.07f * k))))
        .border(
            1.dp,
            Brush.linearGradient(listOf(Color.White.copy(0.75f * k), Color.White.copy(0.08f), Color.White.copy(0.3f))),
            shape,
        )
}

@Composable
fun Backdrop(accent: Color, animate: Boolean = true, alpha: Float = 1f) {
    val t = rememberInfiniteTransition(label = "bg")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val c1 by animateColorAsState(accent, tween(900), label = "c1")
    val c2 = lerp(c1, Color(0xFF7C5CFF), 0.6f)
    val c3 = lerp(c1, Color(0xFF00D4FF), 0.5f)
    val pos = if (animate) a else 0.5f
    Canvas(Modifier.fillMaxSize().blur(70.dp).alpha(alpha)) {
        val m = size.minDimension
        drawCircle(c1, m * 0.6f, Offset(size.width * (0.15f + 0.6f * pos), size.height * 0.22f))
        drawCircle(c2, m * 0.55f, Offset(size.width * (0.85f - 0.6f * pos), size.height * 0.6f))
        drawCircle(c3, m * 0.5f, Offset(size.width * (0.3f + 0.4f * pos), size.height * 0.95f))
    }
}

@Composable
fun Ctrl(label: String, active: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(46.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = if (active) Color.White else Color.White.copy(0.55f), fontSize = 22.sp) }
}

@Composable
fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.glass(CircleShape)
            .background(if (selected) Color.White.copy(0.28f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)
    ) { Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun GlassButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier.glass(CircleShape).clickable(onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 10.dp)
    ) { Text(label, color = Color.White, fontWeight = FontWeight.Medium, fontSize = 14.sp) }
}

fun fmt(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}
