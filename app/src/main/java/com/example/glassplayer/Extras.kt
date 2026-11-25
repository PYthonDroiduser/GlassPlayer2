package com.example.glassplayer

import android.content.Context
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.audiofx.Visualizer
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

    val profiles = mutableStateMapOf<String, String>().apply {
        runCatching {
            val o = JSONObject(prefs.getString("eqp", "{}") ?: "{}")
            o.keys().forEach { put(it, o.getString(it)) }
        }
    }

    private fun save() {
        prefs.edit().putString("eq", levels.joinToString(",")).putBoolean("eqOn", on)
            .putInt("bass", bass).putInt("virt", virt)
            .putString("eqp", JSONObject(profiles.toMap()).toString()).apply()
    }

    fun saveProfile(n: String) { profiles[n] = levels.joinToString(","); save() }
    fun applyProfile(n: String) {
        val v = (profiles[n] ?: return).split(",").mapNotNull { it.toIntOrNull() }
        if (v.size == levels.size) v.forEachIndexed { b, l -> setBand(b, l) }
    }
    fun deleteProfile(n: String) { profiles.remove(n); save() }

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

    fun updateOn(v: Boolean) { on = v; runCatching { eq?.enabled = v }; save() }
    fun updateBass(v: Int) {
        bass = v
        runCatching { bb?.apply { enabled = v > 0; if (strengthSupported) setStrength(v.toShort()) } }
        save()
    }
    fun updateVirt(v: Int) {
        virt = v
        runCatching { vz?.apply { enabled = v > 0; if (strengthSupported) setStrength(v.toShort()) } }
        save()
    }
}

class UiSettings(private val prefs: SharedPreferences) {
    var homeUri by mutableStateOf(prefs.getString("homeUri", null))
    var homeVideo by mutableStateOf(prefs.getBoolean("homeVideo", false))
    var nowUri by mutableStateOf(prefs.getString("nowUri", null))
    var nowVideo by mutableStateOf(prefs.getBoolean("nowVideo", false))
    var bgAlpha by mutableFloatStateOf(prefs.getFloat("bgAlpha", 1f))
    var bright by mutableFloatStateOf(prefs.getFloat("bright", -0.3f))
    var bgBlur by mutableFloatStateOf(prefs.getFloat("bgBlur", 0f))
    var tint by mutableFloatStateOf(prefs.getFloat("tint", 0f))
    var blobs by mutableStateOf(prefs.getBoolean("blobs", true))
    var glass by mutableFloatStateOf(prefs.getFloat("glass", 1f))
    var accentIdx by mutableIntStateOf(prefs.getInt("accent", 0))
    var customAccent by mutableIntStateOf(prefs.getInt("customAccent", 0))
    var vinyl by mutableStateOf(prefs.getBoolean("vinyl", false))
    var quality by mutableIntStateOf(prefs.getInt("quality", 1))
    var viz by mutableStateOf(prefs.getBoolean("viz", false))
    var haptic by mutableIntStateOf(prefs.getInt("haptic", 1))
    var shake by mutableStateOf(prefs.getBoolean("shake", false))
    var shakeLevel by mutableFloatStateOf(prefs.getFloat("shakeLevel", 12f))
    var restore by mutableStateOf(prefs.getBoolean("restore", true))
    var notifArt by mutableStateOf(prefs.getBoolean("notifArt", true))
    var lockScreen by mutableStateOf(prefs.getBoolean("lockScreen", true))
    var scanOnLaunch by mutableStateOf(prefs.getBoolean("scanOnLaunch", true))
    var minSec by mutableIntStateOf(prefs.getInt("minSec", 30))

    val themes = mutableStateMapOf<String, String>().apply {
        runCatching {
            val o = JSONObject(prefs.getString("themes", "{}") ?: "{}")
            o.keys().forEach { put(it, o.getString(it)) }
        }
    }
    val accents = listOf(
        "Auto" to null, "Crimson" to Color(0xFFE5334B), "Ocean" to Color(0xFF2E7BFF),
        "Mint" to Color(0xFF22D3A0), "Pink" to Color(0xFFFF5C8A), "Gold" to Color(0xFFFFB020),
    )

    // Visual quality: Balanced / High / Ultra
    private fun q(a: IntArray) = a[quality.coerceIn(0, 2)]
    val blurDp get() = q(intArrayOf(40, 70, 100))
    val barCount get() = q(intArrayOf(24, 40, 64))
    val vizRateDiv get() = q(intArrayOf(3, 2, 1))
    val bgMax get() = q(intArrayOf(1080, 1600, 2400))

