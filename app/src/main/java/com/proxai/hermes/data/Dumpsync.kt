package com.proxai.hermes.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Prox Dump sync (Hermes plugin proxai-dumpsync) for ProxAI-built apps with the "dumpsync" feature.
 *
 * Same door as the scout and the Study Coach: the app's shell calls PcStatsProvider (method
 * "dumpsync"), which checks the app, then calls this. The difference is where the PC side lives:
 * proxai-dumpsync is its own small server (tailscale serve /dumpsync) with its own token, so the
 * base URL and DUMPSYNC_TOKEN are ProxMobile settings. Neither ever reaches the app: the app sends
 * {"op", ...} and gets the server's JSON back, or a structured error:
 *
 *   {"ok": false, "code": "unreachable" | "unauthorized" | "expired" | ..., "error": "message", "status": 401}
 *
 * The shim turns that into a rejected Promise whose Error has .code and .status.
 */
class DumpsyncClient(
    private val baseUrl: String,
    private val token: String,
    private val http: OkHttpClient = DEFAULT_HTTP,
    /** HUB-APP-ONLY (0.9.15): the second token Nathan's own presses need (Settings → Owner token). Sent only with an
     *  owner app's calls ([OWNER_APPS]), as X-ProxAI-Owner-Token; never logged, never handed to an app. */
    private val ownerToken: String = "",
) {
    companion object {
        val STATUSES = setOf("new", "reviewed", "done", "trash")
        val OPS = setOf("list", "setStatus", "trash", "recover", "summary")
        /** ISO 8601 date or date-time, e.g. 2026-10-26, 2026-10-26T14:30:00Z, 2026-10-26T14:30:00.123-04:00 */
        val SINCE = Regex("^\\d{4}-\\d{2}-\\d{2}([T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d{1,9})?)?(Z|[+-]\\d{2}:?\\d{2})?)?$")
        const val MAX_ID = 512
        const val MAX_ARG = 8192
        /** Daily Log (ProxAI.daily, 0.7.2): same server and token, routes /daily. "get" and "add" are checked
         *  here; any other op name (0.7.3: habits, habit, habit-history, nutrition, focus, and later ones) goes
         *  to POST /daily/<op> as is, and the PC checks it, so new daily features need no new ProxMobile. */
        val DAILY_OP = Regex("^[a-z][a-z0-9-]{1,29}$")
        val DAILY_SECTIONS = setOf("tasks", "check", "focus", "notes")
        val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
        const val MAX_DAILY_ARG = 32_768     // 20 items x 500 characters, JSON-escaped

        /** Round 4 (0.9.15): no redirects, so the dumpsync and owner tokens only ever go to the URL in Settings, never
         *  to a host a 3xx names. A redirect comes back to the app as an error. */
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /** The most of the PC's own refusal text (a 403) the app is shown. */
        const val MAX_REFUSAL = 300

        /** From ProxMobile's settings: the dumpsync URL if set, else <server>/dumpsync (tailscale serve). */
        fun from(settings: Settings) =
            DumpsyncClient(settings.dumpsyncBaseUrl, settings.dumpsyncToken, ownerToken = settings.ownerToken)

        /** HUB-APP-ONLY: the one app that may use Prox Hub (Nathan's presses and answers). Every app check here is
         *  against the package Android names for the Binder caller, never against anything the app sends. */
        const val HUB_APP = "com.proxai.apps.prox_hub"
        const val BOARDROOM_APP = "com.proxai.apps.boardroom"
        /** Nathan's own apps: they get the owner token and every Pipeline op, but only when signed with his owner
         *  key (OWNER-SIGN, 0.9.16: [OwnerSign]; the `owner` flag below). */
        val OWNER_APPS = setOf(HUB_APP, BOARDROOM_APP)
        const val NOT_HUB_APP = "Only the Prox Hub app can use Prox Hub."
        const val HUB_NOT_OWNER_SIGNED = "Prox Hub only answers the Prox Hub app signed with your owner key. Pin the " +
            "key's fingerprint in ProxMobile (Settings → Owner apps) and install the owner-signed Prox Hub."

        /** Why [caller] (Android's name for the calling app) may not use Prox Hub, or null. [owner] = Android says
         *  it's signed with the pinned owner key ([OwnerSign.isOwner]). Unit-tested. */
        fun hubRefusal(caller: String?, owner: Boolean): String? = when {
            caller != HUB_APP -> NOT_HUB_APP
            !owner -> HUB_NOT_OWNER_SIGNED
            else -> null
        }

        /** The Pipeline ops any app with the "pipeline" feature may call: summary reads. "money" only without an id
         *  (the totals). Everything else (writes, item, cards, mail, agents, week, plans, client_update, ...) is for
         *  [OWNER_APPS] only. */
        val PIPELINE_READS = setOf("list", "due", "clients", "client", "money")
        const val PIPELINE_READ_ONLY = "This app can only read the Pipeline's summaries (list, due, clients, client, " +
            "money totals). Changes and details are for Prox Hub and BoardRoom."
        /** Pipeline op names: like Daily's, plus "_" (client_update). Still one plain path segment. */
        val PIPELINE_OP = Regex("^[a-z][a-z0-9_-]{1,29}$")

        /** Why [caller] may not call Pipeline [op] with request [o], or null. [owner]: signed with the pinned owner
         *  key; an owner app's name without it gets what any other app gets. Unit-tested. */
        fun pipelineRefusal(caller: String?, op: String, o: JSONObject, owner: Boolean): String? = when {
            caller == null -> "Only ProxAI-built apps can use the Pipeline"
            caller in OWNER_APPS && owner -> null
            op !in PIPELINE_READS -> PIPELINE_READ_ONLY
            op == "money" && o.has("id") -> PIPELINE_READ_ONLY     // one item's money is a detail, not a total
            else -> null
        }

        const val BAD_OWNER_TOKEN = "The Owner token in ProxMobile's settings has characters a token can't have. " +
            "Enter it again."

        /** A header value OkHttp will send as is: printable ASCII, no spaces. */
        private fun headerSafe(v: String) = v.all { it.code in 0x21..0x7e }

        /** The structured error the app sees. */
        fun error(code: String, message: String, status: Int = 0): String =
            JSONObject().put("ok", false).put("code", code).put("error", message).apply {
                if (status > 0) put("status", status)
            }.toString()

        /** Error code for an HTTP answer from the dumpsync server (or whatever is in front of it). Unit-tested. */
        fun codeFor(status: Int, serverJson: Boolean): String = when {
            status == 401 -> "unauthorized"
            status == 403 -> "blocked"
            status == 404 && serverJson -> "not_found"
            status == 404 || status == 502 || status == 503 && !serverJson || status == 504 -> "unreachable"
            status == 409 -> "conflict"
            status == 410 -> "expired"
            status == 400 -> "bad_request"
            status == 503 -> "busy"
            else -> "server_error"
        }

        private fun friendly(code: String, serverMessage: String): String = when (code) {
            "unauthorized" -> "The Prox Dump sync token is wrong or missing. Check it in ProxMobile's settings."
            "unreachable" -> "Can't reach Prox Dump sync on the PC (is the PC on and on the tailnet?)"
            "expired" -> "Too late to recover: notes can only be recovered within 24 hours of trashing."
            "busy" -> "The vault is busy (a sync is running). Try again in a minute."
            "blocked" -> "That note is outside the folders Prox Dump can use."
            else -> serverMessage.ifBlank { "Prox Dump sync error" }
        }
    }

    val isConfigured: Boolean get() = token.isNotBlank() && baseUrl.toHttpUrlOrNull() != null

    /** One call from an app: [arg] = {"op": "list"|"setStatus"|"trash"|"recover"|"summary", "since"?, "id"?, "status"?}.
     *  Always returns JSON text: the server's answer, or a structured error. Never throws. */
    fun call(arg: String): String {
        if (arg.length > MAX_ARG) return error("bad_request", "That request is too big")
        val o = runCatching { JSONObject(arg) }.getOrElse { return error("bad_request", "Bad Prox Dump request") }
        val op = o.optString("op")
        if (op !in OPS) return error("bad_request", "Unknown Prox Dump call '$op'")
        if (!isConfigured) return error("not_configured",
            "Prox Dump sync isn't set up in ProxMobile (Settings → Prox Dump sync: token)")
        return try {
            when (op) {
                "list" -> list(since(o))
                "summary" -> summary(since(o) ?: throw BadInput("summary needs a since time (the last sync's server_time)"))
                "trash" -> trash()
                "setStatus" -> setStatus(id(o), o.optString("status").also {
                    if (it !in STATUSES) throw BadInput("status must be new, reviewed, done or trash")
                })
                else -> recover(id(o))
            }
        } catch (e: BadInput) {
            error("bad_request", e.message ?: "Bad request")
        } catch (e: IOException) {
            error("unreachable", friendly("unreachable", ""))
        } catch (e: Exception) {
            error("unreachable", friendly("unreachable", ""))
        }
    }

    /** One Daily Log call from an app: [arg] = {"op", ...}. get -> GET /daily?date=, add -> POST /daily
     *  ({"section", "items", "date"?}), anything else -> POST /daily/<op> with the rest of [arg] as the body.
     *  Same JSON-or-structured-error contract as [call]. */
    fun daily(arg: String): String {
        if (arg.length > MAX_DAILY_ARG) return error("bad_request", "That request is too big")
        val o = runCatching { JSONObject(arg) }.getOrElse { return error("bad_request", "Bad Daily Log request") }
        val op = o.optString("op")
        if (!DAILY_OP.matches(op)) return error("bad_request", "Unknown Daily Log call '$op'")
        if (!isConfigured) return error("not_configured",
            "Daily Log uses Prox Dump sync, which isn't set up in ProxMobile (Settings → Prox Dump sync: token)")
        return try {
            val date = date(o)
            if (op == "get") send(url("daily", query = date?.let { "date" to it }))
            else if (op != "add") send(url("daily", op), JSONObject(arg).apply { remove("op") }.toString())
            else {
                val section = o.optString("section")
                if (section !in DAILY_SECTIONS) throw BadInput("section must be tasks, check, focus or notes")
                val items = when (val v = o.opt("items")) {
                    is String -> v
                    is org.json.JSONArray -> v.also { a ->
                        for (i in 0 until a.length()) if (a.opt(i) !is String) throw BadInput("items must be text or a list of text")
                    }
                    else -> throw BadInput("items must be text or a list of text")
                }
                val body = JSONObject().put("section", section).put("items", items)
                date?.let { body.put("date", it) }
                send(url("daily"), body.toString())
            }
        } catch (e: BadInput) {
            error("bad_request", e.message ?: "Bad request")
        } catch (e: Exception) {
            error("unreachable", friendly("unreachable", ""))
        }
    }

    /** One Prox Hub call from an app (ProxAI.hub, 0.9.10): [arg] = {"op", ...} -> POST /hub/<op> with the rest of
     *  [arg] as the body, on Prox Dump sync's door and token. The PC checks everything; new hub features need no new
     *  ProxMobile.
     *
     *  HUB-APP-ONLY (0.9.15): [caller] is the package Android names for the calling app (PcStatsProvider's
     *  callingPackage, never from [arg]). Any app but [HUB_APP] gets nothing, before the PC is asked. The Prox Hub
     *  app's calls carry X-ProxAI-App: <caller> and, when set, X-ProxAI-Owner-Token. A 403 shows the PC's own text.
     *
     *  OWNER-SIGN (0.9.16): [owner] = Android reports the installed Prox Hub is signed with the owner key Nathan
     *  pinned ([OwnerSign.isOwner]); without it nothing reaches the PC. */
    fun hub(arg: String, caller: String?, owner: Boolean = false): String {
        hubRefusal(caller, owner)?.let { return error("blocked", it) }
        if (arg.length > MAX_DAILY_ARG) return error("bad_request", "That request is too big")
        val o = runCatching { JSONObject(arg) }.getOrElse { return error("bad_request", "Bad Prox Hub request") }
        val op = o.optString("op")
        if (!DAILY_OP.matches(op)) return error("bad_request", "Unknown Prox Hub call '$op'")
        if (!isConfigured) return error("not_configured",
            "Prox Hub uses Prox Dump sync, which isn't set up in ProxMobile (Settings → Prox Dump sync: token)")
        val headers = appHeaders(caller!!, owner) ?: return error("bad_request", BAD_OWNER_TOKEN)
        return try {
            send(url("hub", op), JSONObject(arg).apply { remove("op") }.toString(), headers,
                refused = "Prox Hub on the PC refused that. Check the Owner token in ProxMobile's settings.")
        } catch (e: Exception) {
            error("unreachable", friendly("unreachable", ""))
        }
    }

    /** One Pipeline call from an app (ProxAI.pipeline, 0.7.7): [arg] = {"op", ...} -> POST /pipeline/<op> with the
     *  rest of [arg] as the body. ProxMobile only checks the op's shape and size; the PC's proxai-pipeline checks
     *  everything else, so new pipeline features (decision cards, ...) need no new ProxMobile.
     *
     *  HUB-APP-ONLY (0.9.15): [caller] is Android's name for the calling app (never from [arg]). Every call carries
     *  X-ProxAI-App: <caller>; an owner app's also carries the owner token. Any other app gets only the summary reads
     *  ([PIPELINE_READS]), refused here before the PC is asked. A 403 shows the PC's own text.
     *
     *  OWNER-SIGN (0.9.16): "owner app" now also needs [owner] (signed with the pinned owner key, [OwnerSign]); a
     *  Prox Hub or BoardRoom without it is treated like any other app: summary reads, no owner token. */
    fun pipeline(arg: String, caller: String?, owner: Boolean = false): String {
        if (arg.length > MAX_DAILY_ARG) return error("bad_request", "That request is too big")
        val o = runCatching { JSONObject(arg) }.getOrElse { return error("bad_request", "Bad Pipeline request") }
        val op = o.optString("op")
        if (!PIPELINE_OP.matches(op)) return error("bad_request", "Unknown Pipeline call '$op'")
        pipelineRefusal(caller, op, o, owner)?.let { return error("blocked", it) }
        if (!isConfigured) return error("not_configured",
            "The Pipeline uses Prox Dump sync, which isn't set up in ProxMobile (Settings → Prox Dump sync: token)")
        val headers = appHeaders(caller!!, owner) ?: return error("bad_request", BAD_OWNER_TOKEN)
        return try {
            send(url("pipeline", op), JSONObject(arg).apply { remove("op") }.toString(), headers,
                refused = "The Pipeline on the PC refused that.")
        } catch (e: Exception) {
            error("unreachable", friendly("unreachable", ""))
        }
    }

    private fun date(o: JSONObject): String? {
        val s = if (o.isNull("date")) "" else o.optString("date").trim()
        if (s.isEmpty()) return null
        if (!DATE.matches(s)) throw BadInput("date must be YYYY-MM-DD")
        return s
    }

    private class BadInput(msg: String) : Exception(msg)

    private fun since(o: JSONObject): String? {
        val s = if (o.isNull("since")) "" else o.optString("since").trim()
        if (s.isEmpty()) return null
        if (!SINCE.matches(s)) throw BadInput("since must be an ISO time like 2026-10-26T14:30:00Z")
        return s
    }

    private fun id(o: JSONObject): String {
        val id = o.optString("id")
        if (id.isBlank() || id.length > MAX_ID || id.any { it.code < 32 }) throw BadInput("Bad note id")
        return id
    }

    private fun url(vararg segments: String, query: Pair<String, String>? = null): HttpUrl {
        val b = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()!!.newBuilder()
        segments.forEach { b.addPathSegment(it) }      // a note id's "/" becomes %2F (one segment)
        query?.let { b.addQueryParameter(it.first, it.second) }
        return b.build()
    }

    fun list(since: String?): String = send(url("dumps", query = since?.let { "since" to it }))
    fun summary(since: String): String = send(url("summary", query = "since" to since))
    fun trash(): String = send(url("trash"))
    fun setStatus(id: String, status: String): String =
        send(url("dumps", id, "status"), JSONObject().put("status", status).toString())
    fun recover(id: String): String = send(url("dumps", id, "recover"), "{}")

    /** X-ProxAI-App: [caller] (Android's name for it), plus X-ProxAI-Owner-Token for an owner app signed with the
     *  pinned owner key ([signed]) only, when set. Null when that token can't be sent as a header. */
    private fun appHeaders(caller: String, signed: Boolean): Map<String, String>? {
        val owner = caller in OWNER_APPS && signed && ownerToken.isNotEmpty()
        if (owner && !headerSafe(ownerToken)) return null
        return buildMap<String, String> {
            put("X-ProxAI-App", caller)
            if (owner) put("X-ProxAI-Owner-Token", ownerToken)
        }
    }

    /** [refused]: for Prox Hub and the Pipeline, a 403 is the PC saying why it won't take that call (which app, the
     *  owner token), so the app sees the PC's own words, or [refused] when it sent none; never Prox Dump's
     *  note-folder message. */
    private fun send(url: HttpUrl, postBody: String? = null, headers: Map<String, String> = emptyMap(),
                     refused: String? = null): String {
        val b = Request.Builder().url(url).header("Authorization", "Bearer $token")
        headers.forEach { (k, v) -> b.header(k, v) }
        if (postBody != null) b.post(postBody.toRequestBody("application/json".toMediaType()))
        http.newCall(b.build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            val json = runCatching { JSONObject(body) }.getOrNull()
            if (r.isSuccessful && json != null) return body
            if (r.isSuccessful) return error("server_error", "Bad answer from Prox Dump sync", r.code)
            val code = codeFor(r.code, json?.has("ok") == true)
            val said = json?.optString("error").orEmpty()
            if (refused != null && code == "blocked")
                return error(code, said.trim().take(MAX_REFUSAL).ifBlank { refused }, r.code)
            return error(code, friendly(code, said), r.code)
        }
    }
}
