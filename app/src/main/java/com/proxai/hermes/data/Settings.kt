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

/** Server URL without a trailing slash or /v1, e.g. https://your-desktop.your-tailnet.ts.net */
val HermesConfig.baseUrl: String
    get() = serverUrl.trim().trimEnd('/').removeSuffix("/v1").trimEnd('/')

/** App preferences, stored in the app's private storage. */
class Settings(context: Context) : HermesConfig {
    private val prefs = context.getSharedPreferences("proxai", Context.MODE_PRIVATE)

    /** e.g. https://your-desktop.your-tailnet.ts.net or http://100.x.y.z:8642 */
    override var serverUrl: String
        get() = prefs.getString("server_url", "") ?: ""
        set(v) = prefs.edit().putString("server_url", v.trim()).apply()

    /** The proxai profile's API_SERVER_KEY. */
    override var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(v) = prefs.edit().putString("api_key", v.trim()).apply()

    /** Cloud model (C8, 10-28): picked with buttons. X1-PM (11-16): Sonnet 5.5 unless he picked another, and
     *  always one of ModelRoute.CLOUD_CHOICES (anything else saved reads as Sonnet; anything else set is ignored). */
    override var model: String
        get() = ModelRoute.listed(prefs.getString("cloud_model", null))
        set(v) { if (v.trim() in ModelRoute.CLOUD_NAMES) prefs.edit().putString("cloud_model", v.trim()).apply() }

    /** Speak replies in Prox's voice, made on the PC (falls back to the phone voice). */
    var proxVoice: Boolean
        get() = prefs.getBoolean("prox_voice", prefs.getBoolean("kanye_voice", true))
        set(v) = prefs.edit().putBoolean("prox_voice", v).apply()

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

    /** "cloud" (default) or "local". X1-PM (11-16): "auto" is gone (it sent chat to the local model in server
     *  mode); a saved "auto" reads as "cloud". */
    var modelChoice: String
        get() = if (prefs.getString("model_choice", "cloud") == "local") "local" else "cloud"
        set(v) = prefs.edit().putString("model_choice", if (v == "local") "local" else "cloud").apply()

    /** The local model chat uses when Model is "Local": one of ModelRoute.LOCAL_CHOICES, never typed. */
    var localModel: String
        get() = ModelRoute.listedLocal(prefs.getString("local_model", DEFAULT_LOCAL_MODEL))
        set(v) = prefs.edit().putString("local_model", ModelRoute.listedLocal(v)).apply()

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

    /** Notify about the PC's temperature / load alerts (checked about every 15 min). Off by default
     *  since B5 (10-26): the Monitor app already watches temperatures. */
    var pcAlerts: Boolean
        get() = prefs.getBoolean("pc_alerts", false)
        set(v) = prefs.edit().putBoolean("pc_alerts", v).apply()

    /** Check the PC for notices at all (brief, nudges, apps ready, wiki...). They land in Chat; the
     *  buzz switches below decide which ones also notify. */
    var pcNotices: Boolean
        get() = prefs.getBoolean("pc_notices", true)
        set(v) = prefs.edit().putBoolean("pc_notices", v).apply()

    /** 0.9.9 (B19): PC notices over a live connection ([com.proxai.hermes.notify.PcListener]), within seconds.
     *  Off = only Android's background check, about every 15 minutes. On unless switched off. */
    var pcLive: Boolean
        get() = prefs.getBoolean("pc_live", true)
        set(v) = prefs.edit().putBoolean("pc_live", v).apply()

    // ---- B5 (10-30): what buzzes the phone. Off = quiet, but it still lands in Chat ----

    var buzzReminders: Boolean
        get() = prefs.getBoolean("buzz_reminders", true)
        set(v) = prefs.edit().putBoolean("buzz_reminders", v).apply()

    var buzzCalendar: Boolean
        get() = prefs.getBoolean("buzz_calendar", true)
        set(v) = prefs.edit().putBoolean("buzz_calendar", v).apply()

    var buzzBrief: Boolean
        get() = prefs.getBoolean("buzz_brief", true)
        set(v) = prefs.edit().putBoolean("buzz_brief", v).apply()

    var buzzUpdates: Boolean
        get() = prefs.getBoolean("buzz_updates", true)
        set(v) = prefs.edit().putBoolean("buzz_updates", v).apply()