    fun fixedAccent(): Color? =
        if (accentIdx == accents.size) customAccent.takeIf { it != 0 }?.let { Color(it) }
        else accents.getOrNull(accentIdx)?.second

    fun setCustom(hex: String): Boolean {
        val c = runCatching { android.graphics.Color.parseColor("#" + hex.trim().removePrefix("#")) }.getOrNull() ?: return false
        customAccent = c; accentIdx = accents.size; save()
        return true
    }

    fun bg(now: Boolean): Pair<String, Boolean>? =
        (if (now) nowUri?.let { it to nowVideo } else null) ?: homeUri?.let { it to homeVideo }
    fun hasBg(now: Boolean = false) = bg(now) != null
    fun setBg(now: Boolean, uri: String?, video: Boolean) {
        if (now) { nowUri = uri; nowVideo = video } else { homeUri = uri; homeVideo = video }
        save()
    }

    fun saveTheme(n: String) {
        themes[n] = JSONObject().put("a", accentIdx).put("c", customAccent).put("g", glass.toDouble())
            .put("b", blobs).put("v", vinyl).put("q", quality).toString()
        save()
    }
    fun applyTheme(n: String) {
        val o = JSONObject(themes[n] ?: return)
        accentIdx = o.getInt("a"); customAccent = o.getInt("c"); glass = o.getDouble("g").toFloat()
        blobs = o.getBoolean("b"); vinyl = o.getBoolean("v"); quality = o.getInt("q")
        save()
    }
    fun deleteTheme(n: String) { themes.remove(n); save() }

    fun reset() {
        bgAlpha = 1f; bright = -0.3f; bgBlur = 0f; tint = 0f; blobs = true; glass = 1f
        accentIdx = 0; customAccent = 0; vinyl = false; quality = 1; haptic = 1; shakeLevel = 12f
        save()
    }

    fun save() {
        prefs.edit().putString("homeUri", homeUri).putBoolean("homeVideo", homeVideo)
            .putString("nowUri", nowUri).putBoolean("nowVideo", nowVideo)
            .putFloat("bgAlpha", bgAlpha).putFloat("bright", bright).putFloat("bgBlur", bgBlur).putFloat("tint", tint)
            .putBoolean("blobs", blobs).putFloat("glass", glass).putInt("accent", accentIdx)
            .putInt("customAccent", customAccent).putBoolean("vinyl", vinyl).putInt("quality", quality)
            .putBoolean("viz", viz).putInt("haptic", haptic).putBoolean("shake", shake)
            .putFloat("shakeLevel", shakeLevel).putBoolean("restore", restore).putBoolean("notifArt", notifArt)
            .putBoolean("lockScreen", lockScreen).putBoolean("scanOnLaunch", scanOnLaunch).putInt("minSec", minSec)
            .putString("themes", JSONObject(themes.toMap()).toString()).apply()
    }
}

/** Audio-reactive spectrum. Only attached while the app is on screen and the setting is on. */
class Viz {
    var bars by mutableStateOf(FloatArray(0))
    private var v: Visualizer? = null

    fun attach(session: Int, count: Int, rateDiv: Int) {
        release()
        runCatching {
            val x = Visualizer(session)
            x.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            x.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, r: Int) {}
                override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, r: Int) {
                    if (fft == null || fft.size < 8) return
                    val n = fft.size / 2
                    val old = bars
                    bars = FloatArray(count) { i ->
                        val lo = Math.pow(n.toDouble(), i.toDouble() / count).toInt().coerceIn(1, n - 1)
                        val hi = Math.pow(n.toDouble(), (i + 1.0) / count).toInt().coerceIn(lo + 1, n)
                        var m = 0f
                        for (k in lo until hi) {
                            val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                            m = maxOf(m, kotlin.math.sqrt(re * re + im * im))
                        }
                        maxOf(old.getOrElse(i) { 0f } * 0.82f, (m / 70f).coerceIn(0f, 1f))
                    }
                }
            }, Visualizer.getMaxCaptureRate() / rateDiv, false, true)
            x.enabled = true
            v = x
        }
    }

    fun release() {
        runCatching { v?.enabled = false; v?.release() }
        v = null
        bars = FloatArray(0)
    }
}

class Shake(ctx: Context, private val level: () -> Float, private val onShake: () -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private var last = 0L
    fun start() { sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) } }
    fun stop() { sm?.unregisterListener(this) }
    override fun onSensorChanged(e: SensorEvent) {
        val g = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) - 9.81f
        val now = System.currentTimeMillis()
        if (g > level() && now - last > 1200) { last = now; onShake() }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
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

    fun clear() {
        plays.clear(); recent.clear()
        prefs.edit().remove("plays").remove("recent").apply()
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
