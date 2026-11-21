package com.proxai.hermes.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Phase 2, C4 (2026-10-29): channels. The DM ([DM], "ProxAI") is the main chat, where the PC's
 * notices land; each channel is a separate conversation for a project or topic, with its own Hermes
 * session (so its own memory on the PC) and its own model.
 *
 * [model]: null = what Settings says (Auto/Cloud/Local and the cloud model), "local" = the PC's
 * local model, anything else = that cloud model (ModelRoute.CLOUD_CHOICES).
 */
data class Channel(
    val id: String,
    val name: String,
    val model: String? = null,
    val sessionId: String = newSessionId(),
    /** Summary of a compacted session, sent with the next message (like Settings.carryOver for the DM). */
    val carryOver: String = "",
    val created: Long = System.currentTimeMillis(),
) {
    val isDm get() = id == DM

    companion object {
        const val DM = "dm"
        const val MAX_NAME = 40
        fun newSessionId() = "android-" + UUID.randomUUID().toString().substring(0, 13)

        /** "Homework 2!" -> "homework-2" (+ a short random tail, so a deleted name can be reused). */
        fun newId(name: String): String {
            val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(24).ifBlank { "channel" }
            return "$slug-" + UUID.randomUUID().toString().substring(0, 4)
        }

        fun cleanName(name: String) = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME)

        /** The route for a channel's [model] (see the class doc). Pure, unit-tested. */
        fun route(model: String?, settingsChoice: String, localModel: String, localProvider: String,
                  settingsCloud: String): ModelRoute = when (model) {
            null -> ModelRoute.pick(settingsChoice, localModel, localProvider, settingsCloud)
            "local" -> ModelRoute.pick("local", localModel, localProvider, settingsCloud)
            // A model that isn't on the list (an old or hand-edited channels.json) follows Settings' cloud model.
            else -> ModelRoute.cloud(ModelRoute.listed(model, ModelRoute.listed(settingsCloud)))
        }

        /** "Same as Settings" / "Haiku 5.5" / "Local model" for the channel list. */
        fun modelLabel(model: String?): String = when (model) {
            null -> "Same as Settings"
            "local" -> "Local model"
            else -> ModelRoute.CLOUD_NAMES[model] ?: model
        }
    }
}

/** channels.json: the custom channels. The DM's session and summary stay in Settings (as before). */
class ChannelStore(context: Context, private val settings: Settings) {
    private val file = File(context.filesDir, "channels.json")
    private var list: List<Channel> = load()

    val channels: List<Channel> get() = list

    fun dm() = Channel(Channel.DM, "ProxAI", null, settings.sessionId, settings.carryOver, 0)
    fun get(id: String): Channel? = if (id == Channel.DM) dm() else list.firstOrNull { it.id == id }
    /** The DM first, then the channels, oldest first. */
    fun all(): List<Channel> = listOf(dm()) + list

    fun create(name: String, model: String?): Channel {
        val c = Channel(Channel.newId(name), Channel.cleanName(name).ifBlank { "Channel" }, model)
        list = list + c
        save()
        return c
    }

    /** Put back a channel from the trash, with its old id, name and model (and the session it had). */
    fun restore(c: Channel) {
        if (c.isDm || get(c.id) != null) return
        list = list + c
        save()
    }

    fun update(id: String, fn: (Channel) -> Channel) {
        if (id == Channel.DM) {
            val c = fn(dm())
            settings.sessionId = c.sessionId
            settings.carryOver = c.carryOver
            return
        }
        list = list.map { if (it.id == id) fn(it) else it }
        save()
    }

    fun remove(id: String) {
        if (id == Channel.DM) return
        list = list.filterNot { it.id == id }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        list.forEach { arr.put(toJson(it)) }
        runCatching { file.writeText(arr.toString()) }
    }

