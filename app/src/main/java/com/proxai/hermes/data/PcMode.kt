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
        model.isNotBlank() -> "${CLOUD_NAMES[model] ?: model} · cloud"
        else -> "cloud (profile default)"
    }

    companion object {
        val CLOUD_DEFAULT = ModelRoute("", "", local = false)
        const val HAIKU = "claude-haiku-5-5"
        const val SONNET = "claude-sonnet-5-5"
        const val OPUS = "claude-opus-5-5"
        const val FABLE = "claude-fable-5-2"
        /** Ids an older version saved (Settings, channels) -> the id used today. */
        private val RENAMED = mapOf("claude-fable-5-1" to FABLE)
        fun current(model: String): String = RENAMED[model] ?: model
        /** The Settings buttons, cheapest first: the only cloud models the app ever asks for (X1-PM, 11-16). */
        val CLOUD_CHOICES = listOf("Haiku 5.5" to HAIKU, "Sonnet 5.5" to SONNET, "Opus 5.5" to OPUS, "Fable 5.2" to FABLE)
        val CLOUD_NAMES = CLOUD_CHOICES.associate { (name, id) -> id to name }
        /** X1-PM (11-16): chat runs on Sonnet 5.5 (the profile's chat model since X1) unless he picks another. */
        const val CHAT_DEFAULT = SONNET
        /** The local models the app offers for "Local"; the app never sends a typed name. */
        val LOCAL_CHOICES = listOf("Qwen 3.5 35B" to "qwen3.5:35b-a3b", "Qwen 3.5 9B" to "qwen3.5:9b")

        /** A saved or channel model -> one of [CLOUD_CHOICES] (renamed ids read as today's), else [fallback]. */
        fun listed(model: String?, fallback: String = CHAT_DEFAULT): String =
            current(model?.trim().orEmpty()).takeIf { it in CLOUD_NAMES } ?: fallback

        /** A saved local model -> one of [LOCAL_CHOICES], else the first. */
        fun listedLocal(model: String?): String =
            model?.trim().takeIf { m -> LOCAL_CHOICES.any { it.second == m } } ?: LOCAL_CHOICES.first().second

        /** A cloud model, always one of [CLOUD_CHOICES], sent with its provider (Hermes ignores a bare model name). */
        fun cloud(model: String) = ModelRoute(listed(model), "anthropic", local = false)

        /** Settings choice -> route. Pure, so it's unit tested. X1-PM (11-16): only "local" sends chat to the
         *  PC's local model; anything else (an older saved "auto" included) is the cloud model. */
        fun pick(choice: String, localModel: String, localProvider: String, cloudAlias: String): ModelRoute =
            if (choice == "local") ModelRoute(listedLocal(localModel), localProvider, local = true)
            else cloud(cloudAlias)
    }
}

/**
 * Finds out whether the PC is in SERVER or PERSONAL mode, through the phone voice server
 * (server/voice_server.py, Tailscale Serve path /voice):
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
