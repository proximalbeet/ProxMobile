package com.proxai.hermes.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * A browser waiting to log in to the Workbench (the AI-engineering IDE on the PC), as the PC lists it: a label
 * like "Chrome on Linux" and three numbers to pick from. The right number is never sent here; only the browser
 * that asked shows it.
 */
data class WorkbenchLogin(val id: String, val label: String, val choices: List<Int>, val seq: Long = 0)

/** An answer from the Workbench that isn't a success; [code] is one of the shared error codes. */
class WorkbenchException(val code: String, message: String, val status: Int = 0) : IOException(message)

/** What one long poll brought back: the logins waiting now, and which run of the Workbench sent them. */
data class WorkbenchWait(val epoch: String, val logins: List<WorkbenchLogin>)

/** What a listener has popped up already: the Workbench's run (epoch) and the last request number it saw. */
data class ListenerCursor(val epoch: String = "", val after: Long = 0) {
    /** The logins to pop up now, and the cursor after them. Another epoch (the Workbench restarted) counts from 0. */
    fun next(answer: WorkbenchWait): Pair<List<WorkbenchLogin>, ListenerCursor> {
        val from = if (answer.epoch == epoch) after else 0L
        val fresh = answer.logins.filter { it.seq > from }
        return fresh to ListenerCursor(answer.epoch, maxOf(from, answer.logins.maxOfOrNull { it.seq } ?: 0L))
    }
}

/**
 * Workbench logins (0.9.3). When a browser asks to log in to https://<server>/workbench/, ProxMobile shows three
 * numbers and sends the one Nathan taps, or null for "Not me":
 *
 *   GET  <server>/workbench/api/logins                           -> {"logins": [{"id", "label", "choices"}]}
 *   POST <server>/workbench/api/logins/<id>  {"number": n | null} -> {"state": "approved" | "refused"}
 *
 * Both carry the Workbench's PHONE_KEY as a Bearer key, paired by scanning `workbench.py phone-key` on the PC.
 * ProxMobile's own feature: there is no ProxAI.* bridge for it, on purpose. Hermes builds the PC-built apps, and
 * nothing Hermes makes may ever let a browser into the IDE.
 */
class WorkbenchClient(
    private val baseUrl: String,
    private val key: String,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
    /** Paired, and only ever over HTTPS, so the key never goes out in the clear. */
    val configured: Boolean get() = baseUrl.startsWith("https://") && validKey(key)

    fun waiting(): List<WorkbenchLogin> = parse(send(request("$baseUrl/api/logins").get().build()).optJSONArray("logins"))

    /**
     * The long poll PcListener keeps open (0.9.4): the Workbench answers as soon as a login numbered after
     * [after] is waiting, or after [waitSeconds] with what's waiting. A different [epoch] (it restarted since)
     * gets the list at once.
     */
    fun waitForLogins(after: Long, epoch: String, waitSeconds: Int = 25): WorkbenchWait {
        val longer = http.newBuilder().readTimeout((waitSeconds + 15).toLong(), TimeUnit.SECONDS).build()
        val url = "$baseUrl/api/logins?wait=$waitSeconds&after=$after&epoch=${java.net.URLEncoder.encode(epoch, "UTF-8")}"
        val json = send(request(url).get().build(), longer)
        return WorkbenchWait(json.optString("epoch"), parse(json.optJSONArray("logins")))
    }

    /** Sends the tapped number, or null for "Not me". Returns "approved" or "refused". */
    fun answer(id: String, number: Int?): String {
        require(ID.matches(id)) { "not a Workbench login id" }
        val body = JSONObject().put("number", number ?: JSONObject.NULL).toString().toRequestBody(JSON)
        return send(request("$baseUrl/api/logins/$id").post(body).build()).optString("state")
    }

    private fun request(url: String) = Request.Builder().url(url).header("Authorization", "Bearer $key")

    private fun send(req: Request, client: OkHttpClient = http): JSONObject = client.newCall(req).execute().use { r ->
        val json = runCatching { JSONObject(r.body?.string().orEmpty()) }.getOrNull()
        if (!r.isSuccessful) {
            throw WorkbenchException(codeFor(r.code), json?.optString("error").orEmpty().ifBlank { "The Workbench answered ${r.code}" }, r.code)
        }
        json ?: throw WorkbenchException("bad_request", "The Workbench's answer wasn't JSON", r.code)
    }

    companion object {
        val ID = Regex("^[A-Za-z0-9_-]{16}$")
        private val KEY = Regex("^[A-Za-z0-9_-]{32,128}$")
        private val JSON = "application/json".toMediaType()
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()

        fun from(settings: Settings) = WorkbenchClient(settings.workbenchBaseUrl, settings.workbenchKey)

        /** What a pairing link may carry: the PC's token_urlsafe key and nothing else. */
        fun validKey(key: String) = KEY.matches(key)

        fun codeFor(status: Int) = when (status) {
            400 -> "bad_request"
            401 -> "unauthorized"
            403 -> "blocked"
            404 -> "not_found"
            409 -> "conflict"
            410 -> "expired"
            503 -> "busy"
            else -> "unreachable"
        }

        /** The PC's list, keeping only well-formed entries: an id and exactly three numbers from 10 to 99. */
        fun parse(list: JSONArray?): List<WorkbenchLogin> {
            if (list == null) return emptyList()
            return (0 until list.length()).mapNotNull { i ->
                val o = list.optJSONObject(i) ?: return@mapNotNull null
                val raw = o.optJSONArray("choices") ?: return@mapNotNull null
                val choices = (0 until raw.length()).map { raw.opt(it) }.filterIsInstance<Int>().filter { it in 10..99 }
                val id = o.optString("id")
                if (!ID.matches(id) || raw.length() != 3 || choices.size != 3) null
                else WorkbenchLogin(id, o.optString("label").take(60).ifBlank { "A browser" }, choices, o.optLong("seq"))
            }
        }
    }
}

/**
 * Asks the Workbench every few seconds, while ProxMobile is on screen and unlocked, whether a browser is waiting
 * to log in. MainActivity runs [watch] only then, and prompts for the first one.
 */
class WorkbenchLogins(private val settings: Settings) {
    private val _waiting = MutableStateFlow<List<WorkbenchLogin>>(emptyList())
    val waiting: StateFlow<List<WorkbenchLogin>> = _waiting

    /** Answered or put away here: not shown again (the PC drops them within two minutes anyway). */
    private val done = mutableSetOf<String>()

    suspend fun watch() {
        while (true) {
            val client = WorkbenchClient.from(settings)
            val now = if (!client.configured) emptyList()
                else withContext(Dispatchers.IO) { runCatching { client.waiting() }.getOrDefault(emptyList()) }
            _waiting.value = now.filter { it.id !in done }
            delay(POLL_MS)
        }
    }

    /** Sends the answer: the PC's "approved" or "refused", or why it couldn't be sent. */
    suspend fun answer(login: WorkbenchLogin, number: Int?): Result<String> {
        putAway(login)
        return withContext(Dispatchers.IO) { runCatching { WorkbenchClient.from(settings).answer(login.id, number) } }
    }

    fun putAway(login: WorkbenchLogin) {
        done += login.id
        _waiting.value = _waiting.value.filter { it.id != login.id }
    }

    companion object {
        const val POLL_MS = 3_000L
    }
}
