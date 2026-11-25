package com.example.glassplayer

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Nav { HOME, SEARCH, LIBRARY, SETTINGS }

private fun tileColor(name: String) = Color.hsv(name.hashCode().mod(360).toFloat(), 0.55f, 0.75f)
private fun tileBrush(name: String) = Brush.linearGradient(listOf(tileColor(name), Color(0xFF1B1B2F)))

@Composable
fun BottomBar(nav: Nav, onSelect: (Nav) -> Unit) {
    val tap = LocalTap.current
    val tabs = listOf(
        Triple(Nav.HOME, R.drawable.ic_home, "Home"),
        Triple(Nav.SEARCH, R.drawable.ic_search, "Search"),
        Triple(Nav.LIBRARY, R.drawable.ic_library, "Your Library"),
    )
    val idx = tabs.indexOfFirst { it.first == nav }
    var last by remember { mutableIntStateOf(0) }
    if (idx >= 0) last = idx
    val onSel = rememberUpdatedState(onSelect)
    val cur = rememberUpdatedState(nav)

    // The pill's two edges chase the target on different springs, so it stretches like liquid while it travels
    val fast by animateFloatAsState(last.toFloat(), spring(dampingRatio = 0.6f, stiffness = 520f), label = "pillFast")
    val slow by animateFloatAsState(last.toFloat(), spring(dampingRatio = 0.85f, stiffness = 200f), label = "pillSlow")
    val vis by animateFloatAsState(if (idx >= 0) 1f else 0f, tween(260), label = "pillVis")

    BoxWithConstraints(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                // Drag across the bar and the pill follows your finger
                detectHorizontalDragGestures { change, _ ->
                    val i = (change.position.x / (size.width / 3f)).toInt().coerceIn(0, 2)
                    if (tabs[i].first != cur.value) { tap(); onSel.value(tabs[i].first) }
                }
            },
    ) {
        val itemW = maxWidth / 3
        val lo = minOf(fast, slow)
        val hi = maxOf(fast, slow)
        val squash = 1f - 0.12f * (hi - lo).coerceIn(0f, 1f)
        val pill = RoundedCornerShape(50)
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = itemW * lo).width(itemW * (hi - lo + 1f))
                .height(54.dp * squash).alpha(vis).clip(pill)
                .background(Brush.verticalGradient(listOf(Color.White.copy(0.38f), Color.White.copy(0.14f))))
                .border(1.dp, Brush.linearGradient(listOf(Color.White.copy(0.9f), Color.White.copy(0.1f), Color.White.copy(0.5f))), pill),
        )
        Row(Modifier.fillMaxWidth()) {
            tabs.forEach { (n, icon, label) ->
                val on = nav == n
                val c by animateColorAsState(if (on) Color.White else Color.White.copy(0.55f), tween(250), label = "tab")
                val sc by animateFloatAsState(if (on) 1.12f else 1f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessMedium), label = "tabScale")
                Column(
                    Modifier.weight(1f).height(54.dp)
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            if (!on) { tap(); onSelect(n) }
                        },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(painterResource(icon), null, Modifier.size(24.dp).graphicsLayer { scaleX = sc; scaleY = sc }, tint = c)
                    Text(label, color = c, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
    }
}

/** Album art (or a music-note icon on the accent gradient when a song has none) */
@Composable
fun ArtBox(p: Player, t: Track?, modifier: Modifier, shape: Shape, iconSize: Dp = 28.dp) {
    val art by produceState<Bitmap?>(null, t?.uri) { value = t?.let { p.covers.art(it) } }
    Box(
        modifier.clip(shape).background(Brush.linearGradient(listOf(p.accent(t), Color(0xFF1B1B2F)))),
        contentAlignment = Alignment.Center,
    ) {
        val a = art
        if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(painterResource(R.drawable.ic_music_note), null, Modifier.size(iconSize), tint = Color.White.copy(0.8f))
    }
}

@Composable
fun CoverCard(p: Player, name: String, sub: String, cover: Track?, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(132.dp)) {
            ArtBox(p, cover, Modifier.fillMaxSize(), RoundedCornerShape(16.dp), 36.dp)
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(0.65f)))),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, modifier = Modifier.padding(10.dp))
            }
        }
        Text(sub, color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun TrackCard(p: Player, t: Track, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ArtBox(p, t, Modifier.size(132.dp), RoundedCornerShape(16.dp), 40.dp)
        Text(t.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.artist, color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun Shelf(title: String, content: LazyListScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp), content = content)
    }
}

