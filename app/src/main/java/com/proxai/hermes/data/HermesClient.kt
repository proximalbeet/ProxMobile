package com.proxai.hermes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Token counts Hermes reports for one reply (all model calls of the turn added up). */
data class Usage(val promptTokens: Int, val completionTokens: Int)

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    /** The model is thinking (reasoning text, not shown). Sent once each time thinking starts. */
    data object Thinking : StreamEvent
    data class Tool(val id: String, val label: String, val emoji: String, val running: Boolean) : StreamEvent
    data class Done(val sessionId: String?, val usage: Usage? = null, val model: String? = null) : StreamEvent
    /** Hermes's own status lines (`hermes.status`), e.g. "🗜️ Compacting context — …" while it
     *  compacts the session on the PC, then kind "compacted" when it's done. */
    data class Status(val kind: String, val text: String) : StreamEvent
    data class Error(val message: String) : StreamEvent
}

/**
 * Talks to the Hermes gateway's OpenAI-compatible API server (proxai profile).
 * POST /v1/chat/completions with stream=true; X-Hermes-Session-Id keeps the
 * conversation server-side, so each request only carries the newest message.
 */
class HermesClient(private val settings: HermesConfig) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        // Agent turns with tools can run for minutes; the server sends heartbeats every 30s.
        .readTimeout(10, TimeUnit.MINUTES)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String get() = settings.baseUrl

    fun stream(system: String?, user: String, sessionId: String, route: ModelRoute? = null): Flow<StreamEvent> = callbackFlow {
        if (!settings.isConfigured) {
            trySend(StreamEvent.Error("Set your ProxAI server URL and API key in Settings."))
            close(); return@callbackFlow
        }
        val messages = JSONArray()
        if (!system.isNullOrBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("stream", true).put("messages", messages)
        val r = route ?: ModelRoute(settings.model, "", local = false)
        if (r.model.isNotBlank()) body.put("model", r.model)
        // An explicit provider makes Hermes honor the model (a bare model name is ignored).
        if (r.provider.isNotBlank()) body.put("provider", r.provider)

        val request = Request.Builder()
            .url("$baseUrl/v1/chat/completions")
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("X-Hermes-Session-Id", sessionId)
            .header("Accept", "text/event-stream")
            .post(body.toString().toRequestBody(JSON))
            .build()
        val call = http.newCall(request)

        launch(Dispatchers.IO) {
            try {
                call.execute().use { resp ->
                    if (!resp.isSuccessful) {
                        send(StreamEvent.Error(errorText(resp)))
                        return@use
                    }
                    val source = resp.body!!.source()
                    var event: String? = null
                    val data = StringBuilder()
                    var usage: Usage? = null
                    var model: String? = null
                    var thinking = false

                    suspend fun dispatch() {
                        val payload = data.toString()
                        val ev = event
                        event = null
                        data.clear()
                        if (payload.isBlank() || payload == "[DONE]") return
                        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
                        json.optJSONObject("usage")?.let {
                            usage = Usage(it.optInt("prompt_tokens"), it.optInt("completion_tokens"))
                        }
                        json.optString("model").takeIf { it.isNotBlank() }?.let { model = it }
                        if (ev == "hermes.status") {
                            send(StreamEvent.Status(json.optString("kind"), json.optString("text")))
                            return
                        }
                        if (ev == "hermes.tool.progress") {
                            thinking = false
                            send(StreamEvent.Tool(
                                id = json.optString("toolCallId"),
                                label = json.optString("label", json.optString("tool")),
                                emoji = json.optString("emoji", "🛠"),
                                running = json.optString("status") == "running"))
                            return
                        }
                        json.optJSONObject("error")?.let {
                            send(StreamEvent.Error(it.optString("message", "ProxAI error")))
                            return
                        }
                        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val text = delta?.opt("content")
                        if (text is String && text.isNotEmpty()) {
                            thinking = false
                            send(StreamEvent.Delta(text))
                        }
                        // Local qwen streams its thinking first; without this the reply looks frozen.
                        val reasoning = delta?.opt("reasoning_content") ?: delta?.opt("reasoning")
                        if (reasoning is String && reasoning.isNotEmpty() && !thinking) {
                            thinking = true
                            send(StreamEvent.Thinking)
                        }
                    }

                    while (true) {
                        val line = source.readUtf8Line() ?: break
                        when {
                            line.isEmpty() -> dispatch()
                            line.startsWith(":") -> Unit // heartbeat comment
                            line.startsWith("event:") -> event = line.substring(6).trim()
                            line.startsWith("data:") -> {
                                if (data.isNotEmpty()) data.append('\n')
                                data.append(line.substring(5).removePrefix(" "))
                            }
                        }
                    }
                    if (data.isNotEmpty()) dispatch()
                    send(StreamEvent.Done(resp.header("X-Hermes-Session-Id"), usage, model))
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) trySend(StreamEvent.Error(friendly(e)))
            }
            close()
        }
        awaitClose { call.cancel() }
    }

    /** One-shot question (used by generated apps through window.ProxAI.ask). */
    suspend fun ask(prompt: String, sessionId: String, system: String? = null, route: ModelRoute? = null): String {
        val out = StringBuilder()
        var error: String? = null
        stream(system, prompt, sessionId, route).collect {
            when (it) {
                is StreamEvent.Delta -> out.append(it.text)
                is StreamEvent.Error -> error = it.message
                else -> Unit
            }
        }
        error?.let { if (out.isEmpty()) throw IOException(it) }
        return out.toString()
    }

    /** Checks reachability and the API key. Returns a human-readable status. */
    suspend fun testConnection(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            if (baseUrl.isBlank()) error("No server URL set")
            val health = http.newCall(Request.Builder().url("$baseUrl/health").build()).execute().use {
                if (!it.isSuccessful) error("Server answered HTTP ${it.code}")
                JSONObject(it.body!!.string())
            }
            http.newCall(
                Request.Builder().url("$baseUrl/v1/models")
                    .header("Authorization", "Bearer ${settings.apiKey}").build()
            ).execute().use {
                if (it.code == 401 || it.code == 403) error("Reached ProxAI, but the API key was rejected")
                if (!it.isSuccessful) error("Reached ProxAI, but /v1/models gave HTTP ${it.code}")
            }
            "Connected to ${health.optString("platform", "hermes")} ${health.optString("version")}"
        }.recoverCatching { e -> throw IOException(if (e is IOException) friendly(e) else e.message, e) }
    }

    private fun errorText(resp: Response): String {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull()
        return when (resp.code) {
            401, 403 -> "API key rejected (HTTP ${resp.code}). Check Settings."
            else -> msg ?: "ProxAI returned HTTP ${resp.code}"
        }
    }

    private fun friendly(e: IOException): String = when {
        e.message?.contains("Unable to resolve host") == true ->
            "Can't find the server. Is Tailscale connected on this phone?"
        e.message?.contains("failed to connect") == true || e is java.net.ConnectException ->
            "Can't reach ProxAI. Is the PC on and Tailscale connected?"
        e is java.net.SocketTimeoutException -> "ProxAI took too long to answer."
        else -> e.message ?: "Network error"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
