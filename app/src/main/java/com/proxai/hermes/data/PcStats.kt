package com.proxai.hermes.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.proxai.hermes.ProxApp
import com.proxai.hermes.notify.Notifier
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.sync.withLock

/**
 * The PC's own stats (checklist Phase 14 "Thermal guard"): the thermal watch on the PC
 * (server/proxai_thermal_watch.py) samples GPU/CPU/RAM every second and logs alerts; the apps
 * server publishes them at <server>/apps/v1/pc/... This shows the alerts as notifications and
 * hands the stats to ProxAI-built apps that use the "pc-stats" feature.
 */
data class PcAlert(val id: Int, val time: String, val level: String, val kind: String, val msg: String) {
    val critical get() = level == "CRIT"
    /** What its notification (and the chat, when it's tapped) calls it. */
    val title get() = if (critical) "🔥 PC critical" else "⚠️ PC alert"

    companion object {
        fun parseList(json: String): List<PcAlert> {
            val arr = JSONObject(json).optJSONArray("alerts") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    PcAlert(it.optInt("id"), it.optString("time"), it.optString("level"), it.optString("kind"), it.optString("msg"))
                }
            }
        }

        /**
         * Which alerts to show. First check ever (lastSeen 0): only the last 30 minutes, so an
         * install doesn't replay old alerts. Returns (to show, new lastSeen).
         */
        fun toNotify(alerts: List<PcAlert>, lastSeen: Int, now: LocalDateTime): Pair<List<PcAlert>, Int> {
            val newest = alerts.maxOfOrNull { it.id } ?: lastSeen
            val fresh = alerts.filter { it.id > lastSeen }
            val show = if (lastSeen > 0) fresh else fresh.filter {
                try { LocalDateTime.parse(it.time).isAfter(now.minusMinutes(30)) } catch (e: DateTimeParseException) { false }
            }
            return show to maxOf(newest, lastSeen)
        }
    }
}