    fun buzzes(group: Buzz): Boolean = when (group) {
        Buzz.CALENDAR -> buzzCalendar
        Buzz.BRIEF -> buzzBrief
        Buzz.UPDATES -> buzzUpdates
    }

    // ---- S1 (Phase 3, 10-30): the app lock (AppLock) ----

    /** Lock ProxAI: fingerprint or face, app PIN as backup. Only counts once a PIN is set. */
    var appLock: Boolean
        get() = prefs.getBoolean("app_lock", false) && pinHash.isNotBlank()
        set(v) = prefs.edit().putBoolean("app_lock", v).apply()

    /** How long ProxMobile can be out of sight before it locks again; 1 minute by default (Nathan, 10-30). */
    var lockAfterMs: Long
        get() = prefs.getLong("lock_after_ms", 60_000L)
        set(v) = prefs.edit().putLong("lock_after_ms", v).apply()

    /** The app PIN as a salted hash (PinCode.hash); the PIN itself is never stored. */
    var pinHash: String
        get() = prefs.getString("pin_hash", "") ?: ""
        set(v) = prefs.edit().putString("pin_hash", v).apply()

    /** Wrong PINs in a row, and the time before which no PIN is checked (PinAttempts). */
    var pinFails: Int
        get() = prefs.getInt("pin_fails", 0)
        set(v) = prefs.edit().putInt("pin_fails", v).apply()

    var pinWaitUntil: Long
        get() = prefs.getLong("pin_wait_until", 0L)
        set(v) = prefs.edit().putLong("pin_wait_until", v).apply()

    /** A reply typed into a notification while ProxAI was locked; sent after the next unlock. */
    var pendingReply: String
        get() = prefs.getString("pending_reply", "") ?: ""
        set(v) = prefs.edit().putString("pending_reply", v).apply()

    var pendingReplyChannel: String
        get() = prefs.getString("pending_reply_channel", Channel.DM) ?: Channel.DM
        set(v) = prefs.edit().putString("pending_reply_channel", v).apply()

    /** Newest PC notice already shown (numbered on the PC). */
    var lastPcNoticeId: Int
        get() = prefs.getInt("last_pc_notice_id", 0)
        set(v) = prefs.edit().putInt("last_pc_notice_id", v).apply()

    /** Newest PC alert already shown (alerts are numbered on the PC). */
    var lastPcAlertId: Int
        get() = prefs.getInt("last_pc_alert_id", 0)
        set(v) = prefs.edit().putInt("last_pc_alert_id", v).apply()

    /** Auto-compact once one reply's input passes this many tokens. 0 = off. */
    var compactAtTokens: Int
        get() = prefs.getInt("compact_at_tokens", 60_000)
        set(v) = prefs.edit().putInt("compact_at_tokens", v).apply()

    // ---- Prox Dump sync (0.7.1): the proxai-dumpsync server, for apps with the "dumpsync" feature ----

    /** Optional; blank = <server URL>/dumpsync (tailscale serve --set-path /dumpsync). */
    var dumpsyncUrl: String
        get() = prefs.getString("dumpsync_url", "") ?: ""
        set(v) = prefs.edit().putString("dumpsync_url", v.trim()).apply()

    /** ProxAI line (0.9.9): the token from `setup_phone.py --mobile-token`. Stays in ProxMobile, never logged. */
    var lineToken: String
        get() = prefs.getString("line_token", "") ?: ""
        set(v) = prefs.edit().putString("line_token", v.trim()).apply()

    /** ProxAI line: where calls are announced; blank = <server>/phone/call. */
    var lineUrl: String
        get() = prefs.getString("line_url", "") ?: ""
        set(v) = prefs.edit().putString("line_url", v.trim()).apply()

    /** ProxAI line: "No PIN while unlocked" (calls hold the app lock; outgoing ones tell the PC). Off by default. */
    var lineNoPin: Boolean
        get() = prefs.getBoolean("line_no_pin", false)
        set(v) = prefs.edit().putBoolean("line_no_pin", v).apply()

    /** The PC's DUMPSYNC_TOKEN (proxai .env). Stays in ProxMobile; apps never see it. */
    var dumpsyncToken: String
        get() = prefs.getString("dumpsync_token", "") ?: ""
        set(v) = prefs.edit().putString("dumpsync_token", v.trim()).apply()

