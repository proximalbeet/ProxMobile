package com.proxai.hermes.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the PC says about proxDocs (GET /v1/docs/status; other answers carry the same fields). */
data class DocsStatus(
    val mode: String = "",
    val server: Boolean = false,
    val model: String = "",
    val loaded: Boolean = false,
    val loading: Boolean = false,
    val ready: Boolean = false,
    val code: String = "",
    val why: String = "",
    val passwordSet: Boolean = false,
    val passages: Int = 0,
    val commits: Int = 0,
    val apps: Int = 0,
) {
    companion object {
        fun parse(o: JSONObject, before: DocsStatus? = null): DocsStatus {
            val lib = o.optJSONObject("library")
            return DocsStatus(
                mode = o.optString("mode", before?.mode ?: ""),
                server = o.optBoolean("server", before?.server ?: false),
                model = o.optString("model", before?.model ?: ""),
                loaded = o.optBoolean("loaded", before?.loaded ?: false),
                loading = o.optBoolean("loading", before?.loading ?: false),
                ready = o.optBoolean("ready", before?.ready ?: false),
                code = o.optString("code", before?.code ?: ""),
                why = o.optString("why", before?.why ?: ""),
                passwordSet = if (o.has("password_set")) o.optBoolean("password_set") else before?.passwordSet ?: false,
                passages = lib?.optInt("passages") ?: before?.passages ?: 0,
                commits = lib?.optInt("commits") ?: before?.commits ?: 0,
                apps = lib?.optInt("apps") ?: before?.apps ?: 0,
            )
        }
    }
}

data class DocsSource(val n: Int, val title: String, val where: String, val kind: String, val cited: Boolean)

data class DocsAnswer(val question: String, val answer: String, val sources: List<DocsSource>, val seconds: Double)

/** An expected no from the PC: [code] is what the screen acts on (locked, wrong_password, personal_mode...). */
class DocsRefused(val code: String, message: String, val waitSeconds: Int = 0, val triesLeft: Int = -1) : IOException(message)

/**
 * P4 (0.9.7): proxDocs on the PC (Hermes plugin proxai-docs) through the apps server's /v1/docs, which
 * hands each request as is to the plugin's private socket. Blocking: call it off the main thread.
 */
class ProxDocsClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val http: OkHttpClient = HTTP,
) {
    companion object {
        val HTTP: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(320, TimeUnit.SECONDS)      // the local model can take a minute or two (the PC waits 300 s)
            .build()
        private val JSON_TYPE = "application/json".toMediaType()
    }

    fun status(): DocsStatus = DocsStatus.parse(call("GET", "status", null))

    /** (token, status). The password goes in this one request's body and nowhere else. */
    fun unlock(password: String, relockSeconds: Long): Pair<String, DocsStatus> {
        val o = call("POST", "unlock", JSONObject().put("password", password).put("where", "phone")
            .put("relock_seconds", relockSeconds))
        val token = o.optString("token")
        if (token.length < 20) throw IOException("proxDocs answered without a key")
        return token to DocsStatus.parse(o)
    }

    fun alive(token: String): JSONObject = call("POST", "alive", JSONObject().put("token", token))

    fun lock(token: String) {
        call("POST", "lock", JSONObject().put("token", token))
    }

    fun load(token: String): JSONObject = call("POST", "load", JSONObject().put("token", token))

    fun ask(token: String, question: String): DocsAnswer {
        val o = call("POST", "ask", JSONObject().put("token", token).put("question", question))
        val arr = o.optJSONArray("sources")
        val sources = (0 until (arr?.length() ?: 0)).mapNotNull { i ->
            arr?.optJSONObject(i)?.let {
                DocsSource(it.optInt("n"), it.optString("title"), it.optString("where"), it.optString("kind"), it.optBoolean("cited"))
            }
        }
        return DocsAnswer(question, o.optString("answer"), sources, o.optDouble("seconds", 0.0))
    }

    private fun call(method: String, route: String, body: JSONObject?): JSONObject {
        val b = Request.Builder().url("$baseUrl/apps/v1/docs/$route").header("Authorization", "Bearer $apiKey")
        if (method == "POST") b.post((body ?: JSONObject()).toString().toRequestBody(JSON_TYPE))
        http.newCall(b.build()).execute().use { r ->
            val text = r.body?.string().orEmpty()
            val o = runCatching { JSONObject(text) }.getOrNull()
            if (o == null || !o.has("ok")) {
                throw when (r.code) {
                    401 -> IOException("The PC refused ProxAI's API key")
                    404 -> DocsRefused("old_server", "The PC's apps server doesn't know proxDocs yet (it needs the P4 update)")
                    else -> IOException("proxDocs error (HTTP ${r.code})")
                }
            }
            if (!o.optBoolean("ok")) {
                val code = o.optString("code").ifBlank { if (r.code == 503) "down" else "error" }
                throw DocsRefused(code, o.optString("error").ifBlank { "proxDocs said no (HTTP ${r.code})" },
                    o.optInt("wait_seconds", 0), o.optInt("tries_left", -1))
            }
            return o
        }
    }
}

