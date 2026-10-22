package com.proxai.hermes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** StormTrooper's Phase 10 mode as the phone sees it. */
data class PcMode(
    /** "server", "personal", "unknown" or "offline". */
    val mode: String,
    /** Local model the PC runs in this mode, if it said so. */
    val localModel: String? = null,
    val detail: String = "",
    val checkedAt: Long = System.currentTimeMillis(),
) {
    val isServer get() = mode == "server"
}

/** Which model a request asks Hermes for. Blank model = the proxai profile default (cloud). */
data class ModelRoute(val model: String, val provider: String, val local: Boolean) {
    val label: String get() = when {
        local -> "$model · local"
        model.isNotBlank() -> model
        else -> "cloud (profile default)"
    }

    companion object {
        val CLOUD_DEFAULT = ModelRoute("", "", local = false)

        /** Settings choice + PC mode -> route. Pure, so it's unit tested. */
        fun pick(choice: String, mode: PcMode?, localModel: String, localProvider: String, cloudAlias: String): ModelRoute {
            val local = ModelRoute(mode?.localModel?.takeIf { choice == "auto" && it.isNotBlank() } ?: localModel,
                localProvider, local = true)
            val cloud = ModelRoute(cloudAlias, "", local = false)
            return when (choice) {
                "local" -> local
                "cloud" -> cloud
                else -> if (mode?.isServer == true) local else cloud
            }
        }
    }
}

/**
 * Finds out whether the PC is in SERVER or PERSONAL mode, through the phone voice server
 * (server/kanye_voice_server.py, Tailscale Serve path /voice):
 *  1. `GET /voice/v1/mode` (newer voice server) returns ~/.local/state/proxai/mode.json's mode.
 *  2. Older voice server: `GET /voice/health`. Server mode keeps the voice loaded for a year
 *     (idle_exit_seconds = 31536000); without a mode it exits after 5 minutes.
 *  3. HTTP 502: the voice server is switched off, which only personal mode does.
 * The answer is cached for [MAX_AGE_MS].
 */
class PcModeMonitor(private val config: HermesConfig, private val http: OkHttpClient = DEFAULT_HTTP) {
    private val _state = MutableStateFlow<PcMode?>(null)
    val state: StateFlow<PcMode?> = _state
    private val lock = Mutex()

    /** Cached mode, refreshed when older than [MAX_AGE_MS]. Never takes longer than ~5 s. */
    suspend fun current(force: Boolean = false): PcMode? {
        val cached = _state.value
        if (!force && cached != null && System.currentTimeMillis() - cached.checkedAt < MAX_AGE_MS) return cached
        return withTimeoutOrNull(5_000) { refresh() } ?: cached
    }

    suspend fun refresh(): PcMode? = lock.withLock {
        if (!config.isConfigured) return@withLock null
        val m = withContext(Dispatchers.IO) { runCatching { probe() }.getOrElse { PcMode("offline", detail = it.message ?: "unreachable") } }
        _state.value = m
        m
    }

    private fun probe(): PcMode {
        val base = config.baseUrl + "/voice"
        http.newCall(Request.Builder().url("$base/v1/mode").header("Authorization", "Bearer ${config.apiKey}").build())
            .execute().use { r ->
                if (r.isSuccessful) return parseMode(r.body?.string().orEmpty())
                if (r.code == 502 || r.code == 503) return PcMode("personal", detail = "voice server off")
            }
        http.newCall(Request.Builder().url("$base/health").build()).execute().use { r ->
            if (r.code == 502 || r.code == 503) return PcMode("personal", detail = "voice server off")
            if (!r.isSuccessful) return PcMode("unknown", detail = "voice server HTTP ${r.code}")
            return parseHealth(r.body?.string().orEmpty())
        }
    }

    companion object {
        const val MAX_AGE_MS = 60_000L
        private const val SERVER_IDLE_SECONDS = 86_400

        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()

        fun parseMode(json: String): PcMode {
            val o = JSONObject(json)
            val mode = o.optString("mode").ifBlank { "unknown" }
            val model = o.optString("local_model").takeIf { it.isNotBlank() && it != "null" }
            return PcMode(mode, model, detail = "from the PC")
        }

        fun parseHealth(json: String): PcMode {
            val idle = runCatching { JSONObject(json).optLong("idle_exit_seconds", 0) }.getOrDefault(0)
            return if (idle >= SERVER_IDLE_SECONDS) PcMode("server", detail = "voice kept loaded")
            else PcMode("unknown", detail = "no mode set")
        }
    }
}
