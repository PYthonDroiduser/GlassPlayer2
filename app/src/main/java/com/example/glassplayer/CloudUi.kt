package com.example.glassplayer

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Search box actions, results and the accounts panel, added to the Services list
fun LazyListScope.cloudItems(p: Player, q: String, showAcc: Boolean, toggle: () -> Unit) {
    val c = p.cloud
    item {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GlassButton("Search all", R.drawable.ic_search) { if (q.isNotBlank()) c.search(q) }
            if (c.spConnected) GlassButton("My Spotify library", R.drawable.ic_library) { c.loadSpotifyLibrary() }
            GlassButton("Accounts", R.drawable.ic_settings) { toggle() }
        }
    }
    if (c.busy) item { Note("Working…") }
    c.status?.let { s -> item { Note(s) } }
    items(c.results) { h -> HitRow(c, h) }
    if (showAcc) item { AccountsPanel(c) }
}

@Composable
fun HitRow(c: Cloud, h: Hit) {
    val art by produceState<Bitmap?>(null, h.art) { value = h.art?.let { Net.bitmap(it) } }
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(12.dp)).background(h.svc.color.copy(0.6f)),
            contentAlignment = Alignment.Center,
        ) {
            val a = art
            if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(24.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.title, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${h.svc.label} · ${h.sub}", color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        GlassButton(if (h.svc == Svc.SPOTIFY && h.uri != null && c.spConnected) "Play" else "Open") { c.play(h) }
    }
}

@Composable
fun AccountsPanel(c: Cloud) {
    val ctx = LocalContext.current
    fun web(url: String) = ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel("Spotify (Web API)") {
            OutlinedTextField(c.spotifyId, { c.spotifyId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client ID") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassButton(if (c.spConnected) "Reconnect" else "Connect") { c.spotifyLogin() }
                if (c.spConnected) GlassButton("Disconnect") { c.spotifyLogout() }
                GlassButton("Get a Client ID") { web("https://developer.spotify.com/dashboard") }
            }
            Note("In the Spotify dashboard create an app with Web API ticked and add this redirect URI: glassplayer://spotify-callback. Spotify now requires the app owner to have Premium and allows 5 users. Playing a song needs Premium and an open Spotify app.")
        }
        Panel("YouTube Music (YouTube Data API)") {
            OutlinedTextField(c.ytKey, { c.ytKey = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("API key") })
            GlassButton("Get an API key") { web("https://console.cloud.google.com/apis/library/youtube.googleapis.com") }
            Note("Create a key in Google Cloud with YouTube Data API v3 enabled and no app restriction. Results open in the YouTube Music app. The free quota allows about 100 searches a day.")
        }
        Panel("SoundCloud API") {
            OutlinedTextField(c.scId, { c.scId = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Client ID") })
            OutlinedTextField(
                c.scSecret, { c.scSecret = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Client secret") }, visualTransformation = PasswordVisualTransformation(),
            )
            GlassButton("SoundCloud for Developers") { web("https://developers.soundcloud.com/docs/api") }
            Note("SoundCloud only issues keys to Artist Pro accounts. Without one, use the Open and Search buttons on the service cards instead.")
        }
        GlassButton("Save keys") { c.saveKeys() }
        Note("Keys and sign-in tokens stay on this phone in the app's private storage.")
    }
}
