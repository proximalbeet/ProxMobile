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

    /** Hermes session id; history lives server-side in the proxai state.db. */
    var sessionId: String
        get() = prefs.getString("session_id", null) ?: newSession()
        set(v) = prefs.edit().putString("session_id", v).apply()

    fun newSession(): String {
        val id = "android-" + UUID.randomUUID().toString().substring(0, 13)
        sessionId = id
        return id
    }

}