@Composable
fun ShortcutTile(p: Player, name: String, cover: Track?, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).glass(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtBox(p, cover, Modifier.size(56.dp), RoundedCornerShape(0.dp), 22.dp)
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
fun HomeScreen(
    p: Player, act: Actions, onMenu: (Track) -> Unit, onNew: () -> Unit, goSettings: () -> Unit,
    openedState: MutableState<Pair<Tab, String>?>,
) {
    var opened by openedState
    val o = opened
    if (o != null) {
        Body(p, o.first, o.second, { if (it == null) opened = null }, "", {}, onMenu, onNew, act, Modifier.fillMaxSize())
        return
    }
    val mixes = remember(p.library.size, p.mixer.recent.size, p.mixer.plays.size) { p.mixer.mixes(p.library) }
    val recent = remember(p.library.size, p.mixer.recent.size) { p.resolve(p.mixer.recent.toList()).take(20) }
    val hour = remember { java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY) }
    val greet = if (hour < 12) "Good morning" else if (hour < 18) "Good afternoon" else "Good evening"
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(greet, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Ctrl(R.drawable.ic_settings, true) { goSettings() }
            }
        }
        if (p.library.isEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(26.dp)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(if (p.scanning) "Scanning your music…" else p.notice ?: "Your library is empty.", color = Color.White)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        GlassButton("Scan") { act.rescan() }
                        GlassButton("Add songs") { act.addSongs() }
                    }
                }
            }
        } else {
            item {
                val tiles = buildList<Triple<String, Track?, () -> Unit>> {
                    add(Triple("Liked Songs", p.favTracks().firstOrNull(), { opened = Tab.FAV to "Liked Songs" }))
                    mixes.take(5).forEach { (n, l) -> add(Triple(n, l.firstOrNull(), { opened = Tab.MIXES to n })) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (n, c, go) -> ShortcutTile(p, n, c, Modifier.weight(1f), go) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (mixes.isNotEmpty()) item {
                Shelf("Made for you") { items(mixes) { (n, l) -> CoverCard(p, n, "${l.size} songs", l.firstOrNull()) { opened = Tab.MIXES to n } } }
            }
            if (recent.isNotEmpty()) item {
                Shelf("Recently played") { itemsIndexed(recent) { i, t -> TrackCard(p, t) { p.playList(recent, i) } } }
            }
            if (p.playlists.isNotEmpty()) item {
                Shelf("Your playlists") {
                    items(p.playlists.keys.sorted()) { n -> CoverCard(p, n, "${p.playlists[n]?.size ?: 0} songs", p.resolve(p.playlists[n] ?: emptyList()).firstOrNull()) { opened = Tab.LISTS to n } }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    p: Player, act: Actions, onMenu: (Track) -> Unit, onNew: () -> Unit,
    qState: MutableState<String>, genreState: MutableState<String?>,
) {
    var q by qState
    var genre by genreState
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Search", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            q, { q = it; genre = null }, Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text("What do you want to listen to?") },
        )
        val g = genre
        when {
            g != null -> Body(p, Tab.GENRES, g, { genre = it }, "", {}, onMenu, onNew, act, Modifier.weight(1f))
            q.isBlank() -> {
                val genres = remember(p.library.size) {
                    p.library.groupBy { it.genre }.map { it.key to it.value.size }.sortedByDescending { it.second }
                }
                Text("Browse all", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                LazyVerticalGrid(
                    GridCells.Fixed(2), Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    gridItems(genres) { (name, count) ->
                        Box(Modifier.height(84.dp).clip(RoundedCornerShape(14.dp)).background(tileBrush(name)).clickable { genre = name }) {
                            ArtBox(p, p.library.firstOrNull { it.genre == name }, Modifier.align(Alignment.CenterEnd).offset(x = 10.dp).size(64.dp).rotate(18f), RoundedCornerShape(10.dp), 24.dp)
                            Column(Modifier.padding(12.dp)) {
                                Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2)
                                Text("$count songs", color = Color.White.copy(0.7f), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
            else -> {
                val res = p.library.filter {
                    it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true)
                }
                TrackList(p, res, { i -> p.playList(res, i) }, onMenu, "No results for “$q”.")
            }
        }
    }
}

@Composable
fun LibraryScreen(
    p: Player, act: Actions, tab: Tab, setTab: (Tab) -> Unit, open: String?, setOpen: (String?) -> Unit,
    onMenu: (Track) -> Unit, onNew: () -> Unit, qState: MutableState<String>,
) {
    var q by qState
    val tabs = listOf(Tab.LISTS, Tab.FAV, Tab.ARTISTS, Tab.ALBUMS, Tab.GENRES, Tab.SONGS, Tab.QUEUE)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your Library", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            GlassButton(if (p.scanning) "Scanning…" else "Scan", R.drawable.ic_refresh) { act.rescan() }
            Spacer(Modifier.width(8.dp))
            GlassButton("Songs", R.drawable.ic_add) { act.addSongs() }
        }
        ChipRow { tabs.forEach { t -> Chip(t.label, t == tab) { setTab(t); setOpen(null) } } }
        Body(p, tab, open, setOpen, q, { q = it }, onMenu, onNew, act, Modifier.weight(1f))
    }
}
