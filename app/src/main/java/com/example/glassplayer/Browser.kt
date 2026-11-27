package com.example.glassplayer

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import java.net.URLEncoder

private const val HOME = "https://www.google.com"

/** Holds the in-app browser so the page, history and scroll position survive switching tabs */
class Browser {
    var web: WebView? = null
    var url by mutableStateOf(HOME)
    var progress by mutableFloatStateOf(0f)
    var canBack by mutableStateOf(false)
    var canForward by mutableStateOf(false)

    // A web address opens as is; anything else becomes a Google search
    fun go(input: String) {
        val t = input.trim()
        val target = when {
            t.isEmpty() -> HOME
            t.startsWith("http://") || t.startsWith("https://") -> t
            " " !in t && "." in t -> "https://$t"
            else -> "https://www.google.com/search?q=" + URLEncoder.encode(t, "UTF-8")
        }
        web?.loadUrl(target)
    }
}

fun openInChrome(ctx: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(Intent(view).setPackage("com.android.chrome")) }.onFailure {
        runCatching { ctx.startActivity(view) }
    }
}

private fun makeBrowser(ctx: Context, b: Browser) = WebView(ctx).apply {
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.builtInZoomControls = true
    settings.displayZoomControls = false
    settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    enableGlassDownloads(ctx)
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val scheme = request.url.scheme
            if (scheme == "http" || scheme == "https") return false
            // mailto:, tel:, app links and similar go to whichever app handles them
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return true
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            b.url = url
            b.canBack = view.canGoBack()
            b.canForward = view.canGoForward()
        }
    }
    webChromeClient = object : WebChromeClient() {
        override fun onProgressChanged(view: WebView, newProgress: Int) {
            b.progress = if (newProgress >= 100) 0f else newProgress / 100f
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in request.resources) {
                request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
            } else request.deny()
        }
    }
    loadUrl(b.url)
}

@Composable
fun BrowserScreen(b: Browser) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(b.url) }
    LaunchedEffect(b.url) { text = b.url }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(start = 18.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicTextField(
                text, { text = it }, Modifier.weight(1f).padding(vertical = 14.dp), singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                cursorBrush = SolidColor(Color.White),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { b.go(text) }),
            )
            Ctrl(R.drawable.ic_open, true) { openInChrome(ctx, b.url) }
        }
        Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(30.dp)).padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Ctrl(R.drawable.ic_back, b.canBack) { b.web?.goBack() }
            Ctrl(R.drawable.ic_chevron_right, b.canForward) { b.web?.goForward() }
            Ctrl(R.drawable.ic_refresh, true) { b.web?.reload() }
            Ctrl(R.drawable.ic_home, true) { b.go("") }
        }
        if (b.progress > 0f) {
            Box(Modifier.fillMaxWidth(b.progress).height(2.dp).background(Color.White.copy(0.8f)))
        }
        AndroidView(
            factory = { c ->
                (b.web ?: makeBrowser(c, b).also { b.web = it }).also { (it.parent as? ViewGroup)?.removeView(it) }
            },
            modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(24.dp)),
        )
    }
}

// Shared Chrome-style download handler for GlassPlayer WebViews
fun android.webkit.WebView.enableGlassDownloads(context: Context) {
    setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->

        if (!url.startsWith("https://") && !url.startsWith("http://")) {
            return@setDownloadListener
        }

        val filename = android.webkit.URLUtil.guessFileName(
            url, contentDisposition, mimeType
        )

        val request = android.app.DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(filename)
            setDescription("Downloading with GlassPlayer")
            setNotificationVisibility(
                android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
            )
            setDestinationInExternalPublicDir(
                android.os.Environment.DIRECTORY_DOWNLOADS, filename
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)

            if (!mimeType.isNullOrBlank()) setMimeType(mimeType)
            if (!userAgent.isNullOrBlank()) addRequestHeader("User-Agent", userAgent)

            android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                addRequestHeader("Cookie", it)
            }
        }

        val manager = context.getSystemService(
            Context.DOWNLOAD_SERVICE
        ) as android.app.DownloadManager

        runCatching { manager.enqueue(request) }
            .onFailure {
                android.widget.Toast.makeText(
                    context, "Download failed to start", android.widget.Toast.LENGTH_LONG
                ).show()
            }
    }
}