/** What the proxDocs screen shows. Answers live only here, in memory, and go when it locks. */
data class DocsUi(
    val status: DocsStatus? = null,
    val checking: Boolean = false,
    val unlocked: Boolean = false,
    val unlocking: Boolean = false,
    val asking: String? = null,
    val askingSince: Long = 0,
    val answers: List<DocsAnswer> = emptyList(),
    val error: String? = null,
    val waitUntil: Long = 0,
    val note: String? = null,
)

/**
 * When an open proxDocs locks again, following the app lock (Nathan 11-01: "Base it off of the lock
 * feature on proxmobile"): after the app lock's time out of sight (also when the app lock itself is
 * switched off), whenever the app lock locks, on Lock, and when the PC says so. Pure, clock passed in
 * (ProxDocsTest).
 */
class DocsTimer(private val relockMs: () -> Long, private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile private var awaySince = 0L

    fun offScreen() {
        awaySince = clock()
    }

    /** Back on screen: true if it was away long enough to lock. */
    fun onScreen(): Boolean {
        val away = awaySince
        awaySince = 0
        return away > 0 && clock() - away >= relockMs()
    }

    /**
     * A proxDocs app (a PC-built app with the "docs" feature, 0.9.8) is in use: it counts as on screen for
     * [APP_GRACE] more (its shell checks in every 20 s while it shows), like the app lock's rule that apps
     * in use keep their window open.
     */
    fun used() {
        if (awaySince > 0) awaySince = maxOf(awaySince, clock() + APP_GRACE)
    }

    /** Away (no ProxAI screen, no proxDocs app) for the whole lock time. */
    fun expired(): Boolean {
        val away = awaySince
        return away > 0 && clock() - away >= relockMs()
    }

    val onScreenNow: Boolean get() = awaySince == 0L

    companion object {
        const val APP_GRACE = 30_000L
    }
}

/**
 * The proxDocs session, app-wide (not tied to the screen, so leaving the tab or turning the phone
 * doesn't cut a question off: the 0.9.5 lesson). The token is kept in memory only. While ProxAI is on
 * screen it tells the PC every 30 s that it's still open, so the PC's copy of the lock time doesn't
 * run out; off screen it stops, and both sides lock after the app lock's time.
 */
object ProxDocs {
    private val _ui = MutableStateFlow(DocsUi())
    val ui: StateFlow<DocsUi> = _ui
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var token: String? = null
    @Volatile private var keepAlive: Job? = null
    @Volatile private var askJob: Job? = null

    /** Set by ProxApp (tests set their own): the client for the connected PC (null if not connected) and the lock time. */
    var client: () -> ProxDocsClient? = { null }
    var relockMs: () -> Long = { 60_000L }
    var now: () -> Long = System::currentTimeMillis
    var aliveEveryMs = 30_000L
    val timer = DocsTimer({ relockMs() }, { now() })

    val unlocked: Boolean get() = token != null

    /** The PC's state (no password needed). */
    fun refresh() {
        val c = client() ?: return _ui.update { it.copy(status = null, error = "Connect ProxAI to your PC first (Settings)") }
        if (_ui.value.checking) return
        _ui.update { it.copy(checking = true) }
        scope.launch {
            try {
                val st = c.status()
                if (!st.server && token != null) lockLocal("proxDocs locked: the PC is in personal mode.")
                _ui.update { it.copy(status = st, checking = false, error = null) }
            } catch (e: IOException) {
                _ui.update { it.copy(checking = false, error = e.message ?: "Couldn't reach the PC") }
            }
        }
    }

