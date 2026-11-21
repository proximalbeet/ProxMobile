package com.proxai.hermes.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * NOTICE-QUOTE round 4 (0.9.15): the PC notices and alerts ProxMobile itself fetched from the PC (NoticeSync,
 * PcAlertWorker), kept by key ("notice:123", "alert:45").
 *
 * A notification's tap or Reply reaches ProxMobile as an intent, and MainActivity takes intents from any app. So
 * an intent is only a pointer: its key is looked up here and the notice put in the chat is this store's copy, never
 * the intent's extras. A key that isn't here is dropped, so no app can put a made-up notice in the chat (and in what
 * reaches the model) or claim a real notice's key before it arrives. Pure apart from [of]; unit-tested.
 */
class NoticeStore(private val load: () -> String?, private val save: (String) -> Unit) {
    data class Entry(
        val key: String, val kind: String, val title: String, val text: String, val time: String,
        val noQuote: Boolean = false, val decision: String = "", val next: String = "",
    )

    /** Keeps [entries] (a newer copy of a key replaces the older one), the newest [MAX] in all. */
    fun put(entries: List<Entry>) {
        if (entries.isEmpty()) return
        synchronized(LOCK) {
            val keys = entries.map { it.key }.toSet()
            save(toJson((all().filterNot { it.key in keys } + entries).takeLast(MAX)))
        }
    }

    /** The stored notice a pointer [key] names, or null (unknown, blank or missing: drop it). */
    fun get(key: String?): Entry? {
        if (key.isNullOrBlank()) return null
        return synchronized(LOCK) { all().lastOrNull { it.key == key } }
    }

    private fun all(): List<Entry> = runCatching { fromJson(load().orEmpty()) }.getOrDefault(emptyList())

    companion object {
        const val MAX = 100
        private val LOCK = Any()

        /** The store on this phone (its own preferences file; apps never see it). */
        fun of(ctx: Context): NoticeStore {
            val prefs = ctx.applicationContext.getSharedPreferences("pc_notice_store", Context.MODE_PRIVATE)
            return NoticeStore({ prefs.getString("entries", null) }, { prefs.edit().putString("entries", it).apply() })
        }

        fun entry(n: PcNotice) = Entry(ChatNotices.key(n), n.kind, n.title, n.text, n.time, n.noQuote, n.decision, n.next)
        fun entry(a: PcAlert) = Entry(ChatNotices.key(a), "alert", a.title, a.msg, a.time)

        fun toJson(entries: List<Entry>): String = JSONArray().apply {
            entries.forEach {
                put(JSONObject().put("key", it.key).put("kind", it.kind).put("title", it.title).put("text", it.text)
                    .put("time", it.time).put("noQuote", it.noQuote).put("decision", it.decision).put("next", it.next))
            }
        }.toString()

        fun fromJson(s: String): List<Entry> {
            if (s.isBlank()) return emptyList()
            val arr = JSONArray(s)
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.takeIf { it.optString("key").isNotBlank() }?.let {
                    Entry(it.optString("key"), it.optString("kind"), it.optString("title"), it.optString("text"),
                        it.optString("time"), ChatNotices.noQuoteOf(it.opt("noQuote")), it.optString("decision"),
                        it.optString("next"))
                }
            }
        }
    }
}