class PcStatsClient(private val config: HermesConfig) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    /** Grading, question writing and coach answers take a few seconds (up to ~1 min when cold). */
    private val slowHttp = http.newBuilder().readTimeout(150, TimeUnit.SECONDS).build()

    companion object {
        val TUTOR_ROUTE = Regex("^/[a-z0-9][a-z0-9/-]{0,120}$")
    }

    private fun get(path: String): String {
        if (!config.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        val req = Request.Builder().url(config.baseUrl + "/apps" + path)
            .header("Authorization", "Bearer ${config.apiKey}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException(
                if (r.code == 404 || r.code == 502) "The PC's stats aren't set up yet" else "PC stats error (HTTP ${r.code})")
            return r.body!!.string()
        }
    }

    /** F1 (0.9.9): the question the PC is waiting on for one of these chats, or null (also on an older PC). */
    fun approval(sessions: List<String>): Approval? {
        val ids = sessions.filter { it.isNotBlank() && it.length <= 120 }.distinct().take(8)
        if (ids.isEmpty() || !config.isConfigured) return null
        val query = ids.joinToString("&") { "session=" + java.net.URLEncoder.encode(it, "UTF-8") }
        val req = Request.Builder().url(config.baseUrl + "/apps/v1/approvals?$query")
            .header("Authorization", "Bearer ${config.apiKey}").build()
        http.newCall(req).execute().use { r ->
            return if (r.isSuccessful) Approval.parse(r.body!!.string()) else null
        }
    }

    /** WAKE-PHONE round 2: like [approval], but a PC that can't answer throws instead of looking like "none". */
    fun approvalOrThrow(sessions: List<String>): Approval? {
        val ids = sessions.filter { it.isNotBlank() && it.length <= 120 }.distinct().take(8)
        if (!config.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        if (ids.isEmpty()) throw IOException("No session to ask about")
        val query = ids.joinToString("&") { "session=" + java.net.URLEncoder.encode(it, "UTF-8") }
        val req = Request.Builder().url(config.baseUrl + "/apps/v1/approvals?$query")
            .header("Authorization", "Bearer ${config.apiKey}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Approvals error (HTTP ${r.code})")
            val body = r.body?.string().orEmpty()
            // A body that isn't {"approval": ...} at all can't be told apart from "none": it counts as no answer.
            val o = runCatching { JSONObject(body) }.getOrNull()
            if (o == null || !o.has("approval")) throw IOException("Approvals: not an answer")
            return Approval.parse(body)
        }
    }

    /** {"latest": {...}, "age_s", "watching", "history": [per-minute peaks], "alerts": [...]} */
    fun stats(): String = get("/v1/pc/stats")

    fun alertsAfter(id: Int): List<PcAlert> = PcAlert.parseList(get("/v1/pc/alerts?after=$id"))

    fun noticesAfter(id: Int): List<PcNotice> = PcNotice.parseList(get("/v1/pc/notices?after=$id"))

    /** B19 (0.9.9): the listener's long poll. The PC answers as soon as a notice with id > [id] lands, or with
     *  none after [waitSeconds] (an older PC answers at once). */
    fun waitForNotices(id: Int, waitSeconds: Int): List<PcNotice> {
        if (!config.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        val longer = http.newBuilder().readTimeout((waitSeconds + 15).toLong(), TimeUnit.SECONDS).build()
        val req = Request.Builder().url(config.baseUrl + "/apps/v1/pc/notices?after=$id&wait=$waitSeconds")
            .header("Authorization", "Bearer ${config.apiKey}").build()
        longer.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("PC notices error (HTTP ${r.code})")
            return PcNotice.parseList(r.body!!.string())
        }
    }

    /** The scout's gig board (Hermes proxai-scout): {"items": [...], "statuses": {"gigs": [...], "ideas": [...]}}. */
    fun scout(): String = get("/v1/scout")

    /** Queue a gig-board change on the PC ({"id", "status"?, "notes"?, "hours"?, "block"?}); the PC applies it
     *  within a minute. Returns the server's JSON; throws with its error message when refused. */
    fun scoutUpdate(json: String): String {
        if (!config.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        if (json.length > 8192) throw IOException("That change is too big")
        val req = Request.Builder().url(config.baseUrl + "/apps/v1/scout/update")
            .header("Authorization", "Bearer ${config.apiKey}")
            .post(json.toRequestBody("application/json".toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException(
                runCatching { JSONObject(body).optString("error") }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "The PC refused the change (HTTP ${r.code})")
            return body
        }
    }

    /** The Study Coach (Hermes plugin proxai-tutor) through the apps server: [arg] is
     *  {"method": "GET"|"POST", "route": "/notebooks/...", "body": {...}}. Returns the plugin's JSON,
     *  including {"ok": false, "error"} answers (the app shows those). */
    fun tutor(arg: String): String {
        if (!config.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        val o = runCatching { JSONObject(arg) }.getOrElse { throw IOException("Bad Study Coach request") }
        val method = o.optString("method", "GET").uppercase()
        val route = o.optString("route")
        if (method !in setOf("GET", "POST") || !TUTOR_ROUTE.matches(route) || "//" in route)
            throw IOException("Bad Study Coach route")
        val b = Request.Builder().url(config.baseUrl + "/apps/v1/tutor" + route)
            .header("Authorization", "Bearer ${config.apiKey}")
        if (method == "POST") b.post((o.optJSONObject("body") ?: JSONObject()).toString().toRequestBody("application/json".toMediaType()))
        slowHttp.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (r.code == 404 && !body.contains("\"ok\"")) throw IOException("The PC's apps server is too old for the Study Coach")
            if (body.isBlank()) throw IOException("Study Coach error (HTTP ${r.code})")
            return body
        }
    }

    /** The conversation's real size on the PC (latest model call), or null if unknown. */
    fun context(session: String): PcContext? = PcContext.parse(get("/v1/pc/context?session=" +
        java.net.URLEncoder.encode(session, "UTF-8")))
}

/** A notice from the PC (Hermes proxai-schedule's feed): scheduled-job results, app ready, wiki waiting.
 *  NOTICE-QUOTE (0.9.15): [noQuote] (the PC's "no_quote": true) keeps the body out of what reaches the model;
 *  [decision] and [next] (a decision or next step, when the notice has one) go along with a quoted notice. */
data class PcNotice(
    val id: Int, val time: String, val kind: String, val title: String, val text: String,
    val noQuote: Boolean = false, val decision: String = "", val next: String = "",
    /** "thread" = chat only: no notification, nothing on the lock screen (#51). */
    val tier: String = "",
) {
    val chatOnly: Boolean get() = tier == THREAD

    companion object {
        const val THREAD = "thread"

        fun parseList(json: String): List<PcNotice> {
            val arr = JSONObject(json).optJSONArray("notices") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    PcNotice(it.optInt("id"), it.optString("time"), it.optString("kind"), it.optString("title"), it.optString("text"),
                        noQuote = ChatNotices.noQuoteOf(it.opt("no_quote")), decision = it.plain("decision"),
                        next = it.plain("next"), tier = (it.opt("tier") as? String).orEmpty().trim().take(20))
                }
            }
        }

        /** A text field, "" when absent or JSON null (Android's optString gives "null" for a null). */
        private fun JSONObject.plain(k: String): String = if (isNull(k)) "" else optString(k).trim()

        /** Same rule as alerts: the very first check shows only the last 30 minutes. */
        fun toNotify(notices: List<PcNotice>, lastSeen: Int, now: LocalDateTime): Pair<List<PcNotice>, Int> {
            val newest = notices.maxOfOrNull { it.id } ?: lastSeen
            val fresh = notices.filter { it.id > lastSeen }
            val show = if (lastSeen > 0) fresh else fresh.filter {
                try { LocalDateTime.parse(it.time).isAfter(now.minusMinutes(30)) } catch (e: DateTimeParseException) { false }
            }
            return show to maxOf(newest, lastSeen)
        }
    }
}

