package com.proxai.hermes.voice

import com.proxai.hermes.data.HermesConfig
import com.proxai.hermes.data.baseUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the Kanye voice service on the PC (server/kanye_voice_server.py), published by
 * Tailscale Serve at <server>/voice. Same voice, reference clip and settings as Hermes on Discord.
 */
class KanyeVoice(
    private val config: HermesConfig,
    private val cacheDir: File,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
    private val voiceUrl get() = config.baseUrl + "/voice"

    /** Ask the PC to load the model now, so it's warm by the time Hermes has answered. */
    fun warmup() {
        if (!config.isConfigured) return
        val req = Request.Builder().url("$voiceUrl/v1/warmup")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post("{}".toRequestBody(JSON)).build()
        http.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {}
            override fun onResponse(call: Call, response: Response) = response.close()
        })
    }

    /** Speaks [text] in the Kanye voice; returns a WAV file the caller deletes after playing. */
    suspend fun synth(text: String, voice: String = "kanye"): File = withContext(Dispatchers.IO) {
        val body = JSONObject().put("text", text).put("voice", voice).toString().toRequestBody(JSON)
        val req = Request.Builder().url("$voiceUrl/v1/tts")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(body).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val msg = runCatching { JSONObject(resp.body!!.string()).optString("error") }.getOrNull()
                throw IOException(when (resp.code) {
                    401 -> "Kanye voice rejected the API key"
                    404 -> "Kanye voice isn't set up on the PC"
                    502 -> "Kanye voice service isn't running on the PC"
                    else -> msg?.takeIf { it.isNotBlank() } ?: "Kanye voice failed (HTTP ${resp.code})"
                })
            }
            val file = File.createTempFile("kanye-", ".wav", cacheDir)
            resp.body!!.byteStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            file
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        // A cold model takes ~9 s to load on the PC; warm sentences take ~1-2 s.
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()

        /**
         * Splits speech into pieces the phone fetches one after another. The first piece is just
         * the first sentence so audio starts quickly; the rest are packed up to [max] chars
         * (Chatterbox gets unstable on long inputs).
         */
        fun chunks(text: String, max: Int = 300): List<String> {
            val sentences = text.trim().split(Regex("(?<=[.!?])\\s+"))
                .flatMap { s -> if (s.length <= max) listOf(s) else splitLong(s, max) }
                .filter { it.isNotBlank() }
            if (sentences.isEmpty()) return emptyList()
            val out = mutableListOf(sentences.first())
            var current = ""
            for (s in sentences.drop(1)) {
                if (current.isNotEmpty() && current.length + s.length + 1 > max) {
                    out += current
                    current = s
                } else {
                    current = if (current.isEmpty()) s else "$current $s"
                }
            }
            if (current.isNotEmpty()) out += current
            return out
        }

        /** A single sentence longer than [max]: break at commas, then at spaces. */
        private fun splitLong(s: String, max: Int): List<String> {
            val out = mutableListOf<String>()
            var current = ""
            for (word in s.split(" ")) {
                if (current.isNotEmpty() && current.length + word.length + 1 > max) {
                    out += current
                    current = word
                } else {
                    current = if (current.isEmpty()) word else "$current $word"
                }
            }
            if (current.isNotEmpty()) out += current
            return out
        }
    }
}
