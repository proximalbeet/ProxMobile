package com.proxai.hermes.data

import okhttp3.Call
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** What [AppResearch.send] did: the PC folder, the app's files there now, how many were already there, bytes. */
data class AppResearchSent(val folder: String, val files: Int, val skipped: Int, val bytesSent: Long, val bytes: Long)

/** A firmware image or other file sent with [AppResearch.sendFile]: its PC folder, the name it's stored under. */
data class AppResearchFileSent(val folder: String, val file: String, val size: Long, val sha256: String)

class AppResearchException(message: String) : IOException(message)

/**
 * P3 (0.9.5, 2026-10-31): sends someone else's app (its APK files) or a firmware image / other file to
 * the PC's apps server (<server>/apps, server/proxai_apps_server.py: /v1/research) for Claude Code with
 * REA. It asks first whether it fits, so a refusal comes with its reason; APK files the PC already has
 * at the same size aren't sent again. Blocking: call it off the main thread.
 */
class AppResearch(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
    companion object {
        val DEFAULT_HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)   // the PC writes the file to disk before it answers
            .build()
        private val APK = "application/vnd.android.package-archive".toMediaType()
        private val BYTES = "application/octet-stream".toMediaType()
        private const val CHUNK = 256L * 1024
        /** The PC takes at most this much in one file. */
        const val FILE_MAX = 1024L * 1024 * 1024
    }

    @Volatile private var call: Call? = null
    @Volatile private var stopped = false

    /** Stops a send that's going (from any thread); it then throws. */
    fun cancel() {
        stopped = true
        call?.cancel()
    }

    /** An app's APK files. [onProgress] gets (bytes sent so far, bytes to send). */
    fun send(app: PhoneApp, onProgress: (Long, Long) -> Unit = { _, _ -> }): AppResearchSent {
        stopped = false
        val there = exchange(get("/${app.packageName}/${app.versionCode}?bytes=${app.bytes}")) { text ->
            val o = ready(text)
            val files = o.optJSONObject("files") ?: JSONObject()
            files.keys().asSequence().associateWith { k -> files.optJSONObject(k)?.optLong("size", -1L) ?: -1L }
        }
        val todo = app.parts.filter { p -> there[p.name] != p.file.length() }
        val toSend = todo.sumOf { it.file.length() }
        var before = 0L
        onProgress(0, toSend)
        for (part in todo) {
            val length = part.file.length()
            val query = "?label=" + enc(app.label) + "&version=" + enc(app.versionName)
            exchange(put("/${app.packageName}/${app.versionCode}/${part.name}$query", body(part.file, APK, before, toSend, onProgress))) { }
            before += length
        }
        return AppResearchSent(app.folder, files = app.parts.size, skipped = app.parts.size - todo.size,
            bytesSent = toSend, bytes = app.bytes)
    }

    /** A firmware image or other file, kept on the PC as app-research/files/<time>-<name>. */
    fun sendFile(name: String, file: File, onProgress: (Long, Long) -> Unit = { _, _ -> }): AppResearchFileSent {
        stopped = false
        val size = file.length()
        if (size > FILE_MAX) throw AppResearchException("That file is over ${FILE_MAX / 1024 / 1024} MB, more than the PC takes")
        exchange(get("/files?bytes=$size")) { ready(it) }
        onProgress(0, size)
        return exchange(put("/files/" + enc(name).replace("+", "%20"), body(file, BYTES, 0, size, onProgress))) { text ->
            val o = JSONObject(text)
            AppResearchFileSent(o.optString("folder").ifBlank { "app-research/files" }, o.getString("file"),
                o.optLong("size", size), o.optString("sha256"))
        }
    }

    /** Streams [f] with progress; per attempt, since OkHttp may write a body again after a retry. */
    private fun body(f: File, type: MediaType, before: Long, total: Long, onProgress: (Long, Long) -> Unit) = object : RequestBody() {
        override fun contentType() = type
        override fun contentLength() = f.length()
        override fun writeTo(sink: BufferedSink) {
            var sent = 0L
            f.source().use { src ->
                while (true) {
                    if (stopped) throw IOException("Cancelled")
                    val n = src.read(sink.buffer, CHUNK)
                    if (n == -1L) break
                    sink.emit()
                    sent += n
                    onProgress(before + sent, total)
                }
            }
        }
    }

    private fun ready(text: String): JSONObject {
        val o = JSONObject(text)
        if (!o.optBoolean("ready", false)) {
            throw AppResearchException(o.optString("error").ifBlank { "The PC can't take it right now" }.replaceFirstChar { it.uppercase() })
        }
        return o
    }

    private fun get(tail: String) = Request.Builder().url(root() + tail).header("Authorization", "Bearer $apiKey").build()

    private fun put(tail: String, body: RequestBody) =
        Request.Builder().url(root() + tail).header("Authorization", "Bearer $apiKey").put(body).build()

    private fun <T> exchange(req: Request, ok: (String) -> T): T {
        if (stopped) throw IOException("Cancelled")
        val c = http.newCall(req)
        call = c
        try {
            c.execute().use { r -> return ok(bodyOf(r)) }
        } catch (e: IOException) {
            if (stopped) throw IOException("Cancelled")
            throw e
        } finally {
            call = null
        }
    }

    /** The body of a good answer, else an [AppResearchException] that says what went wrong in plain words. */
    private fun bodyOf(r: Response): String {
        val text = r.body?.string().orEmpty()
        if (r.isSuccessful) return text
        val said = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
        throw AppResearchException(when (r.code) {
            401 -> "The PC rejected the API key"
            404 -> "The PC's apps server doesn't take apps yet: it needs the P3 update"
            502, 503, 504 -> if (said.isNotBlank()) said.replaceFirstChar { it.uppercase() } else "The PC's apps server isn't answering"
            else -> if (said.isNotBlank()) said.replaceFirstChar { it.uppercase() } else "The PC said HTTP ${r.code}"
        })
    }

    private fun root() = baseUrl.trimEnd('/') + "/apps/v1/research"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}
