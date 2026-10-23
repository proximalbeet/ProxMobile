package com.proxai.hermes.data

import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Apps ProxAI built on the PC (checklist Phase 15, docs/PROXAI-APPS.md). Hermes's `proxai_app` tool
 * has Claude Code build them as APKs; the PC's apps server (server/proxai_apps_server.py, at
 * <server>/apps) lists them and serves the APKs. Read-only from here: building happens through chat.
 */
data class PcBuild(
    val code: Int,
    val version: String,
    val size: Long = 0,
    val sha256: String = "",
    val time: String = "",
    val packageName: String = "",
    val permissions: List<String> = emptyList(),
    val certSha256: String = "",
    val note: String = "",
)

data class PcApp(
    val id: String,
    val name: String,
    val description: String,
    val state: String,          // queued, planning, writing, testing, building, built, failed
    val message: String,
    val latest: PcBuild?,
) {
    val busy: Boolean get() = state in BUSY
    val failed: Boolean get() = state == "failed"

    companion object {
        val BUSY = setOf("queued", "planning", "writing", "testing", "building")

        fun parseList(json: String): List<PcApp> {
            val arr = JSONObject(json).optJSONArray("apps") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let(::parse) }
        }

        fun parse(o: JSONObject): PcApp = PcApp(
            id = o.optString("id"),
            name = o.optString("name").ifBlank { o.optString("id") },
            description = o.optString("description"),
            state = o.optString("state", "unknown"),
            message = o.optString("message"),
            latest = o.optJSONObject("latest")?.let(::parseBuild),
        )

        fun parseBuild(o: JSONObject) = PcBuild(
            code = o.optInt("code"),
            version = o.optString("version"),
            size = o.optLong("size"),
            sha256 = o.optString("sha256"),
            time = o.optString("time"),
            packageName = o.optString("package"),
            permissions = o.optJSONArray("permissions").strings(),
            certSha256 = o.optString("cert_sha256"),
            note = o.optString("note"),
        )

        fun parseBuilds(json: String): List<PcBuild> {
            val arr = JSONObject(json).optJSONArray("builds") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.takeIf { it.optBoolean("available", true) }?.let(::parseBuild)
            }
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }

        /** What the main button on a card should do. */
        fun action(app: PcApp, installedCode: Long?): String = when {
            app.latest == null -> "none"
            installedCode == null -> "install"
            installedCode < app.latest.code -> "update"
            else -> "open"
        }
    }
}

class PcAppsRepo(private val context: Context, private val config: HermesConfig, private val scope: CoroutineScope) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val base get() = config.baseUrl + "/apps"

    private val _apps = MutableStateFlow<List<PcApp>>(emptyList())
    val apps: StateFlow<List<PcApp>> = _apps
    private val _error = MutableStateFlow<String?>(null)
    /** null = fine; otherwise why the list couldn't load (PC off, server not installed, ...). */
    val error: StateFlow<String?> = _error
    private var poller: Job? = null

    private fun get(path: String): Request = Request.Builder().url(base + path)
        .header("Authorization", "Bearer ${config.apiKey}").build()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (!config.isConfigured) return@withContext
        try {
            http.newCall(get("/v1/apps")).execute().use { r ->
                when {
                    r.isSuccessful -> { _apps.value = PcApp.parseList(r.body!!.string()); _error.value = null }
                    r.code == 404 || r.code == 502 -> _error.value = "The PC's apps server isn't set up yet"
                    r.code == 401 -> _error.value = "The PC rejected the API key"
                    else -> _error.value = "Apps server error (HTTP ${r.code})"
                }
            }
        } catch (e: IOException) {
            _error.value = "Can't reach the PC (${e.message ?: "offline"})"
        }
    }

    suspend fun builds(id: String): List<PcBuild> = withContext(Dispatchers.IO) {
        http.newCall(get("/v1/apps/$id")).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            PcApp.parseBuilds(r.body!!.string())
        }
    }

    /** While the Apps tab is open: refresh every 3 s while something builds, else every 30 s. */
    fun watch(active: Boolean) {
        poller?.cancel()
        if (!active) return
        poller = scope.launch {
            while (isActive) {
                refresh()
                delay(if (_apps.value.any { it.busy }) 3_000 else 30_000)
            }
        }
    }

    /** Downloads one build's APK and checks it against the size and SHA-256 the PC reported. */
    suspend fun download(app: PcApp, build: PcBuild): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "pc-apps").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "${app.id}-${build.code}.apk")
        http.newCall(get("/v1/apps/${app.id}/apk?code=${build.code}")).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Download failed (HTTP ${r.code})")
            r.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
        }
        if (build.sha256.isNotBlank() && !sha256(file).equals(build.sha256, ignoreCase = true)) {
            file.delete()
            throw IOException("The download doesn't match what the PC built; try again")
        }
        file
    }

    /** versionCode of the installed app, or null if it isn't installed. */
    fun installedCode(packageName: String): Long? = try {
        if (packageName.isBlank()) null
        else context.packageManager.getPackageInfo(packageName, 0).longVersionCode
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