/** What the model actually read on its latest call, from Hermes's proxai-context plugin. */
data class PcContext(val tokens: Int, val window: Int) {
    companion object {
        /** A session match, or the newest call when Hermes renamed the session (compaction) and it's fresh. */
        fun parse(body: String): PcContext? = runCatching {
            val o = org.json.JSONObject(body)
            val match = o.optString("match")
            val fresh = o.has("age_s") && !o.isNull("age_s") && o.optInt("age_s", 9999) <= 120
            val tokens = o.optInt("prompt_tokens")
            if (tokens <= 0 || (match != "session" && !(match == "latest" && fresh))) null
            else PcContext(tokens, o.optInt("context_length"))
        }.getOrNull()
    }
}

/**
 * New PC notices (Phase 2, C1): the chat kinds (brief, nudges, scout...) go into the chat as ProxAI
 * messages; every notice gets a notification, except one that just landed in the chat on screen.
 * Called by [PcAlertWorker] (every ~15 min) and by the chat screen (every minute while it's open).
 */
object NoticeSync {
    private val lock = kotlinx.coroutines.sync.Mutex()

    suspend fun pull(ctx: Context) = lock.withLock {
        val app = ProxApp.instance
        val s = app.settings
        if (!s.pcNotices || !s.isConfigured) return@withLock
        val seen = s.lastPcNoticeId
        val fresh = try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PcStatsClient(s).noticesAfter(seen) }
        } catch (e: IOException) {
            return@withLock   // PC off or away from the tailnet: try again next round
        }
        val (heard, last) = PcNotice.toNotify(fresh, seen, LocalDateTime.now())
        val all = PcPresenceFeed.shown(heard)   // WAKE-PHONE: the PC's "desk" / "away" word is never shown
        // 0.9.11: Prox Hub's notices belong to the Prox Hub app, which shows them under its own name; ProxMobile
        // only carries them. With no Prox Hub app installed they show here as before, so nothing is lost.
        val apps = if (all.any { it.kind == HubRelay.KIND }) HubRelay.apps(ctx) else emptyList()
        val (toApp, show) = HubRelay.split(all, apps.isNotEmpty())
        if (toApp.isNotEmpty()) HubRelay.send(ctx, apps, toApp)
        // B5: the switches in Settings pick what buzzes; a quiet notice still lands in the chat.
        // Round 4: what a notification's tap or Reply may put in the chat, looked up by its key (NoticeStore).
        NoticeStore.of(ctx).put(show.map { NoticeStore.entry(it) })
        val (buzz, quiet) = NoticeBuzz.split(show) { s.buzzes(it) }
        val quietIds = quiet.map { it.id }.toSet()
        val toChat = show.filter { it.kind in ChatNotices.CHAT_KINDS || it.id in quietIds || it.chatOnly }.takeLast(20)
        val inChat = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            toChat.filter { app.chat.addNotice(ChatNotices.key(it), it.kind, it.title, it.text, ChatNotices.timeOf(it.time),
                    noQuote = it.noQuote, decision = it.decision, next = it.next) }
                .map { it.id }.toSet()
        }
        val watching = app.inForeground && app.chat.dmOnScreen
        buzz.filterNot { it.chatOnly }.takeLast(8).filterNot { watching && it.id in inChat }.forEach { Notifier.pcNotice(ctx, it) }
        s.lastPcNoticeId = last
    }
}

