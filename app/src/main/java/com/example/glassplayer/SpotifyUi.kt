package com.example.glassplayer

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Nav { HOME, SEARCH, LIBRARY, SETTINGS }

private fun tileColor(name: String) = Color.hsv(name.hashCode().mod(360).toFloat(), 0.55f, 0.75f)
private fun tileBrush(name: String) = Brush.linearGradient(listOf(tileColor(name), Color(0xFF1B1B2F)))

@Composable
fun BottomBar(nav: Nav, onSelect: (Nav) -> Unit) {
    val tap = LocalTap.current
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).padding(vertical = 8.dp)) {
        listOf(Triple(Nav.HOME, "⌂", "Home"), Triple(Nav.SEARCH, "⌕", "Search"), Triple(Nav.LIBRARY, "▤", "Your Library"))
            .forEach { (n, glyph, label) ->
                val on = nav == n
                val c = if (on) Color.White else Color.White.copy(0.5f)
                Column(
                    Modifier.weight(1f).clickable { tap(); onSelect(n) }.padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(glyph, color = c, fontSize = 22.sp)
                    Text(label, color = c, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal)
                }
            }
    }
}

@Composable
fun CoverCard(name: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.width(132.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(132.dp).clip(RoundedCornerShape(16.dp)).background(tileBrush(name)), contentAlignment = Alignment.BottomStart) {
            Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 2, modifier = Modifier.padding(10.dp))
        }
        Text(sub, color = Color.White.copy(0.65f), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
fun TrackCard(p: Player, t: Track, onClick: () -> Unit) {
    val art by produceState<Bitmap?>(null, t.uri) { value = p.covers.art(t) }
    Column(Modifier.width(132.dp).clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(132.dp).clip(RoundedCornerShape(16.dp)).background(p.accent(t).copy(0.8f)), contentAlignment = Alignment.Center) {
            val a = art
            if (a != null) Image(a.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Text("♪", color = Color.White, fontSize = 40.sp)
        }
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
fun ShortcutTile(name: String, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier.height(56.dp).glass(RoundedCornerShape(12.dp)).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(56.dp).background(tileBrush(name)), contentAlignment = Alignment.Center) {
            Text(if (name == "Liked Songs") "♥" else "♪", color = Color.White, fontSize = 20.sp)
        }
        Text(name, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 2, modifier = Modifier.padding(horizontal = 10.dp))
    }
}

@Composable
fun HomeScreen(p: Player, act: Actions, onMenu: (Track) -> Unit, onNew: () -> Unit, goSettings: () -> Unit) {
    var opened by remember { mutableStateOf<Pair<Tab, String>?>(null) }
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
                Ctrl("⚙", true) { goSettings() }
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
                val tiles = buildList<Pair<String, () -> Unit>> {
                    add("Liked Songs" to { opened = Tab.FAV to "Liked Songs" })
                    mixes.take(5).forEach { (n, _) -> add(n to { opened = Tab.MIXES to n }) }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tiles.chunked(2).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { (n, go) -> ShortcutTile(n, Modifier.weight(1f), go) }
                            if (row.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
            if (mixes.isNotEmpty()) item {
                Shelf("Made for you") { items(mixes) { (n, l) -> CoverCard(n, "${l.size} songs") { opened = Tab.MIXES to n } } }
            }
            if (recent.isNotEmpty()) item {
                Shelf("Recently played") { itemsIndexed(recent) { i, t -> TrackCard(p, t) { p.playList(recent, i) } } }
            }
            if (p.playlists.isNotEmpty()) item {
                Shelf("Your playlists") {
                    items(p.playlists.keys.sorted()) { n -> CoverCard(n, "${p.playlists[n]?.size ?: 0} songs") { opened = Tab.LISTS to n } }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(p: Player, act: Actions, onMenu: (Track) -> Unit, onNew: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf<String?>(null) }
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
    onMenu: (Track) -> Unit, onNew: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val tabs = listOf(Tab.LISTS, Tab.FAV, Tab.ARTISTS, Tab.ALBUMS, Tab.GENRES, Tab.SONGS, Tab.QUEUE)
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your Library", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            GlassButton(if (p.scanning) "Scanning…" else "Scan") { act.rescan() }
            Spacer(Modifier.width(8.dp))
            GlassButton("＋ Songs") { act.addSongs() }
        }
        ChipRow { tabs.forEach { t -> Chip(t.label, t == tab) { setTab(t); setOpen(null) } } }
        Body(p, tab, open, setOpen, q, { q = it }, onMenu, onNew, act, Modifier.weight(1f))
    }
}
