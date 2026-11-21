package com.proxai.hermes.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Phase 2, C6 (2026-10-29): images and PDFs ProxAI sends into the chat. Its reply says
 * MEDIA:/output/<file> (the sandbox's outbox); the streamed reply keeps that tag as text, so the
 * phone fetches the file from the apps server (/apps/v1/media, outbox only). The API server's
 * non-streamed fallback inlines images as ![image](data:image/...), which shows the same way.
 */
object ChatMedia {
    sealed interface Part {
        data class Text(val s: String) : Part
        /** [src]: "/output/<file>", "data:image/...;base64,..." or "file:<path>" (an attachment on the phone). */
        data class Image(val src: String) : Part
        data class File(val path: String) : Part
    }

    private val TAG = Regex("""MEDIA:\s*`?(/output/[A-Za-z0-9_][^\s`'"()\[\]<>]*)`?""")
    private val DATA_IMG = Regex("""!\[[^\]]*\]\((data:image/(?:png|jpeg|gif|webp);base64,[A-Za-z0-9+/=\s]+)\)""")
    private val IMAGE_EXT = setOf("png", "jpg", "jpeg", "gif", "webp")
    const val PDF_EXT = "pdf"

    /** The text split around its images and files, in order. Unknown file types stay as text. */
    fun split(text: String): List<Part> {
        val hits = (TAG.findAll(text).map { m ->
            val path = m.groupValues[1].trimEnd('.', ',', ';', ':', '!', '?')
            val ext = path.substringAfterLast('.', "").lowercase()
            val part: Part? = when (ext) {
                in IMAGE_EXT -> Part.Image(path)
                PDF_EXT -> Part.File(path)
                else -> null
            }
            Triple(m.range, part, m.value)
        } + DATA_IMG.findAll(text).map { m -> Triple(m.range, Part.Image(m.groupValues[1].replace(Regex("\\s"), "")), m.value) })
            .filter { it.second != null }.sortedBy { it.first.first }.toList()
        if (hits.isEmpty()) return listOf(Part.Text(text))
        val out = mutableListOf<Part>()
        var i = 0
        for ((range, part, _) in hits) {
            if (range.first < i) continue
            text.substring(i, range.first).takeIf { it.isNotBlank() }?.let { out += Part.Text(it.trim()) }
            out += part!!
            i = range.last + 1
        }
        text.substring(i).takeIf { it.isNotBlank() }?.let { out += Part.Text(it.trim()) }
        return out
    }

    /** The text without media tags or inline images (for speaking it). */
    fun strip(text: String): String = text.replace(TAG, " ").replace(DATA_IMG, " ")

    fun fileName(path: String) = path.substringAfterLast('/')
}

/** Fetches and keeps media for the chat: memory (decoded) and disk (cacheDir/media), so a picture
 *  stays viewable when the PC is off. */
class MediaCache(private val context: Context, private val config: HermesConfig) {
    private val http = OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val dir get() = File(context.cacheDir, "media").apply { mkdirs() }
    private val bitmaps = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** The file on the phone for an outbox path (downloaded once). */
    suspend fun file(path: String): File = withContext(Dispatchers.IO) {
        val name = MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }.take(20) +
            "-" + ChatMedia.fileName(path).replace(Regex("[^A-Za-z0-9._-]"), "_").takeLast(60)
        val f = File(dir, name)
        if (f.isFile && f.length() > 0) return@withContext f
        if (!config.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        val req = Request.Builder().url(config.baseUrl + "/apps/v1/media?path=" + URLEncoder.encode(path, "UTF-8"))
            .header("Authorization", "Bearer ${config.apiKey}").build()
        http.newCall(req).execute().use { r ->
            if (r.code == 404) throw IOException("Not on the PC any more (or the apps server is too old)")
            if (!r.isSuccessful) throw IOException("Couldn't get it from the PC (HTTP ${r.code})")
            val tmp = File(dir, "$name.part")
            r.body!!.byteStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(f)) throw IOException("Couldn't save it")
        }
        f
    }

    /** A picture for [src] (see [ChatMedia.Part.Image]), at most [maxSide] px on its long side. */
    suspend fun bitmap(src: String, maxSide: Int = 1600): Bitmap = bitmaps.get("$src@$maxSide") ?: withContext(Dispatchers.IO) {
        val bytes = when {
            src.startsWith("data:") -> java.util.Base64.getMimeDecoder().decode(src.substringAfter("base64,"))
            src.startsWith("file:") -> File(src.removePrefix("file:")).readBytes()
            else -> file(src).readBytes()
        }
        decode(bytes, maxSide) ?: throw IOException("Not a picture ProxMobile can show")
    }.also { bitmaps.put("$src@$maxSide", it) }

    companion object {
        /** Decodes with the largest power-of-two shrink that keeps the long side >= [maxSide]. */
        fun decode(bytes: ByteArray, maxSide: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
}