    fun unlock(password: String) {
        val c = client() ?: return
        if (_ui.value.unlocking || password.isEmpty()) return
        if (now() < _ui.value.waitUntil) return
        _ui.update { it.copy(unlocking = true, error = null, note = null) }
        scope.launch {
            try {
                val (tok, st) = c.unlock(password, (relockMs() / 1000).coerceAtLeast(30))
                token = tok
                _ui.update { it.copy(unlocking = false, unlocked = true, status = st, waitUntil = 0) }
                if (timer.onScreenNow) startKeepAlive()
            } catch (e: DocsRefused) {
                val wait = if (e.waitSeconds > 0) now() + e.waitSeconds * 1000L else 0L
                _ui.update { it.copy(unlocking = false, error = e.message, waitUntil = wait) }
                if (e.code == "personal_mode" || e.code == "no_password") refresh()
            } catch (e: IOException) {
                _ui.update { it.copy(unlocking = false, error = e.message ?: "Couldn't reach the PC") }
            }
        }
    }

    fun ask(question: String) {
        val c = client() ?: return
        val tok = token ?: return
        val q = question.trim()
        if (q.length < 3 || _ui.value.asking != null) return
        _ui.update { it.copy(asking = q, askingSince = now(), error = null) }
        askJob = scope.launch {
            try {
                val a = c.ask(tok, q)
                if (token == tok) _ui.update { it.copy(asking = null, answers = (it.answers + a).takeLast(30)) }
            } catch (e: DocsRefused) {
                _ui.update { it.copy(asking = null) }
                if (e.code in LOCKING) lockLocal(e.message) else _ui.update { it.copy(error = e.message) }
                if (e.code == "not_loaded" || e.code == "loading") refresh()
            } catch (e: IOException) {
                _ui.update { it.copy(asking = null, error = e.message ?: "Couldn't reach the PC") }
            }
        }
    }

    /** Server mode with the model not loaded: ask the PC to load it (the button; Nathan's own tap). */
    fun load() {
        val c = client() ?: return
        val tok = token ?: return
        scope.launch {
            try {
                val o = c.load(tok)
                _ui.update { it.copy(status = DocsStatus.parse(o, it.status), error = null) }
                repeat(60) {
                    delay(3_000)
                    if (token != tok) return@launch
                    val st = c.status()
                    _ui.update { it.copy(status = st) }
                    if (st.loaded || !st.server || !st.loading) return@launch
                }
            } catch (e: DocsRefused) {
                if (e.code in LOCKING) lockLocal(e.message) else _ui.update { it.copy(error = e.message) }
            } catch (e: IOException) {
                _ui.update { it.copy(error = e.message ?: "Couldn't reach the PC") }
            }
        }
    }

    /** Lock now: the Lock button, the app lock locking, or away too long. Tells the PC too. */
    fun lock(note: String? = null) {
        val tok = token
        lockLocal(note)
        val c = client() ?: return
        if (tok != null) scope.launch { runCatching { c.lock(tok) } }
    }

    private fun lockLocal(note: String?) {
        token = null
        keepAlive?.cancel()
        keepAlive = null
        askJob?.cancel()
        askJob = null
        _ui.update { DocsUi(status = it.status, note = note, waitUntil = it.waitUntil) }
    }

    /** ProxAI came on screen (ProxApp, with the app lock). Locks if it was away for the lock time. */
    fun onScreen() {
        if (timer.onScreen() && token != null) {
            lock("proxDocs locked again: ProxAI was out of sight for a while.")
        } else if (token != null) {
            startKeepAlive()
        }
    }

    /** ProxAI went out of sight: stop telling the PC it's open; the lock time starts. */
    fun offScreen() {
        timer.offScreen()
        keepAlive?.cancel()
        keepAlive = null
    }

