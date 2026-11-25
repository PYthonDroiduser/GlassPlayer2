package com.example.glassplayer

import android.content.SharedPreferences
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

class Fx(private val prefs: SharedPreferences) {
    private var eq: Equalizer? = null
    private var bb: BassBoost? = null
    private var vz: Virtualizer? = null
    val levels = mutableStateListOf<Int>().apply {
        (prefs.getString("eq", "") ?: "").split(",").mapNotNull { it.toIntOrNull() }.forEach { add(it) }
    }
    val freqs = mutableStateListOf<String>()
    var min by mutableIntStateOf(-1500)
    var max by mutableIntStateOf(1500)
    var on by mutableStateOf(prefs.getBoolean("eqOn", true))
    var bass by mutableIntStateOf(prefs.getInt("bass", 0))
    var virt by mutableIntStateOf(prefs.getInt("virt", 0))

    val presets = listOf(
        "Flat" to listOf(0f, 0f, 0f, 0f, 0f), "Bass" to listOf(0.8f, 0.5f, 0f, 0f, 0f),
        "Treble" to listOf(0f, 0f, 0f, 0.5f, 0.8f), "Vocal" to listOf(-0.3f, 0f, 0.5f, 0.4f, -0.2f),
        "Rock" to listOf(0.5f, 0.3f, -0.2f, 0.3f, 0.5f), "Electronic" to listOf(0.6f, 0.2f, -0.3f, 0.3f, 0.6f),
        "Loud" to listOf(0.6f, 0.2f, 0.2f, 0.2f, 0.6f),
    )

    private fun save() {
        prefs.edit().putString("eq", levels.joinToString(",")).putBoolean("eqOn", on)
            .putInt("bass", bass).putInt("virt", virt).apply()
    }

    fun attach(session: Int) {
        runCatching { eq?.release(); bb?.release(); vz?.release() }
        runCatching {
            val e = Equalizer(0, session)
            eq = e
            min = e.bandLevelRange[0].toInt(); max = e.bandLevelRange[1].toInt()
            val n = e.numberOfBands.toInt()
            if (freqs.size != n) {
                freqs.clear()
                for (b in 0 until n) {
                    val f = e.getCenterFreq(b.toShort())
                    freqs.add(if (f >= 1_000_000) "${f / 1_000_000}kHz" else "${f / 1000}Hz")
                }
            }
            if (levels.size != n) { levels.clear(); repeat(n) { levels.add(0) } }
            e.enabled = on
            for (b in 0 until n) e.setBandLevel(b.toShort(), levels[b].toShort())
        }
        runCatching { bb = BassBoost(0, session).apply { enabled = bass > 0; if (strengthSupported) setStrength(bass.toShort()) } }
        runCatching { vz = Virtualizer(0, session).apply { enabled = virt > 0; if (strengthSupported) setStrength(virt.toShort()) } }
    }

    fun setBand(b: Int, v: Int) {
        levels[b] = v
        runCatching { eq?.setBandLevel(b.toShort(), v.toShort()) }
        save()
    }

    fun preset(curve: List<Float>) {
        val n = levels.size
        for (b in 0 until n) {
            val c = curve[if (n > 1) Math.round(b * 4f / (n - 1)) else 2]
            setBand(b, (if (c >= 0) c * max else -c * min).toInt())
        }
    }

    fun setOn(v: Boolean) { on = v; runCatching { eq?.enabled = v }; save() }
    fun setBass(v: Int) {
        bass = v
        runCatching { bb?.apply { enabled = v > 0; if (strengthSupported) setStrength(v.toShort()) } }
        save()
    }
    fun setVirt(v: Int) {
        virt = v
        runCatching { vz?.apply { enabled = v > 0; if (strengthSupported) setStrength(v.toShort()) } }
        save()
    }
}

class UiSettings(private val prefs: SharedPreferences) {
    var bgUri by mutableStateOf(prefs.getString("bgUri", null))
    var bgVideo by mutableStateOf(prefs.getBoolean("bgVideo", false))
    var bgDim by mutableFloatStateOf(prefs.getFloat("bgDim", 0.35f))
    var bgBlur by mutableFloatStateOf(prefs.getFloat("bgBlur", 0f))
    var blobs by mutableStateOf(prefs.getBoolean("blobs", true))
    var glass by mutableFloatStateOf(prefs.getFloat("glass", 1f))
    var accentIdx by mutableIntStateOf(prefs.getInt("accent", 0))
    var vinyl by mutableStateOf(prefs.getBoolean("vinyl", false))

    val accents = listOf(
        "Auto" to null, "Crimson" to Color(0xFFE5334B), "Ocean" to Color(0xFF2E7BFF),
        "Mint" to Color(0xFF22D3A0), "Pink" to Color(0xFFFF5C8A), "Gold" to Color(0xFFFFB020),
    )

    fun fixedAccent(): Color? = accents.getOrNull(accentIdx)?.second
    fun hasBg() = bgUri != null
    fun setBackground(u: String?, video: Boolean) { bgUri = u; bgVideo = video; save() }
    fun save() {
        prefs.edit().putString("bgUri", bgUri).putBoolean("bgVideo", bgVideo).putFloat("bgDim", bgDim)
            .putFloat("bgBlur", bgBlur).putBoolean("blobs", blobs).putFloat("glass", glass)
            .putInt("accent", accentIdx).putBoolean("vinyl", vinyl).apply()
    }
}

class Mixer(private val prefs: SharedPreferences) {
    val plays = mutableStateMapOf<String, Int>().apply {
        runCatching {
            val o = JSONObject(prefs.getString("plays", "{}") ?: "{}")
            o.keys().forEach { put(it, o.getInt(it)) }
        }
    }
    val recent = mutableStateListOf<String>().apply {
        addAll((prefs.getString("recent", "") ?: "").split("\n").filter { it.isNotEmpty() })
    }

    fun played(t: Track) {
        val k = t.uri.toString()
        plays[k] = (plays[k] ?: 0) + 1
        recent.remove(k); recent.add(0, k)
        while (recent.size > 50) recent.removeAt(recent.size - 1)
        prefs.edit().putString("plays", JSONObject(plays.toMap()).toString())
            .putString("recent", recent.joinToString("\n")).apply()
    }

    fun mixes(lib: List<Track>): List<Pair<String, List<Track>>> {
        val by = lib.associateBy { it.uri.toString() }
        val rnd = java.util.Random(System.currentTimeMillis() / 86_400_000L)
        val out = mutableListOf<Pair<String, List<Track>>>()
        out += "Most played" to plays.entries.sortedByDescending { it.value }.mapNotNull { by[it.key] }.take(40)
        out += "Recently played" to recent.mapNotNull { by[it] }
        lib.groupBy { it.genre }.filterKeys { !it.startsWith("Unknown") }.entries
            .sortedByDescending { it.value.size }.take(4)
            .forEach { (g, l) -> out += "$g Mix" to l.shuffled(rnd).take(40) }
        lib.groupBy { it.artist }.filterKeys { !it.startsWith("Unknown") }.entries
            .sortedByDescending { it.value.size }.take(3).forEach { (a, l) ->
                val genres = l.map { it.genre }.toSet()
                val extra = lib.filter { it.genre in genres && it.artist != a }.shuffled(rnd).take(25)
                out += "More like $a" to (l + extra).shuffled(rnd)
            }
        out += "Discover" to lib.filter { (plays[it.uri.toString()] ?: 0) == 0 }.shuffled(rnd).take(40)
        out += "Everything, shuffled" to lib.shuffled(rnd)
        return out.filter { it.second.isNotEmpty() }
    }
}
