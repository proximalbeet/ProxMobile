package com.proxai.shell

import org.json.JSONObject

/** Pure helpers (no Android classes), so they can be unit-tested on the JVM. */
object ShellLogic {
    /** Fake https origin the app's files are served from. Each APK is its own package, so
     *  localStorage and cookies are already separate per app. */
    const val HOST = "app.proxai.local"
    const val ORIGIN = "https://$HOST/"

    /** Features an app can ask for in proxai-app.json. Anything else is ignored. */
    val KNOWN_FEATURES = setOf("vibrate", "notifications", "speech", "ask", "pc-stats", "scout",
        "voice", "listen", "files", "tutor", "dumpsync", "daily", "pipeline", "docs", "hub")

    /** Size of the floating ⋯ menu button (top right; apps keep that corner clear). */
    const val MENU_DP = 36

    /** ProxMobile's door for PC stats (data/PcStats.kt PcStatsProvider). */
    const val PC_STATS_URI = "content://com.proxai.hermes.pcstats"

    data class AppInfo(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val features: Set<String>,
        val color: Int,
    )

    fun parseInfo(json: String): AppInfo {
        val o = JSONObject(json)
        val feats = o.optJSONArray("features")
        val set = mutableSetOf<String>()
        if (feats != null) for (i in 0 until feats.length()) feats.optString(i).takeIf { it in KNOWN_FEATURES }?.let(set::add)
        return AppInfo(
            id = o.optString("id", "app"),
            name = o.optString("name", "ProxAI app").ifBlank { "ProxAI app" },
            version = o.optString("version", "1.0"),
            description = o.optString("description", ""),
            features = set,
            color = parseColor(o.optString("color", "")) ?: BRAND,
        )
    }

    fun parseColor(s: String): Int? {
        val hex = s.trim().removePrefix("#")
        if (!Regex("^[0-9a-fA-F]{6}$").matches(hex)) return null
        return (0xFF000000 or hex.toLong(16)).toInt()
    }

    /** Asset path for a request path on [HOST], or null if it's not allowed ('..', hidden files). */
    fun assetPath(urlPath: String?): String? {
        var p = (urlPath ?: "").trimStart('/')
        if (p.isEmpty() || p.endsWith("/")) p += "index.html"
        val parts = p.split('/')
        if (parts.any { it.isEmpty() || it == ".." || it == "." || it.startsWith(".") }) return null
        return "www/$p"
    }

