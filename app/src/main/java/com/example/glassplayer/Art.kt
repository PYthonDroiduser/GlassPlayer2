package com.example.glassplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class ArtLoader(private val ctx: Context) {
    private val cache = LruCache<Uri, Bitmap>(80)
    private val missing = HashSet<Uri>()
    val accents = mutableStateMapOf<Uri, Color>()

    fun cached(t: Track): Bitmap? = cache.get(t.uri)

    // ---- Cover art: embedded tag, then system thumbnail, then folder images ----

    suspend fun art(t: Track): Bitmap? {
        cache.get(t.uri)?.let { return it }
        if (t.uri in missing) return null
        val b = withContext(Dispatchers.IO) { loadArt(t) }
        if (b == null) {
            missing.add(t.uri)
        } else {
            cache.put(t.uri, b)
            accents[t.uri] = Color(Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0))
        }
        return b
    }

    private fun loadArt(t: Track): Bitmap? {
        // 1. Cover art embedded in the file's tags
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, t.uri)
            r.embeddedPicture?.let { bytes -> decodeBytes(bytes)?.let { return it } }
        } catch (_: Exception) {
        } finally {
            r.release()
        }
        // 2. Thumbnail from Android's media store
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                return ctx.contentResolver.loadThumbnail(t.uri, Size(512, 512), null)
            } catch (_: Exception) {
            }
        }
        // 3. Image files in the song's folder (cover.jpg, folder.png, .thumbnails/...)
        t.path?.let { path -> folderArt(File(path).parentFile)?.let { return it } }
        return null
    }

    private val coverNames = listOf("cover", "folder", "album", "albumart", "albumartsmall", "front", "artwork")
    private val imageExts = listOf("jpg", "jpeg", "png", "webp")

    private fun folderArt(dir: File?): Bitmap? {
        if (dir == null) return null
        val files = dir.listFiles() ?: return null
        val named = files.firstOrNull { f ->
            f.isFile && f.extension.lowercase() in imageExts &&
                coverNames.any { f.nameWithoutExtension.lowercase().startsWith(it) }
        }
        if (named != null) decodeFile(named)?.let { return it }
        val thumbs = File(dir, ".thumbnails").listFiles()?.filter { it.isFile }?.take(5) ?: return null
        return thumbs.firstNotNullOfOrNull { decodeFile(it) }
    }

    private fun sample(w: Int, h: Int): Int {
        var s = 1
        while (w / (s * 2) >= 512 && h / (s * 2) >= 512) s *= 2
        return s
    }

    private fun decodeFile(f: File): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return@runCatching null
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight) })
    }.getOrNull()

    private fun decodeBytes(b: ByteArray): Bitmap? = runCatching {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(b, 0, b.size, o)
        if (o.outWidth <= 0) return@runCatching null
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = sample(o.outWidth, o.outHeight) })
    }.getOrNull()

}