/** Hands Prox Hub's notices (kind "hub") to the Prox Hub app: the ProxAI-built app that declared the "hub" feature. */
object HubRelay {
    const val KIND = "hub"
    const val ACTION = "com.proxai.shell.HUB_NOTICE"

    /** (for the app, for ProxMobile). Unit-tested. */
    fun split(notices: List<PcNotice>, haveApp: Boolean): Pair<List<PcNotice>, List<PcNotice>> =
        if (!haveApp) emptyList<PcNotice>() to notices else notices.partition { it.kind == KIND }

    /** Installed apps that can show them: the Prox Hub app itself (HUB-APP-ONLY, 0.9.15), signed with the owner key
     *  Nathan pinned (OWNER-SIGN, 0.9.16) and built with "hub". */
    fun apps(ctx: Context): List<String> = runCatching {
        ctx.packageManager.queryBroadcastReceivers(Intent(ACTION), 0)
            .map { it.activityInfo.packageName }.distinct()
            .filter { pkg ->
                val owner = OwnerSign.isOwner(ctx, pkg)         // asked once per app
                DumpsyncClient.hubRefusal(pkg, owner) == null && PcStatsProvider.callerRefusal(ctx, pkg, "hub", owner) == null
            }
    }.getOrDefault(emptyList())

    fun send(ctx: Context, apps: List<String>, notices: List<PcNotice>) {
        for (pkg in apps) for (n in notices.takeLast(8)) {
            val i = Intent(ACTION).setPackage(pkg).putExtra("title", n.title).putExtra("text", n.text)
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                // the app's receiver checks who sent it, which Android only tells it when the sender shares that
                ctx.sendBroadcast(i, null, android.app.BroadcastOptions.makeBasic().setShareIdentityEnabled(true).toBundle())
            } else {
                ctx.sendBroadcast(i)
            }
        }
    }
}

