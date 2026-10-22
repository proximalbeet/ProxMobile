package com.proxai.hermes.data

import android.content.Context
import java.util.UUID

/** What the Hermes client needs to know. */
interface HermesConfig {
    val serverUrl: String
    val apiKey: String
    val model: String
    val isConfigured: Boolean get() = serverUrl.isNotBlank() && apiKey.isNotBlank()
}

/** Server URL without a trailing slash or /v1, e.g. https://desktop.tail773dbb.ts.net */
val HermesConfig.baseUrl: String
    get() = serverUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

/** App preferences, stored in the app's private storage. */
class Settings(context: Context) : HermesConfig {
    private val prefs = context.getSharedPreferences("proxai", Context.MODE_PRIVATE)

    /** e.g. https://desktop.tail773dbb.ts.net or http://100.117.26.105:8642 */
    override var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()

    /** The proxai profile's API_SERVER_KEY. */
    override var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    /** Optional model/route alias; blank lets Hermes use the profile default. */
    override var model: String
        get() = prefs.getString("model", "") ?: ""
        set(v) = prefs.edit().putString("model", v.trim()).apply()

    /** Speak replies in the Kanye voice generated on the PC (falls back to the phone voice). */
    var kanyeVoice: Boolean
        get() = prefs.getBoolean("kanye_voice", true)
        set(v) = prefs.edit().putBoolean("kanye_voice", v).apply()

    var speakReplies: Boolean
        get() = prefs.getBoolean("speak_replies", true)
        set(v) = prefs.edit().putBoolean("speak_replies", v).apply()

    /** Start listening as soon as the power-button overlay opens. */
    var autoListen: Boolean
        get() = prefs.getBoolean("auto_listen", true)
        set(v) = prefs.edit().putBoolean("auto_listen", v).apply()

    /** Keep the conversation going: listen again after Hermes finishes speaking. */
    var handsFree: Boolean
        get() = prefs.getBoolean("hands_free", false)
        set(v) = prefs.edit().putBoolean("hands_free", v).apply()

    // ---- Model routing (v0.3) ----

    /** "auto" = local model while the PC is in SERVER mode, cloud otherwise; "cloud"; "local". */
    var modelChoice: String
        get() = prefs.getString("model_choice", "auto") ?: "auto"
        set(v) = prefs.edit().putString("model_choice", v).apply()

    /** The local Ollama model Hermes runs for the phone in server mode (proxai modes.toml tier1). */
    var localModel: String
        get() = prefs.getString("local_model", DEFAULT_LOCAL_MODEL)?.ifBlank { DEFAULT_LOCAL_MODEL } ?: DEFAULT_LOCAL_MODEL
        set(v) = prefs.edit().putString("local_model", v.trim()).apply()

    /** Hermes provider name for that model (config.yaml custom_providers name). */
    var localProvider: String
        get() = prefs.getString("local_provider", DEFAULT_LOCAL_PROVIDER)?.ifBlank { DEFAULT_LOCAL_PROVIDER } ?: DEFAULT_LOCAL_PROVIDER
        set(v) = prefs.edit().putString("local_provider", v.trim()).apply()

    // ---- Speech input (v0.3) ----

    /** Silence (ms) after you stop talking before a voice message is sent. 0 = only the send button. */
    var sendPauseMs: Long
        get() = prefs.getLong("send_pause_ms", 3000L)
        set(v) = prefs.edit().putLong("send_pause_ms", v).apply()

    /** Words the recognizer should expect (one per line), e.g. cron, firmware, Hermes. */
    var vocabulary: String
        get() = prefs.getString("vocabulary", DEFAULT_VOCABULARY) ?: DEFAULT_VOCABULARY
        set(v) = prefs.edit().putString("vocabulary", v).apply()

    /** "heard => meant" fixes applied to what the recognizer heard, one per line. */
    var wordFixes: String
        get() = prefs.getString("word_fixes", DEFAULT_WORD_FIXES) ?: DEFAULT_WORD_FIXES
        set(v) = prefs.edit().putString("word_fixes", v).apply()

    // ---- Notifications and context (v0.3) ----

    /** Notify when Hermes finishes a reply while the app isn't on screen. */
    var replyNotifications: Boolean
        get() = prefs.getBoolean("reply_notifications", true)
        set(v) = prefs.edit().putBoolean("reply_notifications", v).apply()

    /** Auto-compact once one reply's input passes this many tokens. 0 = off. */
    var compactAtTokens: Int
        get() = prefs.getInt("compact_at_tokens", 60_000)
        set(v) = prefs.edit().putInt("compact_at_tokens", v).apply()

    /** Summary of a compacted session, sent with the first message of the next one. */
    var carryOver: String
        get() = prefs.getString("carry_over", "") ?: ""
        set(v) = prefs.edit().putString("carry_over", v).apply()

    /** Hermes session id; history lives server-side in the proxai state.db. */
    var sessionId: String
        get() = prefs.getString("session_id", null) ?: newSession()
        set(v) = prefs.edit().putString("session_id", v).apply()

    fun newSession(): String {
        val id = "android-" + UUID.randomUUID().toString().substring(0, 13)
        sessionId = id
        return id
    }

    companion object {
        const val DEFAULT_LOCAL_MODEL = "qwen3.5:35b-a3b"
        const val DEFAULT_LOCAL_PROVIDER = "prox-ai"
        const val DEFAULT_VOCABULARY = "ProxAI\nHermes\ncron\ncron job\nfirmware\ncausal\nswerve\nmock interview\n" +
            "Ollama\nqwen\nTailscale\nObsidian\nvault\nbrain dump\nStormTrooper\nKanye\nDiscord\nGitHub\nAPI"
        const val DEFAULT_WORD_FIXES = "crime job => cron job\ncrime jobs => cron jobs\nMac interview => mock interview\n" +
            "Mac interviews => mock interviews\nfirewall update => firmware update\nbrain dumb => brain dump"
    }

}
