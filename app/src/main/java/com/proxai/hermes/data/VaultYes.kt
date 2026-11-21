package com.proxai.hermes.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * VAULT-UNDO-APP (0.9.18): every vault change Nathan approved (VAULT-YES, on Prox Dump sync's door), shown on the
 * phone with a one-tap Undo. Built against dumpsync's /vault-yes/... contract:
 *
 *   GET  <dumpsync>/vault-yes/written?since=ISO
 *        -> {"ok": true, "server_time": ISO, "items": [{"id", "path", "how", "approved_by", "can_undo", "time"}]}
 *   POST <dumpsync>/vault-yes/<id>/undo   -> 200 done; 409 the note changed since; 503 log folder missing or over its cap
 *
 * Both carry the Prox Dump sync bearer, X-ProxAI-App: com.proxai.hermes (ProxMobile itself) and
 * X-ProxAI-Owner-Token (Settings → Owner apps). Only the path, how and who approved it are ever shown, never a
 * note's text: nothing else from an item is read.
 */
data class VaultChange(
    val id: String,
    /** Already escaped for showing ([VaultYes.escape]). */
    val path: String,
    /** One plain word or two ([VaultYes.how]). */
    val how: String,
    /** One of [VaultYes.WORDS]. */
    val approvedBy: String,
    val canUndo: Boolean,
    val time: String,
    /** Another entry in the same answer has this id: listed, marked, never offered an Undo. */
    val sharedId: Boolean = false,
) {
    /** The one line shown for it, in the notification and the list. */
    val line: String get() = "📝 $path $how (from your $approvedBy)"
    /** Shown under [line] for an entry that shares its id; "" otherwise. */
    val mark: String get() = if (sharedId) VaultYes.SHARED_MARK else ""
    val undoable: Boolean get() = canUndo && !sharedId && VaultYes.validId(id)
    /** What tells one entry from another for "already notified" (id alone isn't unique: [sharedId]). */
    val key: String get() = "$id|$time|$path"
}

data class VaultWritten(val items: List<VaultChange>, val serverTime: String)

/** A GET or an undo: [ok] with [message] to show plainly either way. */
data class VaultAnswer(val ok: Boolean, val message: String, val status: Int = 0, val written: VaultWritten? = null)

object VaultYes {
    const val APP = "com.proxai.hermes"
    val WORDS = listOf("chat", "call", "terminal", "other")
    private val HOW = Regex("^[a-z][a-z -]{0,23}$")
    const val MAX_PATH = 160
    const val MAX_ITEMS = 200
    /** How many "already notified" keys the phone keeps. */
    const val MAX_SEEN = 400

    /** How many undoable changes the phone remembers for a notification's Undo. */
    const val MAX_PENDING = 100

    const val SHARED_MARK = "Same id as another entry: no Undo. Check it on the PC."
    const val CONFLICT ="Not undone: the note changed since. Open it and change it back by hand."
    const val NOT_SET_UP = "Not undone: vault undo isn't set up on the PC (its log folder is missing or over its cap)."
    const val LIST_NOT_SET_UP = "Vault changes aren't set up on the PC (its log folder is missing or over its cap)."
    const val NO_TOKENS = "Needs the Prox Dump sync token and the Owner token (Settings → PC links)."
    const val BAD_TOKEN = "The Owner token or Prox Dump sync token has characters a token can't have. Enter it again."
    const val UNREACHABLE = "Can't reach Prox Dump sync on the PC (is the PC on and on the tailnet?)"

    /** The approver as one of the fixed words; anything else the PC sends reads "other". */
    fun word(raw: Any?): String = (raw as? String)?.trim()?.lowercase()?.takeIf { it in WORDS } ?: "other"

    /** How it changed, as the PC said it when it's a plain lower-case word or two, else "changed". */
    fun how(raw: Any?): String = (raw as? String)?.trim()?.takeIf { HOW.matches(it) } ?: "changed"

    /** A path made safe to show: control, format (bidi, zero-width) and line characters become visible \uXXXX;
     *  anything past [MAX_PATH] characters is cut with "…". Never empty. */
    fun escape(raw: Any?): String {
        val s = (raw as? String).orEmpty()
        val out = StringBuilder()
        for (ch in s) {
            val t = Character.getType(ch)
            val hide = ch.code < 0x20 || ch.code == 0x7f || t == Character.CONTROL.toInt() || t == Character.FORMAT.toInt() ||
                t == Character.LINE_SEPARATOR.toInt() || t == Character.PARAGRAPH_SEPARATOR.toInt()
            if (hide) out.append("\\u").append(String.format("%04x", ch.code)) else out.append(ch)
            if (out.length > MAX_PATH) break
        }
        val shown = if (out.length > MAX_PATH) out.take(MAX_PATH - 1).toString() + "…" else out.toString()
        return shown.ifBlank { "(no path)" }
    }

    private val ID = Regex("^[0-9a-f]{12}$")

    /** A note id the PC issued: exactly 12 lower-case hex characters, so "." or ".." (or anything else) never
     *  reaches addPathSegment. (Security #48.) */
    fun validId(id: String): Boolean = ID.matches(id)

    /** An item's time, kept only when it is an ISO time (it is shown unescaped); anything else becomes "". */
    fun isoTime(raw: Any?): String = (raw as? String)?.takeIf { it.length <= 40 && DumpsyncClient.SINCE.matches(it) }.orEmpty()

    /** The Undo needs an unlock first: the app lock is on and ProxMobile is locked. (Security #47.) */
    fun needsUnlock(lockOn: Boolean, locked: Boolean): Boolean = lockOn && locked

    const val UNLOCK_TO_UNDO = "Unlock ProxAI to undo"

    /** What an Undo tap does. [Send]: queue the undo. [RepostUnlock]: send nothing, show "Unlock ProxAI to undo" again
     *  for [key]'s entry. Decided by the app lock ([lockedNow] = app.lock.check()), never by whether ProxMobile or an
     *  untouched wake overlay is on screen (that overlay is up but doesn't unlock anything). */
    enum class UndoStep { Send, RepostUnlock }

    fun undoStep(lockOn: Boolean, lockedNow: Boolean): UndoStep =
        if (needsUnlock(lockOn, lockedNow)) UndoStep.RepostUnlock else UndoStep.Send

    /** The PC's answer to GET written. Entries sharing an id are all kept and marked [VaultChange.sharedId]. */
    fun parse(body: String): VaultWritten? {
        val o = runCatching { JSONObject(body) }.getOrNull() ?: return null
        val arr: JSONArray = o.optJSONArray("items") ?: o.optJSONArray("written") ?: JSONArray()
        val raw = (0 until minOf(arr.length(), MAX_ITEMS)).mapNotNull { arr.optJSONObject(it) }.map {
            VaultChange(
                id = if (it.isNull("id")) "" else it.opt("id")?.toString().orEmpty(),
                path = escape(it.opt("path")),
                how = how(it.opt("how")),
                approvedBy = word(it.opt("approved_by")),
                canUndo = it.opt("can_undo") == true,
                time = isoTime(it.opt("time")),
            )
        }
        val counts = raw.groupingBy { it.id }.eachCount()
        val items = raw.map { it.copy(sharedId = it.id.isEmpty() || (counts[it.id] ?: 0) > 1) }
        val st = (o.opt("server_time") as? String).orEmpty().trim()
        return VaultWritten(items, if (DumpsyncClient.SINCE.matches(st)) st else "")
    }

    /** The new entries to notify about (not in [seen]), and the seen keys to keep after this round (newest last, at
     *  most [MAX_SEEN]). */
    fun fresh(items: List<VaultChange>, seen: List<String>): Pair<List<VaultChange>, List<String>> {
        val known = seen.toHashSet()
        val new = items.filter { it.key !in known }.distinctBy { it.key }
        return new to (seen + new.map { it.key }).takeLast(MAX_SEEN)
    }

    /** The undoable changes kept for notifications' Undo buttons (a notification's intent carries only the key, a
     *  pointer into this list), as JSON text. Already-escaped fields go back in as they are. */
    fun pendingJson(changes: List<VaultChange>): String = JSONArray().apply {
        changes.filter { it.undoable }.distinctBy { it.key }.takeLast(MAX_PENDING).forEach {
            put(JSONObject().put("id", it.id).put("path", it.path).put("how", it.how).put("by", it.approvedBy)
                .put("time", it.time))
        }
    }.toString()

    fun pendingFrom(json: String): List<VaultChange> = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
            VaultChange(it.optString("id"), it.optString("path"), how(it.opt("how")), word(it.opt("by")),
                canUndo = true, time = isoTime(it.opt("time")))
        }.filter { it.undoable }
    }.getOrDefault(emptyList())

    /** The seen keys, kept as a JSON array (a key holds the raw id, which may have any character). */
    fun keysJson(keys: List<String>): String = JSONArray(keys.takeLast(MAX_SEEN)).toString()
    fun keysFrom(json: String): List<String> = runCatching {
        val a = JSONArray(json); (0 until a.length()).map { a.optString(it) }
    }.getOrDefault(emptyList())

    /** What an undo's answer means, in plain words. */
    fun undoMessage(status: Int, path: String, said: String = ""): String = when (status) {
        in 200..299 -> "Undone: $path"
        409 -> CONFLICT
        503 -> NOT_SET_UP
        404 -> "Not undone: the PC has no record of that change any more."
        401 -> "Not undone: the Prox Dump sync token is wrong or missing (Settings → PC links)."
        403 -> "Not undone: the PC refused it. Check the Owner token (Settings → PC links)." +
            said.trim().take(DumpsyncClient.MAX_REFUSAL).let { if (it.isEmpty()) "" else " The PC said: ${escapeLine(it)}" }
        0 -> UNREACHABLE
        else -> "Not undone: the PC answered $status."
    }

    /** One line of the PC's own words, made safe to show (same rules as [escape]). */
    private fun escapeLine(s: String) = escape(s).removeSuffix("…")

    fun listMessage(status: Int): String = when (status) {
        503 -> LIST_NOT_SET_UP
        401 -> "The Prox Dump sync token is wrong or missing (Settings → PC links)."
        403 -> "The PC refused to list vault changes. Check the Owner token (Settings → PC links)."
        404 -> "The PC's Prox Dump sync doesn't have vault changes yet (needs the VAULT-YES update)."
        0 -> UNREACHABLE
        else -> "The PC answered $status."
    }
}