/** Checks the PC for new alerts and notices about every 15 minutes (Android's minimum while the app is closed). */
class PcAlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val settings = ProxApp.instance.settings
        if (!(settings.pcAlerts || settings.pcNotices) || !settings.isConfigured) return Result.success()
        return try {
            val client = PcStatsClient(settings)
            if (settings.pcAlerts) {
                val (show, last) = PcAlert.toNotify(client.alertsAfter(settings.lastPcAlertId), settings.lastPcAlertId,
                    LocalDateTime.now())
                NoticeStore.of(applicationContext).put(show.takeLast(5).map { NoticeStore.entry(it) })
                show.takeLast(5).forEach { Notifier.pcAlert(applicationContext, it) }
                settings.lastPcAlertId = last
            }
            if (settings.pcNotices) NoticeSync.pull(applicationContext)
            Result.success()
        } catch (e: IOException) {
            Result.success()   // PC off or away from the tailnet: try again next round
        }
    }

    companion object {
        private const val NAME = "pc-alerts"

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            val s = ProxApp.instance.settings
            if (!s.pcAlerts && !s.pcNotices) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PcAlertWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        }

        /** An extra check right away (app opened). */
        fun checkNow(ctx: Context) {
            val s = ProxApp.instance.settings
            if (s.pcAlerts || s.pcNotices) {
                WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<PcAlertWorker>().build())
            }
        }
    }
}

/**
 * The only door from ProxAI-built apps into ProxMobile: content://com.proxai.hermes.pcstats,
 * method "stats" -> Bundle("json"). Also "scout", "scout-update", "ask", "voice", "voice-stop", "tutor", "dumpsync", "daily", "pipeline"
 * and "docs" (proxDocs, 0.9.8: the token stays in ProxMobile and the app unlocks through ProxAI's own DocsUnlockActivity);
 * each method needs the matching feature in the app's own proxai-app.json (METHOD_FEATURE). Answers only apps named com.proxai.apps.* that are signed
 * with the generated-apps key ProxMobile pinned on the first install (AppInstaller). The API key
 * never leaves ProxMobile; the app only gets the stats JSON.
 */
class PcStatsProvider : ContentProvider() {
    override fun onCreate() = true

    /**
     * Always a Bundle with "json" or "error" (0.9.13). Android hands the calling app null, with no exception, when
     * this throws past Binder (the process dies) or the answer is over Binder's ~1 MB, and the shell could only say
     * "ProxMobile isn't installed". So every failure, refusals included, comes back as an "error" here.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle = try {
        bundleFor(answer(method, arg))
    } catch (e: Throwable) {          // also Errors (out of memory on a big file): never take ProxMobile down
        bundleFor(null to failure(e))
    }

    private fun answer(method: String, arg: String?): Pair<String?, String?> {
        val ctx = context ?: return null to STARTING
        // Review round (3): the caller is the package Android vouches for. getCallingPackage() is checked by the
        // framework against Binder.getCallingUid() (a SecurityException otherwise, which call() turns into an
        // error) and checked again here against that UID's own packages; anything else is no caller (refused).
        val uidPackages = runCatching {
            ctx.packageManager.getPackagesForUid(android.os.Binder.getCallingUid())?.toList()
        }.getOrNull()
        val caller = OwnerSign.verifiedCaller(callingPackage, uidPackages)
        // Owner or not is asked of Android once per call, and that one answer is used by every check below.
        val owner = OwnerSign.isOwner(ctx, caller)
        // A proxDocs app's shell checks in every 20 s while it shows ({"op":"alive"}): while ProxAI is locked
        // that's refused like any call, but without the "unlock ProxAI for this app" notification each time.
        val quiet = method == "docs" && runCatching { JSONObject(arg ?: "").optString("op") == "alive" }.getOrDefault(false)
        val why = callerRefusal(ctx, caller, method, owner) ?: lockRefusal(ctx, caller, notify = !quiet)
        if (why != null) return null to why
        val client = PcStatsClient(ProxApp.instance.settings)
        return try {
            val json: String? = when (method) {
                "stats" -> client.stats()
                "scout" -> client.scout()                          // the scout's gig board (v0.6.4)
                "scout-update" -> client.scoutUpdate(arg ?: "")
                "ask" -> ask(caller!!, arg ?: "")                  // ProxAI.ask from installed apps (0.7.0)
                "voice" -> JSONObject().put("spoken", com.proxai.hermes.voice.AppVoice.say(ctx, arg ?: "")).toString()
                "voice-stop" -> { com.proxai.hermes.voice.AppVoice.stop(); "{}" }
                "voice-warmup" -> { com.proxai.hermes.voice.ProxVoice(ProxApp.instance.settings, ctx.cacheDir).warmup(); "{}" }
                "tutor" -> client.tutor(arg ?: "")                  // the Study Coach (0.7.0)
                "dumpsync" -> DumpsyncClient.from(ProxApp.instance.settings).call(arg ?: "")   // Prox Dump sync (0.7.1)
                "daily" -> DumpsyncClient.from(ProxApp.instance.settings).daily(arg ?: "")     // Daily Log (0.7.2)
                // Pipeline (0.7.7); 0.9.15: names the caller (callingPackage); writes only for the owner apps,
                // 0.9.16: and only when Android says they're signed with the pinned owner key (OwnerSign)
                "pipeline" -> DumpsyncClient.from(ProxApp.instance.settings).pipeline(arg ?: "", caller, owner)
                "docs" -> ProxDocs.bridge(arg ?: "")                // proxDocs (0.9.8)
                // Prox Hub (0.9.10); 0.9.15: only the Prox Hub app, named by Android's Binder identity (callingPackage)
                "hub" -> DumpsyncClient.from(ProxApp.instance.settings).hub(arg ?: "", caller, owner)
                else -> return null to "unknown method"
            }
            json to null
        } catch (e: Exception) {
            null to (e.message ?: "Can't reach the PC")
        }
    }

    private fun bundleFor(r: Pair<String?, String?>): Bundle {
        val (key, value) = reply(r.first, r.second)
        return Bundle().apply { putString(key, value) }
    }

    /** S1: while ProxAI is locked, apps get nothing (unless they're in use: AppLock.appsAllowed). */
    private fun lockRefusal(ctx: Context, caller: String?, notify: Boolean = true): String? {
        if (ProxApp.instance.lock.appsAllowed()) return null
        if (!notify) return LOCKED
        val name = caller?.let { pkg ->
            runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        } ?: "An app"
        com.proxai.hermes.notify.Notifier.unlockForApp(ctx, name)
        return LOCKED
    }