    private fun startKeepAlive() {
        if (keepAlive?.isActive == true) return
        val c = client() ?: return
        keepAlive = scope.launch {
            while (isActive) {
                val tok = token ?: return@launch
                try {
                    val o = c.alive(tok)
                    _ui.update { it.copy(status = DocsStatus.parse(o, it.status)) }
                } catch (e: DocsRefused) {
                    if (e.code in LOCKING) {
                        lockLocal(e.message)
                        return@launch
                    }
                } catch (_: IOException) {
                    // The PC may be away for a moment; try again next time. If its lock time runs out, it says "locked".
                }
                delay(aliveEveryMs)
            }
        }
    }

    /**
     * P4 bridge (0.9.8): ProxAI.docs from a PC-built app with the "docs" feature, through PcStatsProvider.
     * Blocking; always JSON: {"ok": true, ...} or {"ok": false, "code", "error"}. It shares this session with
     * the Docs tab, so the token and the password never reach the app: it unlocks through ProxAI's own
     * prompt (DocsUnlockActivity). Its answers don't go into the Docs tab or Chat.
     */
    fun bridge(arg: String): String {
        val o = runCatching { JSONObject(arg) }.getOrNull() ?: return bridgeErr("bad_request", "Bad proxDocs request")
        val c = client() ?: return bridgeErr("not_configured", "ProxAI isn't connected to the PC")
        if (token != null && timer.expired()) lock("proxDocs locked again: ProxAI and its apps were out of sight for a while.")
        val tok = token
        val locked = { bridgeErr("locked", "proxDocs is locked. Unlock it first (ProxAI.docs.unlock()).") }
        return try {
            when (o.optString("op")) {
                "status" -> {
                    val st = c.status()
                    if (tok != null) timer.used()
                    if (!st.server && tok != null) lockLocal("proxDocs locked: the PC is in personal mode.")
                    statusJson(st, token != null).toString()
                }
                "alive" -> if (tok == null) JSONObject().put("ok", true).put("unlocked", false).toString() else {
                    val st = DocsStatus.parse(c.alive(tok), _ui.value.status)
                    timer.used()
                    statusJson(st, true).toString()
                }
                "ask" -> {
                    if (tok == null) return locked()
                    val a = c.ask(tok, o.optString("question").trim().take(1000))
                    timer.used()
                    val sources = org.json.JSONArray()
                    a.sources.forEach { s ->
                        sources.put(JSONObject().put("n", s.n).put("title", s.title).put("where", s.where)
                            .put("kind", s.kind).put("cited", s.cited))
                    }
                    JSONObject().put("ok", true).put("answer", a.answer).put("sources", sources).put("seconds", a.seconds).toString()
                }
                "load" -> {
                    if (tok == null) return locked()
                    val st = DocsStatus.parse(c.load(tok), _ui.value.status)
                    timer.used()
                    statusJson(st, true).put("loading", true).toString()
                }
                "lock" -> {
                    if (tok != null) lock("Locked from an app.")
                    JSONObject().put("ok", true).put("unlocked", false).toString()
                }
                else -> bridgeErr("bad_request", "Unknown proxDocs call")
            }
        } catch (e: DocsRefused) {
            if (e.code in LOCKING && token == tok) lockLocal(e.message)
            JSONObject().put("ok", false).put("code", e.code).put("error", e.message).apply {
                if (e.waitSeconds > 0) put("wait_seconds", e.waitSeconds)
            }.toString()
        } catch (e: IOException) {
            bridgeErr("unreachable", e.message ?: "Can't reach the PC")
        }
    }

    private fun statusJson(st: DocsStatus, unlocked: Boolean): JSONObject = JSONObject().put("ok", true)
        .put("unlocked", unlocked).put("ready", st.ready).put("mode", st.mode).put("server", st.server)
        .put("model", st.model).put("loaded", st.loaded).put("loading", st.loading).put("code", st.code)
        .put("why", st.why).put("password_set", st.passwordSet).put("passages", st.passages)
        .put("commits", st.commits).put("apps", st.apps)

    private fun bridgeErr(code: String, message: String): String =
        JSONObject().put("ok", false).put("code", code).put("error", message).toString()

    /** Tests only. */
    fun reset() {
        lockLocal(null)
        _ui.value = DocsUi()
    }

    private val LOCKING = setOf("locked", "personal_mode")
}