    fun mimeType(path: String): String = when (path.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html"
        "js", "mjs" -> "text/javascript"
        "css" -> "text/css"
        "json" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "ico" -> "image/x-icon"
        "woff2" -> "font/woff2"
        "woff" -> "font/woff"
        "ttf" -> "font/ttf"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "ogg" -> "audio/ogg"
        "mp4" -> "video/mp4"
        "txt", "md" -> "text/plain"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    /** <input accept="..."> values to MIME types for the system picker ("" or nothing = any file). */
    fun acceptMimes(accept: List<String>): List<String> = accept
        .flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        .map { a -> if (a.startsWith(".")) EXT_MIME[a.removePrefix(".")] ?: "*/*" else a }
        .distinct().let { if (it.isEmpty() || "*/*" in it) emptyList() else it }

    private val EXT_MIME = mapOf(
        "pdf" to "application/pdf", "txt" to "text/plain", "md" to "text/markdown", "csv" to "text/csv",
        "html" to "text/html", "htm" to "text/html", "json" to "application/json", "rtf" to "application/rtf",
        "doc" to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "ppt" to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "odt" to "application/vnd.oasis.opendocument.text", "epub" to "application/epub+zip",
        "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "webp" to "image/webp",
    )

    data class ListenOptions(val silenceMs: Long, val hints: List<String>)

    /** listen({silenceMs, hints}) options: silence 800–8000 ms (default 2000), up to 50 short hints. */
    fun listenOptions(json: String?): ListenOptions {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrElse { JSONObject() }
        val silence = o.optLong("silenceMs", 2000L).coerceIn(800L, 8000L)
        val arr = o.optJSONArray("hints")
        val hints = (0 until (arr?.length() ?: 0)).mapNotNull { arr!!.optString(it).trim().take(60).takeIf { h -> h.isNotEmpty() } }.take(50)
        return ListenOptions(silence, hints)
    }

    fun recognizerError(code: Int): String = when (code) {
        1 -> "network timeout"; 2 -> "network error"; 3 -> "audio error"; 4 -> "server error"
        5 -> "client error"; 6 -> "no speech"; 7 -> "no match"; 8 -> "recognizer busy"
        9 -> "no microphone permission"; 10 -> "too many requests"; 11 -> "service disconnected"
        12 -> "language not supported"; 13 -> "language unavailable"
        else -> "error $code"
    }

    /** A dumpsync call that never reached the PC (refused by the shell or by ProxMobile's door), as the
     *  same structured error the PC side returns: {"ok": false, "code", "error"}. Unit-tested. */
    fun dumpsyncError(message: String): String = bridgeError(message, "Prox Dump sync", "0.7.1")

    /** The same for a Daily Log call ("daily" feature, ProxMobile 0.7.2+). Unit-tested. */
    fun dailyError(message: String): String = bridgeError(message, "Daily Log", "0.7.2")

    /** The same for a Pipeline call ("pipeline" feature, ProxMobile 0.7.7+). Unit-tested. */
    fun pipelineError(message: String): String = bridgeError(message, "the Pipeline", "0.7.7")

    /** The same for a proxDocs call ("docs" feature, ProxMobile 0.9.8+). Unit-tested. */
    fun docsError(message: String): String = bridgeError(message, "proxDocs", "0.9.8")

    /** The same for a Prox Hub call ("hub" feature, ProxMobile 0.9.10+). Unit-tested. */
    fun hubError(message: String): String = bridgeError(message, "Prox Hub", "0.9.10")

    /** A file name safe for the phone's Downloads folder: no paths, no control characters, never empty or hidden.
     *  Unit-tested. */
    fun safeFileName(name: String?): String {
        val base = (name ?: "").substringAfterLast('/').substringAfterLast('\\')
            .replace(Regex("[\\x00-\\x1f\"*:<>?|]"), "_").trim().trimStart('.')
        return if (base.isBlank()) "file" else base.take(120)
    }

    /** The broadcast ProxMobile (0.9.11+) sends a "hub" app for each Prox Hub notice from the PC. */
    const val HUB_NOTICE = "com.proxai.shell.HUB_NOTICE"

    /** Whether a Prox Hub notice from [sender] may be shown by an app with [features]. Unit-tested. */
    fun acceptHubNotice(sender: String?, features: Set<String>?): Boolean =
        sender == "com.proxai.hermes" && features != null && "hub" in features

    /**
     * Runs ProxMobile calls one at a time, in the order they came (shell kit 12). Binder's ~1 MB buffer for answers is
     * shared by every call this app has in flight, so two big Prox Hub answers at once (state + full right after an
     * act) could overflow it, and Android then hands back null instead of either. Unit-tested.
     */
    class CallGate {
        private val lock = java.util.concurrent.locks.ReentrantLock(true)
        fun <T> run(block: () -> T): T {
            lock.lock()
            try { return block() } finally { lock.unlock() }
        }
    }

    /** ProxMobile's package: the shell looks it up only to say why its door couldn't be found. */
    const val PROXMOBILE = "com.proxai.hermes"

    // What went wrong on the way to ProxMobile's door (shell kit 12). Each one has its own code in [bridgeError].
    const val NO_ANSWER = "ProxMobile didn't answer: it stopped during the call, or its answer was over Android's 1 MB " +
        "limit between apps. Open ProxMobile and try again."
    const val EMPTY_ANSWER = "ProxMobile answered with nothing (no result and no error)."
    const val HIDDEN = "This app can't find ProxMobile: it isn't installed, or Android hides it from this app " +
        "(package visibility). Install this app from ProxMobile's Apps tab, then try again."

    /**
     * Reads what ProxMobile's door gave back into (ok, json-or-error). [answered] is false when
     * ContentResolver.call returned null, which Android does without an exception when the call dies on the way
     * (ProxMobile's process stopped, or the answer was too big for Binder). Unit-tested.
     */
    fun readAnswer(answered: Boolean, json: String?, error: String?): Pair<Boolean, String> = when {
        !answered -> false to NO_ANSWER
        json != null && json.isNotBlank() -> true to json
        json != null -> false to EMPTY_ANSWER
        error != null -> false to error.ifBlank { "ProxMobile refused the call without saying why." }
        else -> false to EMPTY_ANSWER
    }

    /**
     * What to say when calling ProxMobile's door threw. Android throws IllegalArgumentException("Unknown URI/authority")
     * when no provider is found: [proxMobileVersion] (ProxMobile's versionName, or null when this app can't see it)
     * tells a hidden or missing ProxMobile from one too old to have the door. Unit-tested.
     */
    fun callFailed(e: Throwable, proxMobileVersion: String?): String {
        val noDoor = e is IllegalArgumentException && (e.message ?: "").contains("Unknown", ignoreCase = true)
        return when {
            noDoor && proxMobileVersion == null -> HIDDEN
            noDoor -> "ProxMobile $proxMobileVersion is installed but has no door for apps (needs 0.7.0)."
            else -> "Calling ProxMobile failed (${e.javaClass.simpleName}" +
                (e.message?.takeIf { it.isNotBlank() }?.let { ": " + it.take(200) } ?: "") + ")."
        }
    }

    private fun bridgeError(message: String, what: String, needs: String): String {
        val code = when {
            "wasn't built with" in message -> "not_declared"
            message == HIDDEN || "isn't installed or is too old" in message -> "proxmobile_missing"
            message == NO_ANSWER -> "proxmobile_no_answer"
            message == EMPTY_ANSWER -> "proxmobile_empty"
            "has no door for apps" in message -> "proxmobile_too_old"
            message.startsWith("Calling ProxMobile failed") || message.startsWith("ProxMobile failed on") ||
                message.startsWith("ProxMobile ran out of memory") -> "proxmobile_error"
            "too large for Android" in message -> "too_large"
            // ProxMobile's app lock (PcStatsProvider.LOCKED): nothing went to the PC, so it isn't "offline"
            message.startsWith("ProxAI is locked") -> "locked"
            message.startsWith("ProxMobile is still starting") -> "busy"
            "Only ProxAI-built" in message || "signed with" in message || "Install a ProxAI app" in message ||
                "Can't read this app's features" in message -> "not_trusted"
            "unknown method" in message -> "proxmobile_too_old"
            "too big" in message -> "bad_request"
            "already asking" in message -> "busy"
            else -> "unreachable"
        }
        val text = if ("unknown method" in message) "ProxMobile is too old for $what (needs $needs)" else message
        return JSONObject().put("ok", false).put("code", code).put("error", text).toString()
    }

    /** Puts the window.ProxAI script first in <head> (or at the top) of every HTML page. */
    fun injectShim(html: String): String {
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.substring(0, head.range.last + 1) + SHIM + html.substring(head.range.last + 1)
        else SHIM + html
    }

    // ProxMobile's look (ui/Theme.kt): glowing blue gradient on a near-black background.
    const val BRAND = 0xFF4DA8FF.toInt()
    const val BRAND_DEEP = 0xFF0B3D91.toInt()
    const val BACKGROUND = 0xFF0E0F13.toInt()

    /** Same JS API as ProxMobile's app runner, plus notify/remind/app/voice/listen. Calls that need a feature
     *  the app didn't declare do nothing and return false. */
    const val SHIM = """<script>(function(){
  if (window.ProxAI) return;
  var n = window.__proxaiNative, pending = {}, seq = 0;
  window.__proxaiResolve = function(id, r){ var p = pending[id]; if(!p) return; delete pending[id];
    r.ok ? p.resolve(r.text) : p.reject(new Error(r.error)); };
  window.ProxAI = {
    ask: function(prompt){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: resolve, reject: reject}; n.ask(id, String(prompt)); }); },
    speak: function(t){ return n.speak(String(t)); },
    voice: function(t){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: resolve, reject: reject}; n.voice(id, String(t)); }); },
    stopVoice: function(){ n.stopVoice(); },
    warmVoice: function(){ n.warmVoice(); },
    keepAwake: function(on){ n.keepAwake(!!on); },
    listen: function(opts){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: resolve, reject: reject}; n.listen(id, JSON.stringify(opts || {})); }); },
    stopListening: function(){ n.stopListening(); },
    toast: function(t){ n.toast(String(t)); },
    vibrate: function(ms){ return n.vibrate(ms || 30); },
    notify: function(title, text){ return n.notify(String(title || ''), String(text || '')); },
    remind: function(when, text){ var t = (when instanceof Date) ? when.getTime() : Number(new Date(when));
      return n.remind(t, String(text || '')); },
    pcStats: function(){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: function(t){ try { resolve(JSON.parse(t)); } catch(e) { reject(e); } },
        reject: reject}; n.pcStats(id); }); },
    scout: function(){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: function(t){ try { resolve(JSON.parse(t)); } catch(e) { reject(e); } },
        reject: reject}; n.scout(id); }); },
    scoutUpdate: function(change){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: function(t){ try { resolve(JSON.parse(t)); } catch(e) { reject(e); } },
        reject: reject}; n.scoutUpdate(id, JSON.stringify(change || {})); }); },
    app: (function(){ try { return JSON.parse(n.appInfo()); } catch(e) { return {}; } })()
  };
  // Prox Dump sync ("dumpsync" feature): resolves with the PC's JSON, or rejects with an Error that has
  // .code (unreachable, unauthorized, expired, not_found, conflict, blocked, bad_request, busy,
  // not_configured, not_declared, ...) and .status (the HTTP status, 0 if the PC wasn't reached).
  function dcall(o){ return new Promise(function(resolve, reject){
    var id = 'c' + (++seq);
    pending[id] = {resolve: function(t){ var r; try { r = JSON.parse(t); } catch(e) { return reject(e); }
      if (r && r.ok === false) { var e = new Error(r.error || 'Prox Dump sync error'); e.code = r.code || 'error';
        e.status = r.status || 0; return reject(e); } resolve(r); },
      reject: function(e){ if (!e.code) e.code = 'error'; e.status = 0; reject(e); }};
    n.dumpsync(id, JSON.stringify(o)); }); }
  function iso(t){ return t == null || t === '' ? '' : (t instanceof Date ? t.toISOString() : String(t)); }
  window.ProxAI.dumpsync = {
    list: function(since){ return dcall({op: 'list', since: iso(since)}); },
    setStatus: function(id, status){ return dcall({op: 'setStatus', id: String(id), status: String(status)}); },
    trash: function(){ return dcall({op: 'trash'}); },
    recover: function(id){ return dcall({op: 'recover', id: String(id)}); },
    summary: function(since){ return dcall({op: 'summary', since: iso(since)}); }
  };
  // Daily Log ("daily" feature, ProxMobile 0.7.2+): the day's note + rhythm's messages; add tasks, check, focus,
  // notes. Resolves with the PC's JSON or rejects with an Error that has .code and .status, like dumpsync.
  function lcall(o){ return new Promise(function(resolve, reject){
    var id = 'c' + (++seq);
    pending[id] = {resolve: function(t){ var r; try { r = JSON.parse(t); } catch(e) { return reject(e); }
      if (r && r.ok === false) { var e = new Error(r.error || 'Daily Log error'); e.code = r.code || 'error';
        e.status = r.status || 0; return reject(e); } resolve(r); },
      reject: function(e){ if (!e.code) e.code = 'error'; e.status = 0; reject(e); }};
    n.daily(id, JSON.stringify(o)); }); }
  function day(d){ if (d == null || d === '') return ''; if (!(d instanceof Date)) return String(d);
    var p = function(x){ return (x < 10 ? '0' : '') + x; };
    return d.getFullYear() + '-' + p(d.getMonth() + 1) + '-' + p(d.getDate()); }
  window.ProxAI.daily = {
    today: function(){ return lcall({op: 'get'}); },
    get: function(date){ return lcall({op: 'get', date: day(date)}); },
    add: function(section, items, date){ return lcall({op: 'add', section: String(section),
      items: items == null ? '' : items, date: day(date)}); },
    // v2 (ProxMobile 0.7.3): habits, nutrition, focus sessions; call() for anything newer
    setHabits: function(list){ return lcall({op: 'habits', habits: list || []}); },
    habit: function(id, action, n, date){ return lcall({op: 'habit', id: String(id), action: action || 'add',
      n: n == null ? 1 : n, date: day(date)}); },
    habitHistory: function(days){ return lcall({op: 'habit-history', days: days == null ? 30 : days}); },
    nutrition: function(meals, targets, date){ return lcall({op: 'nutrition', meals: meals || {}, targets: targets || {},
      date: day(date)}); },
    logFocus: function(s){ s = s || {}; return lcall({op: 'focus', task: s.task == null ? '' : String(s.task),
      start: iso(s.start), end: iso(s.end), done: s.done == null ? '' : String(s.done)}); },
    call: function(op, payload){ var o = {}, k; payload = payload || {};
      for (k in payload) if (Object.prototype.hasOwnProperty.call(payload, k)) o[k] = payload[k];
      o.op = String(op); return lcall(o); }
  };
  // Pipeline ("pipeline" feature, ProxMobile 0.7.7+): what Nathan chose after the scout, in four lanes (gig, company,
  // product, mine). Same promise contract as daily; call() for anything newer (decision cards, ...).
  function pcall(o){ return new Promise(function(resolve, reject){
    var id = 'c' + (++seq);
    pending[id] = {resolve: function(t){ var r; try { r = JSON.parse(t); } catch(e) { return reject(e); }
      if (r && r.ok === false) { var e = new Error(r.error || 'Pipeline error'); e.code = r.code || 'error';
        e.status = r.status || 0; return reject(e); } resolve(r); },
      reject: function(e){ if (!e.code) e.code = 'error'; e.status = 0; reject(e); }};
    n.pipeline(id, JSON.stringify(o)); }); }
  function txt(v){ return v == null ? '' : String(v); }
  window.ProxAI.pipeline = {
    list: function(lane, closed){ return pcall({op: 'list', lane: txt(lane), closed: !!closed}); },
    item: function(id){ return pcall({op: 'item', id: txt(id)}); },
    due: function(){ return pcall({op: 'due'}); },
    setStage: function(id, stage, note){ return pcall({op: 'stage', id: txt(id), stage: txt(stage), note: txt(note)}); },
    log: function(id, dir, channel, text){ return pcall({op: 'log', id: txt(id), dir: dir ? String(dir) : 'note',
      channel: channel ? String(channel) : 'note', text: txt(text)}); },
    setNext: function(id, action, due){ var o = {op: 'next', id: txt(id), action: txt(action)}, d = day(due);
      if (d) o.due = d; return pcall(o); },
    addIdea: function(title, summary, lane){ return pcall({op: 'idea', title: txt(title), summary: txt(summary),
      lane: lane ? String(lane) : 'mine'}); },
    setPersonal: function(id, personal){ return pcall({op: 'personal', id: txt(id), personal: !!personal}); },
    call: function(op, payload){ var o = {}, k; payload = payload || {};
      for (k in payload) if (Object.prototype.hasOwnProperty.call(payload, k)) o[k] = payload[k];
      o.op = String(op); return pcall(o); }
  };
  // proxDocs ("docs" feature, ProxMobile 0.9.8+): questions about ProxAI itself (how each plugin, app and tool works,
  // what changed when), answered on the PC. Its password is typed only into ProxAI's own prompt, which unlock() opens,
  // so this app never sees it or the key. Same promise contract as daily: JSON, or an Error with .code.
  function kcall(o){ return new Promise(function(resolve, reject){
    var id = 'c' + (++seq);
    pending[id] = {resolve: function(t){ var r; try { r = JSON.parse(t); } catch(e) { return reject(e); }
      if (r && r.ok === false) { var e = new Error(r.error || 'proxDocs error'); e.code = r.code || 'error';
        e.status = r.status || 0; return reject(e); } resolve(r); },
      reject: function(e){ if (!e.code) e.code = 'error'; e.status = 0; reject(e); }};
    if (o) n.docs(id, JSON.stringify(o)); else n.docsUnlock(id); }); }
  // Prox Hub ("hub" feature, ProxMobile 0.9.10+): Nathan's plan, to-dos, inbox, agents and their questions.
  // state() reads everything at once; act(name, args) runs one dashboard action; call() for anything newer.
  function hcall(o){ return new Promise(function(resolve, reject){
    var id = 'c' + (++seq);
    pending[id] = {resolve: function(t){ var r; try { r = JSON.parse(t); } catch(e) { return reject(e); }
      if (r && r.ok === false) { var e = new Error(r.error || 'Prox Hub error'); e.code = r.code || 'error';
        e.status = r.status || 0; return reject(e); } resolve(r); },
      reject: function(e){ if (!e.code) e.code = 'error'; e.status = 0; reject(e); }};
    n.hub(id, JSON.stringify(o)); }); }
  function hs(v){ return v == null ? '' : String(v); }
  window.ProxAI.hub = {
    state: function(){ return hcall({op: 'state'}); },
    act: function(name, args){ return hcall({op: 'act', name: hs(name), args: args || {}}); },
    capture: function(text){ return hcall({op: 'act', name: 'capture', args: {text: hs(text), from: 'app'}}); },
    sort: function(id, to, project, group){ return hcall({op: 'act', name: 'inbox_sort', args: {id: hs(id), to: hs(to),
      branch: hs(project), group: hs(group)}}); },
    check: function(id, done){ return hcall({op: 'act', name: 'check', args: {id: hs(id), done: done !== false}}); },
    answer: function(id, allow, message, stop){ return hcall({op: 'act', name: 'answer', args: {id: hs(id), allow: allow === true,
      message: hs(message), stop: !!stop}}); },
    call: function(op, payload){ var o = {}, k; payload = payload || {};
      for (k in payload) if (Object.prototype.hasOwnProperty.call(payload, k)) o[k] = payload[k];
      o.op = String(op); return hcall(o); },
    save: function(session, key){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq);
      pending[id] = {resolve: function(t){ var r; try { r = JSON.parse(t); } catch(e) { return reject(e); }
        if (r && r.ok === false) { var e = new Error(r.error || 'Save failed'); e.code = r.code || 'error'; return reject(e); } resolve(r); },
        reject: function(e){ if (!e.code) e.code = 'error'; reject(e); }};
      n.hubSave(id, JSON.stringify({session: hs(session), key: hs(key)})); }); }
  };
  window.ProxAI.docs = {
    status: function(){ return kcall({op: 'status'}); },
    unlock: function(){ return kcall(null); },
    ask: function(question){ return kcall({op: 'ask', question: txt(question)}); },
    load: function(){ return kcall({op: 'load'}); },
    lock: function(){ return kcall({op: 'lock'}); }
  };
  // Study Coach ("tutor" feature): every call resolves with the plugin's answer, or rejects with its error.
  function tcall(method, route, body){ return new Promise(function(resolve, reject){
    var id = 'c' + (++seq);
    pending[id] = {resolve: function(t){ var r; try { r = JSON.parse(t); } catch(e) { return reject(e); }
      if (r && r.ok === false) return reject(new Error(r.error || 'Study Coach error')); resolve(r); }, reject: reject};
    n.tutor(id, JSON.stringify({method: method, route: route, body: body || {}})); }); }
  function nb(id){ return '/notebooks/' + encodeURIComponent(String(id)); }
  // Follows a tutor reply while it's written: onText(textSoFar) as it arrives, then onText(text, true).
  function follow(id, tid, rid, onText){ return new Promise(function(resolve, reject){
    var last = null, t0 = Date.now();
    (function poll(){ tcall('GET', nb(id) + '/chats/' + tid).then(function(r){
      var th = r.thread, p = th.pending || {};
      if (p.id === rid && !p.done) {
        if (onText && p.text && p.text !== last) { last = p.text; try { onText(p.text, false); } catch(e) {} }
        if (Date.now() - t0 > 150000) return reject(new Error('The tutor took too long; try again'));
        return setTimeout(poll, 400); }
      if (p.error) return reject(new Error(p.error));
      var msg = th.messages[th.messages.length - 1];
      if (onText && msg) try { onText(msg.text, true); } catch(e) {}
      resolve(th); }, reject); })(); }); }
  function talk(id, route, body, onText){ return tcall('POST', nb(id) + route, body).then(function(r){
    if (!r.reply) return r.thread || r;
    return follow(id, r.thread.id, r.reply, onText); }); }
  window.ProxAI.tutor = {
    get: function(route){ return tcall('GET', route); },
    post: function(route, body){ return tcall('POST', route, body); },
    notebooks: function(){ return tcall('GET', '/notebooks'); },
    create: function(o){ return tcall('POST', '/notebooks', o); },
    notebook: function(id){ return tcall('GET', nb(id)); },
    update: function(id, changes){ return tcall('POST', nb(id) + '/update', changes); },
    remove: function(id){ return tcall('POST', nb(id) + '/delete', {confirm: true}); },
    plan: function(id){ return tcall('GET', nb(id) + '/plan'); },
    addVault: function(id, path){ return tcall('POST', nb(id) + '/sources', {kind: 'vault', path: path}); },
    addText: function(id, title, text){ return tcall('POST', nb(id) + '/sources', {kind: 'text', title: title, text: text}); },
    addLink: function(id, url){ return tcall('POST', nb(id) + '/sources', {kind: 'link', url: url}); },
    removeSource: function(id, sid){ return tcall('POST', nb(id) + '/sources/' + sid + '/delete'); },
    build: function(id){ return tcall('POST', nb(id) + '/build'); },
    start: function(id, o){ return tcall('POST', nb(id) + '/start', o); },
    next: function(id, session){ return tcall('POST', nb(id) + '/next', {session: session}); },
    answer: function(id, o){ return tcall('POST', nb(id) + '/answer', o); },
    hint: function(id, session, card){ return tcall('POST', nb(id) + '/hint', {session: session, card: card}); },
    skip: function(id, session, card, reason){ return tcall('POST', nb(id) + '/skip', {session: session, card: card, reason: reason}); },
    ask: function(id, question, session, card){ return tcall('POST', nb(id) + '/ask', {question: question, session: session, card: card}); },
    end: function(id, session){ return tcall('POST', nb(id) + '/end', {session: session}); },
    overview: function(id){ return tcall('GET', nb(id) + '/overview'); },
    makeOverview: function(id, words){ return tcall('POST', nb(id) + '/overview', {words: words || 600}); },
    // Learn mode: Today, the course (units), lessons, teach-back, tutor chat.
    today: function(){ return tcall('GET', '/today'); },
    course: function(id){ return tcall('GET', nb(id) + '/course'); },
    learnNext: function(id){ return tcall('GET', nb(id) + '/learn-next'); },
    lesson: function(id, concept){ return tcall('GET', nb(id) + '/lessons/' + concept); },
    lessonCheck: function(id, concept, choice){ return tcall('POST', nb(id) + '/lessons/' + concept + '/check', {choice: choice}); },
    teachBack: function(id, concept, text){ return tcall('POST', nb(id) + '/lessons/' + concept + '/teachback', {text: text}); },
    chats: function(id){ return tcall('GET', nb(id) + '/chats'); },
    thread: function(id, thread){ return tcall('GET', nb(id) + '/chats/' + thread); },
    deleteChat: function(id, thread){ return tcall('POST', nb(id) + '/chats/' + thread + '/delete'); },
    // o = {message, thread?, concept?, session?, card?}; resolves with the whole thread when the reply is done.
    chat: function(id, o, onText){ return talk(id, '/chat', o, onText); },
    // Problem-solving tutor: every help request needs a stage (1-6); without one it resolves {need_stage, stages}.
    problems: function(id){ return tcall('GET', nb(id) + '/problems'); },
    newProblem: function(id, o){ return tcall('POST', nb(id) + '/problems', o); },
    problem: function(id, pid){ return tcall('GET', nb(id) + '/problems/' + pid); },
    help: function(id, pid, o, onText){ return talk(id, '/problems/' + pid + '/help', o, onText); },
    problemDone: function(id, pid, solved, note){ return tcall('POST', nb(id) + '/problems/' + pid + '/done', {solved: solved !== false, note: note || ''}); },
    reopenProblem: function(id, pid){ return tcall('POST', nb(id) + '/problems/' + pid + '/reopen'); },
    // Trust + metacognition: disputes, mistakes, insights, reflection, self-efficacy check-in.
    dispute: function(id, card, why, session){ return tcall('POST', nb(id) + '/dispute', {card: card, why: why || '', session: session}); },
    mistakes: function(id){ return tcall('GET', nb(id) + '/mistakes'); },
    insights: function(id){ return tcall('GET', nb(id) + '/insights'); },
    reflect: function(id, session, answers, efficacy){ return tcall('POST', nb(id) + '/reflect', {session: session, answers: answers, efficacy: efficacy}); },
    checkin: function(){ return tcall('GET', '/checkin'); },
    saveCheckin: function(answers){ return tcall('POST', '/checkin', {answers: answers}); },
    // Code critic: o = {files: {main: code, ...}, language?: 'python'|'java', tests?: testFileText, notebook?, explain?}.
    codeReview: function(o){ return tcall('POST', '/code/review', o); },
    setCoding: function(id, language){ return tcall('POST', nb(id) + '/update', {coding: language || null}); },
    stack: function(){ return tcall('GET', '/stack'); },
    stackNotebook: function(component){ return tcall('POST', '/stack/notebook', {component: component}); },
    // A File from <input type="file"> (needs "files" too). Sent in pieces; onProgress(sent, total).
    upload: function(id, file, onProgress){ return new Promise(function(resolve, reject){
      if (!file || file.size > 25 * 1024 * 1024) return reject(new Error('Files can be up to 25 MB'));
      var fr = new FileReader();
      fr.onerror = function(){ reject(new Error("Couldn't read that file")); };
      fr.onload = function(){
        var b64 = String(fr.result).split(',')[1] || '', CH = 192000, total = Math.max(1, Math.ceil(b64.length / CH));
        var up = ''; for (var k = 0; k < 16; k++) up += '0123456789abcdef'[Math.floor(Math.random() * 16)];
        (function send(i){
          tcall('POST', nb(id) + '/upload', {upload_id: up, index: i, total: total, name: file.name, size: file.size,
            data: b64.slice(i * CH, (i + 1) * CH)}).then(function(r){
              if (onProgress) try { onProgress(i + 1, total); } catch(e) {}
              if (i + 1 < total) send(i + 1); else resolve(r);
            }, reject);
        })(0);
      };
      fr.readAsDataURL(file); }); }
  };
})();</script>"""
}
