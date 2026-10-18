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

sealed interface StreamEvent {
    data class Delta(val text: String) : StreamEvent
    data class Tool(val id: String, val label: String, val emoji: String, val running: Boolean) : StreamEvent
    data class Done(val sessionId: String?) : StreamEvent
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

    private val baseUrl: String
        get() = settings.serverUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

    fun stream(system: String?, user: String, sessionId: String): Flow<StreamEvent> = callbackFlow {
        if (!settings.isConfigured) {
            trySend(StreamEvent.Error("Set your Hermes server URL and API key in Settings."))
            close(); return@callbackFlow
        }
        val messages = JSONArray()
        if (!system.isNullOrBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", user))
        val body = JSONObject().put("stream", true).put("messages", messages)
        if (settings.model.isNotBlank()) body.put("model", settings.model)

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

                    suspend fun dispatch() {
                        val payload = data.toString()
                        val ev = event
                        event = null
                        data.clear()
                        if (payload.isBlank() || payload == "[DONE]") return
                        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
                        if (ev == "hermes.tool.progress") {
                            send(StreamEvent.Tool(
                                id = json.optString("toolCallId"),
                                label = json.optString("label", json.optString("tool")),
                                emoji = json.optString("emoji", "🛠"),
                                running = json.optString("status") == "running"))
                            return
                        }
                        json.optJSONObject("error")?.let {
                            send(StreamEvent.Error(it.optString("message", "Hermes error")))
                            return
                        }
                        val delta = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                        val text = delta?.opt("content")
                        if (text is String && text.isNotEmpty()) send(StreamEvent.Delta(text))
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
                    send(StreamEvent.Done(resp.header("X-Hermes-Session-Id")))
                }
            } catch (e: IOException) {
                if (!call.isCanceled()) trySend(StreamEvent.Error(friendly(e)))
            }
            close()
        }
        awaitClose { call.cancel() }
    }

    /** One-shot question (used by generated apps through window.ProxAI.ask). */
    suspend fun ask(prompt: String, sessionId: String, system: String? = null): String {
        val out = StringBuilder()
        var error: String? = null
        stream(system, prompt, sessionId).collect {
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
                if (it.code == 401 || it.code == 403) error("Reached Hermes, but the API key was rejected")
                if (!it.isSuccessful) error("Reached Hermes, but /v1/models gave HTTP ${it.code}")
            }
            "Connected to ${health.optString("platform", "hermes")} ${health.optString("version")}"
        }.recoverCatching { e -> throw IOException(if (e is IOException) friendly(e) else e.message, e) }
    }

    private fun errorText(resp: Response): String {
        val raw = runCatching { resp.body?.string() }.getOrNull().orEmpty()
        val msg = runCatching { JSONObject(raw).getJSONObject("error").getString("message") }.getOrNull()
        return when (resp.code) {
            401, 403 -> "API key rejected (HTTP ${resp.code}). Check Settings."
            else -> msg ?: "Hermes returned HTTP ${resp.code}"
        }
    }

    private fun friendly(e: IOException): String = when {
        e.message?.contains("Unable to resolve host") == true ->
            "Can't find the server. Is Tailscale connected on this phone?"
        e.message?.contains("failed to connect") == true || e is java.net.ConnectException ->
            "Can't reach Hermes. Is the PC on and Tailscale connected?"
        e is java.net.SocketTimeoutException -> "Hermes took too long to answer."
        else -> e.message ?: "Network error"
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