    private fun load(): List<Channel> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    companion object {
        fun toJson(c: Channel): JSONObject = JSONObject().put("id", c.id).put("name", c.name).put("model", c.model)
            .put("sessionId", c.sessionId).put("carryOver", c.carryOver).put("created", c.created)

        fun fromJson(o: JSONObject): Channel? {
            val id = o.optString("id").takeIf { it.isNotBlank() && it != Channel.DM } ?: return null
            return Channel(id, o.optString("name").ifBlank { "Channel" },
                o.optString("model").takeIf { it.isNotBlank() && it != "null" }?.let(ModelRoute::current),
                o.optString("sessionId").ifBlank { Channel.newSessionId() }, o.optString("carryOver"), o.optLong("created"))
        }
    }
}

/** A conversation in the trash (C7): what was in it, and the channel and session it came from. */
data class TrashItem(
    val id: String,
    val channel: Channel,
    val title: String,
    val deletedAt: Long,
    val count: Int,
    /** The whole channel was deleted (not just one of its conversations). */
    val channelDeleted: Boolean,
)

/**
 * C7: conversations moved out by "New chat" or by deleting a channel. trash/<id>.json holds the
 * messages and the Hermes session, so a restored chat keeps its memory on the PC. Deleted for good
 * after Settings.trashDays (0 = never).
 */
class TrashStore(context: Context) {
    private val dir = File(context.filesDir, "trash")

    fun add(channel: Channel, messages: List<ChatMessage>, channelDeleted: Boolean, now: Long = System.currentTimeMillis()): TrashItem? {
        if (messages.none { it.role == "user" || it.role == "assistant" || it.role == "notice" }) return null
        val item = TrashItem(UUID.randomUUID().toString().substring(0, 13), channel, title(messages), now,
            messages.size, channelDeleted)
        dir.mkdirs()
        val o = JSONObject().put("id", item.id).put("channel", ChannelStore.toJson(channel))
            .put("channelDm", channel.isDm).put("title", item.title).put("deletedAt", now).put("count", item.count)
            .put("channelDeleted", channelDeleted).put("messages", messagesToJson(messages))
        runCatching { File(dir, "${item.id}.json").writeText(o.toString()) }
        return item
    }

    /** Newest first. */
    fun list(): List<TrashItem> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
        .mapNotNull { f -> runCatching { readItem(JSONObject(f.readText())) }.getOrNull() }
        .sortedByDescending { it.deletedAt }

    fun messages(id: String): List<ChatMessage> = runCatching {
        messagesFromJson(JSONObject(File(dir, "$id.json").readText()).getJSONArray("messages"))
    }.getOrDefault(emptyList())

    /** For good, with the files attached to its messages (C5). */
    fun delete(id: String) {
        Attachments.delete(messages(id).flatMap { it.attachments })
        File(dir, "$id.json").delete()
    }

    fun empty() { list().forEach { delete(it.id) } }

    /** Deletes what's older than [days] (0 = keep forever). Returns how many went. */
    fun purge(days: Int, now: Long = System.currentTimeMillis()): Int {
        val gone = expired(list(), days, now)
        gone.forEach { delete(it.id) }
        return gone.size
    }

    private fun readItem(o: JSONObject): TrashItem {
        val co = o.getJSONObject("channel")
        val ch = if (o.optBoolean("channelDm")) Channel(Channel.DM, "ProxAI", null, co.optString("sessionId"),
            co.optString("carryOver"), 0) else ChannelStore.fromJson(co) ?: error("bad channel")
        return TrashItem(o.getString("id"), ch, o.optString("title"), o.optLong("deletedAt"), o.optInt("count"),
            o.optBoolean("channelDeleted"))
    }

    companion object {
        private const val DAY_MS = 24 * 60 * 60 * 1000L

        /** What a trashed conversation is called: its first message from Nathan (or the first notice). */
        fun title(messages: List<ChatMessage>): String {
            val first = messages.firstOrNull { it.role == "user" }?.text
                ?: messages.firstOrNull { it.role == "notice" }?.let { it.title?.ifBlank { null } ?: it.text }
                ?: "Conversation"
            val line = first.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().trim()
            return if (line.length <= 60) line.ifBlank { "Conversation" } else line.take(60).substringBeforeLast(' ') + "…"
        }

        fun expired(items: List<TrashItem>, days: Int, now: Long): List<TrashItem> =
            if (days <= 0) emptyList() else items.filter { now - it.deletedAt > days * DAY_MS }
    }
}