class VaultYesClient(
    private val baseUrl: String,
    private val token: String,
    private val ownerToken: String,
    private val http: OkHttpClient = DEFAULT_HTTP,
) {
    companion object {
        /** No redirects: both tokens only ever go to the URL in Settings. */
        private val DEFAULT_HTTP = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun from(s: Settings) = VaultYesClient(s.dumpsyncBaseUrl, s.dumpsyncToken, s.ownerToken)

        private fun headerSafe(v: String) = v.isNotEmpty() && v.all { it.code in 0x21..0x7e }
    }

    val isConfigured: Boolean
        get() = token.isNotBlank() && ownerToken.isNotBlank() && baseUrl.trim().toHttpUrlOrNull() != null

    /** GET /vault-yes/written[?since=]. A [since] that isn't an ISO time is left out. Never throws. */
    fun written(since: String?): VaultAnswer = guarded { headers ->
        val url = url("vault-yes", "written").newBuilder().apply {
            since?.trim()?.takeIf { DumpsyncClient.SINCE.matches(it) }?.let { addQueryParameter("since", it) }
        }.build()
        http.newCall(request(url, headers).get().build()).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) return@guarded VaultAnswer(false, VaultYes.listMessage(r.code), r.code)
            val w = VaultYes.parse(body) ?: return@guarded VaultAnswer(false, "Bad answer from Prox Dump sync", r.code)
            VaultAnswer(true, if (w.items.isEmpty()) "No approved vault changes." else "", r.code, w)
        }
    }

    /** POST /vault-yes/<id>/undo for [change] (its id is one path segment). Never throws. */
    fun undo(change: VaultChange): VaultAnswer {
        if (!change.undoable) return VaultAnswer(false,
            if (change.sharedId) "No Undo: another entry has the same id." else "The PC says this one can't be undone.")
        return guarded { headers ->
            val url = url("vault-yes", change.id, "undo")
            http.newCall(request(url, headers).post("{}".toRequestBody("application/json".toMediaType())).build())
                .execute().use { r ->
                    val said = runCatching { JSONObject(r.body?.string().orEmpty()).optString("error") }.getOrDefault("")
                    VaultAnswer(r.isSuccessful, VaultYes.undoMessage(r.code, change.path, said), r.code)
                }
        }
    }

    private inline fun guarded(block: (Map<String, String>) -> VaultAnswer): VaultAnswer {
        if (!isConfigured) return VaultAnswer(false, VaultYes.NO_TOKENS)
        if (!headerSafe(token) || !headerSafe(ownerToken)) return VaultAnswer(false, VaultYes.BAD_TOKEN)
        val headers = mapOf("X-ProxAI-App" to VaultYes.APP, "X-ProxAI-Owner-Token" to ownerToken)
        return try {
            block(headers)
        } catch (e: Exception) {
            VaultAnswer(false, VaultYes.UNREACHABLE)
        }
    }

    private fun url(vararg segments: String): HttpUrl {
        val b = baseUrl.trim().trimEnd('/').toHttpUrlOrNull()!!.newBuilder()
        segments.forEach { b.addPathSegment(it) }      // a note id's "/" becomes %2F (one segment)
        return b.build()
    }

    private fun request(url: HttpUrl, headers: Map<String, String>) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token").apply { headers.forEach { (k, v) -> header(k, v) } }
}