    /** One question to ProxAI from an installed app, in that app's own session. Returns {"text"}. */
    private fun ask(caller: String, arg: String): String {
        val prompt = runCatching { JSONObject(arg).optString("prompt") }.getOrNull().orEmpty()
        if (prompt.isBlank()) throw java.io.IOException("Nothing to ask")
        if (prompt.length > MAX_ASK_CHARS) throw java.io.IOException("That question is too long (max $MAX_ASK_CHARS characters)")
        val app = ProxApp.instance
        val text = kotlinx.coroutines.runBlocking {
            app.hermes.ask(prompt, sessionId = "proxai-app-" + caller.removePrefix("com.proxai.apps."),
                system = com.proxai.hermes.data.Prompts.APP_BRIDGE, route = app.chat.currentRoute())
        }
        return JSONObject().put("text", text).toString()
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        const val MAX_ASK_CHARS = 20_000
        /** S1: what an app's bridge call gets while ProxAI is locked. */
        const val LOCKED = "ProxAI is locked. Open ProxMobile and unlock it, then try again."
        const val STARTING = "ProxMobile is still starting. Try again in a moment."

        /** Binder carries a String as UTF-16 (2 bytes a character) in a buffer of about 1 MB that the calling app
         *  shares with the Bundle's own overhead and its other calls in flight; past that the app gets null instead
         *  of the answer. 400K characters are 800 KB, leaving ~250 KB of margin so an answer at the cap still
         *  arrives and anything over it gets the clear "too large" error. */
        const val MAX_ANSWER_CHARS = 400_000

        /** The one key and value the calling app gets for an answer [json] or an [error]: never empty, never
         *  over [MAX_ANSWER_CHARS]. Unit-tested. */
        fun reply(json: String?, error: String?): Pair<String, String> = when {
            json != null && json.isBlank() -> "error" to "The PC sent an empty answer"
            json != null && json.length > MAX_ANSWER_CHARS -> "error" to
                "That answer is too large for Android's app bridge (${json.length / 1000}K characters, the most is " +
                "${MAX_ANSWER_CHARS / 1000}K)"
            json != null -> "json" to json
            !error.isNullOrBlank() -> "error" to error.take(2000)
            else -> "error" to "ProxMobile had no answer"
        }

        /** What the calling app hears when ProxMobile's own code failed. Unit-tested. */
        fun failure(e: Throwable): String = when (e) {
            is OutOfMemoryError -> "ProxMobile ran out of memory on that answer (too large for the phone)"
            else -> "ProxMobile failed on that call (${e.javaClass.simpleName}" +
                (e.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(200) } ?: "") + ")"
        }