    /** HUB-APP-ONLY (0.9.15): the PC's second token for Nathan's own presses, sent only with the owner apps' calls
     *  (Prox Hub, BoardRoom). Stays in ProxMobile; apps never see it, and Settings never shows it again once entered. */
    var ownerToken: String
        get() = prefs.getString("owner_token", "") ?: ""
        set(v) = prefs.edit().putString("owner_token", v.trim()).apply()

    /** OWNER-SIGN (0.9.16): SHA-256 of Nathan's owner signing certificate, typed by him in Settings → Owner apps
     *  (lower-case hex, no colons; blank = not pinned). Only an owner app signed with it gets the owner token
     *  (OwnerSign). Shown in Settings; no app can read or set it. A value that isn't a fingerprint is stored blank. */
    var ownerCertPin: String
        get() = prefs.getString(OwnerSign.KEY, "") ?: ""
        set(v) = prefs.edit().putString(OwnerSign.KEY, OwnerSign.normalize(v) ?: "").apply()

    /** VAULT-UNDO-APP (0.9.18): the PC's server_time from the last GET /vault-yes/written (blank: never checked). */
    var vaultYesSince: String
        get() = prefs.getString("vault_yes_since", "") ?: ""
        set(v) = prefs.edit().putString("vault_yes_since", v.trim()).apply()

    /** The vault changes already notified (VaultYes.keysJson). */
    var vaultYesSeen: List<String>
        get() = VaultYes.keysFrom(prefs.getString("vault_yes_seen", "[]") ?: "[]")
        set(v) = prefs.edit().putString("vault_yes_seen", VaultYes.keysJson(v)).apply()

    /** The undoable changes a notification's Undo may point at (VaultYes.pendingJson). */
    var vaultYesPending: List<VaultChange>
        get() = VaultYes.pendingFrom(prefs.getString("vault_yes_pending", "[]") ?: "[]")
        set(v) = prefs.edit().putString("vault_yes_pending", VaultYes.pendingJson(v)).apply()

    val dumpsyncBaseUrl: String
        get() = dumpsyncUrl.trim().trimEnd('/').ifBlank { if (serverUrl.isBlank()) "" else "$baseUrl/dumpsync" }

    // ---- Workbench logins (0.9.3): answer the IDE's login requests. ProxMobile's own; apps never see it ----

    /** The PC's PHONE_KEY (workbench.env), paired by scanning `workbench.py phone-key`. */
    var workbenchKey: String
        get() = prefs.getString("workbench_key", "") ?: ""
        set(v) = prefs.edit().putString("workbench_key", v.trim()).apply()

    /** <server>/workbench (tailscale serve --set-path /workbench). */
    val workbenchBaseUrl: String
        get() = if (serverUrl.isBlank()) "" else "$baseUrl/workbench"

    /** 0.9.4: keep listening, so logins pop up right away (PcListener, was WorkbenchListener). On unless switched off. */
    var workbenchListen: Boolean
        get() = prefs.getBoolean("workbench_listen", true)
        set(v) = prefs.edit().putBoolean("workbench_listen", v).apply()

    /** C4: the channel on screen ("dm" = the main chat). */
    var activeChannel: String
        get() = prefs.getString("active_channel", Channel.DM) ?: Channel.DM
        set(v) = prefs.edit().putString("active_channel", v).apply()

    /** C9: sites Nathan trusts ("nature.com"); their links are labelled Human-verified. */
    var trustedSites: Set<String>
        get() = prefs.getStringSet("trusted_sites", emptySet())?.toSet() ?: emptySet()
        set(v) = prefs.edit().putStringSet("trusted_sites", v).apply()

    /** C7: days a conversation stays in the trash before it's deleted for good; 0 = keep forever. */
    var trashDays: Int
        get() = prefs.getInt("trash_days", 30)
        set(v) = prefs.edit().putInt("trash_days", v).apply()

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
            "Ollama\nqwen\nTailscale\nObsidian\nvault\nbrain dump\nStormTrooper\nProx\nDiscord\nGitHub\nAPI"
        const val DEFAULT_WORD_FIXES = "crime job => cron job\ncrime jobs => cron jobs\nMac interview => mock interview\n" +
            "Mac interviews => mock interviews\nfirewall update => firmware update\nbrain dumb => brain dump"
    }

}
