package com.example.glassplayer

import android.content.Context
import android.content.Intent
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

// Pages that stay inside the in-app browser. Everything else (other sign-in providers, ads) opens in your normal browser.
private fun Svc.hosts() = when (this) {
    Svc.SPOTIFY -> listOf("spotify.com", "spotifycdn.com", "scdn.co")
    Svc.YTMUSIC -> listOf("youtube.com", "google.com", "gstatic.com", "googleusercontent.com")
    Svc.SOUNDCLOUD -> listOf("soundcloud.com", "sndcdn.com")
}

private fun makeWebView(ctx: Context, svc: Svc) = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    // These web players expect a desktop browser; a phone-sized viewport still gives their compact layout
    settings.userAgentString = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val host = request.url.host ?: return false
            if (svc.hosts().any { host == it || host.endsWith(".$it") }) return false
            ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return true
        }
    }
    webChromeClient = object : WebChromeClient() {
        // Needed so the players can use the phone's protected-media (DRM) support
        override fun onPermissionRequest(request: PermissionRequest) {
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else request.deny()
        }
    }
    loadUrl(svc.home)
}

/** Segmented control whose glass pill stretches like liquid as it slides, same motion as the bottom bar */
@Composable
fun LiquidSegments(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val tap = LocalTap.current
    val fast by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.6f, stiffness = 520f), label = "segFast")
    val slow by animateFloatAsState(selected.toFloat(), spring(dampingRatio = 0.85f, stiffness = 200f), label = "segSlow")
    BoxWithConstraints(modifier.glass(RoundedCornerShape(30.dp)).padding(6.dp)) {
        val w = maxWidth / labels.size
        val lo = minOf(fast, slow)
        val hi = maxOf(fast, slow)
        val pill = RoundedCornerShape(50)
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = w * lo).width(w * (hi - lo + 1f))
                .height(40.dp * (1f - 0.12f * (hi - lo).coerceIn(0f, 1f))).clip(pill)
                .background(Brush.verticalGradient(listOf(Color.White.copy(0.38f), Color.White.copy(0.14f))))
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(0.9f), Color.White.copy(0.1f), Color.White.copy(0.5f))), pill),
        )
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Box(
                    Modifier.weight(1f).height(40.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (i != selected) { tap(); onSelect(i) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(l, color = if (i == selected) Color.White else Color.White.copy(0.6f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
fun WebPlayerScreen(start: Svc, close: () -> Unit) {
    val ctx = LocalContext.current
    var svc by remember { mutableStateOf(start) }
    // One page per service is kept, so switching tabs doesn't reload it or stop the music
    val views = remember { mutableMapOf<Svc, WebView>() }
    BackHandler {
        val v = views[svc]
        if (v != null && v.canGoBack()) v.goBack() else close()
    }
    DisposableEffect(Unit) {
        onDispose {
            views.values.forEach { v ->
                runCatching { (v.parent as? ViewGroup)?.removeView(v); v.stopLoading(); v.destroy() }
            }
            views.clear()
        }
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF0B0B14)).systemBarsPadding().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ctrl(R.drawable.ic_back, true) { views[svc]?.let { if (it.canGoBack()) it.goBack() } }
            Ctrl(R.drawable.ic_refresh, true) { views[svc]?.reload() }
            Text(svc.label, color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
            Ctrl(R.drawable.ic_close, true) { close() }
        }
        key(svc) {
            AndroidView(
                factory = {
                    views.getOrPut(svc) { makeWebView(ctx, svc) }.also { (it.parent as? ViewGroup)?.removeView(it) }
                },
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)),
            )
        }
        LiquidSegments(Svc.values().map { it.label }, svc.ordinal, { svc = Svc.values()[it] }, Modifier.fillMaxWidth())
    }
}