        /** Which declared feature each method needs. */
        val METHOD_FEATURE = mapOf(
            "stats" to "pc-stats", "scout" to "scout", "scout-update" to "scout",
            "ask" to "ask", "voice" to "voice", "voice-stop" to "voice", "voice-warmup" to "voice", "tutor" to "tutor",
            "dumpsync" to "dumpsync", "daily" to "daily", "pipeline" to "pipeline", "docs" to "docs", "hub" to "hub",
        )

        /**
         * Why [caller] may not use [method] (a ProxAI-built app, signed with the pinned key, that declared the
         * method's feature), or null. Also used by DocsUnlockActivity, which only opens for such an app.
         */
        fun callerRefusal(ctx: Context, caller: String?, method: String): String? =
            callerRefusal(ctx, caller, method, OwnerSign.isOwner(ctx, caller))

        /** The same, with [owner] already asked of Android ([OwnerSign.isOwner]) for this call. */
        fun callerRefusal(ctx: Context, caller: String?, method: String, owner: Boolean): String? {
            val trusted = ctx.getSharedPreferences("pc_apps", Context.MODE_PRIVATE).getString("trusted_cert_sha256", null)
            return refuse(caller, caller?.let { certOf(ctx, it) }, trusted, owner)
                ?: featureRefusal(method, caller?.let { declaredFeatures(ctx, it) })
        }

        /** The features the caller declared in its own proxai-app.json (packed into its APK by proxai-app-build). */
        private fun declaredFeatures(ctx: Context, pkg: String): Set<String>? = try {
            val json = ctx.createPackageContext(pkg, 0).assets.open("proxai-app.json").bufferedReader().use { it.readText() }
            val arr = JSONObject(json).optJSONArray("features")
            (0 until (arr?.length() ?: 0)).map { arr!!.optString(it) }.toSet()
        } catch (e: Exception) {
            null
        }

        private fun certOf(ctx: Context, pkg: String): String? = try {
            val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signers = info.signingInfo?.apkContentsSigners
            if (signers == null || signers.size != 1) null
            else MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }

        /** Why an app that declared [features] (null = unreadable) may not call [method], or null. Unit-tested. */
        fun featureRefusal(method: String, features: Set<String>?): String? {
            val need = METHOD_FEATURE[method] ?: return null       // unknown methods are refused later
            return when {
                features == null -> "Can't read this app's features (rebuild it with ProxAI)"
                need !in features -> "This app wasn't built with the '$need' feature."
                else -> null
            }
        }

        /** Why [caller] may not read the stats, or null if it may. [ownerSigned] (OWNER-SIGN, 0.9.16): Prox Hub or
         *  BoardRoom signed with Nathan's pinned owner key ([OwnerSign.isOwner]) instead of the apps key. Unit-tested. */
        fun refuse(caller: String?, cert: String?, trusted: String?, ownerSigned: Boolean = false): String? = when {
            caller == null || !caller.startsWith("com.proxai.apps.") -> "Only ProxAI-built apps can read the PC's stats"
            trusted == null -> "Install a ProxAI app from the Apps tab first"
            ownerSigned && caller in DumpsyncClient.OWNER_APPS -> null
            cert == null || !cert.equals(trusted, ignoreCase = true) -> "This app isn't signed with the ProxAI apps key"
            else -> null
        }
    }
}
