package com.proxai.hermes.data

import android.content.Context
import com.proxai.hermes.notify.Notifier
import com.proxai.hermes.voice.SpeechController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ToolChip(val id: String, val label: String, val emoji: String, val running: Boolean)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant" | "note" (app notices, e.g. "context compacted")
    val text: String,
    val tools: List<ToolChip> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    /** Generated app saved from this reply. */
    val appId: String? = null,
    /** App this request asked Hermes to modify. */
    val targetAppId: String? = null,
    val time: Long = System.currentTimeMillis(),
    /** Model that answered (assistant), e.g. "qwen3.5:35b-a3b · local". */
    val model: String? = null,
    val local: Boolean = false,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    /** Reminders this reply set, as shown text ("Fri Oct 23, 9:00 AM: call Mom"). */
    val reminders: List<String> = emptyList(),
    /** While streaming: the model is thinking (its reasoning isn't shown). Not saved. */
    val thinking: Boolean = false,
    /** While streaming: when the last thing arrived from Hermes (for "still working…"). Not saved. */
    val lastEventAt: Long = 0,
)

/** Fired when Hermes finishes a reply, so the voice overlay can read it aloud. */
data class ReplyDone(val message: ChatMessage, val fromVoice: Boolean)

/** Size and rough cost of the current Hermes session, from the usage Hermes reports. */
data class SessionStats(val lastPromptTokens: Int, val inputTokens: Long, val outputTokens: Long, val costUsd: Double)

/**
 * One conversation shared by the main app and the power-button overlay. The request runs in
 * the application scope, so closing the overlay doesn't kill a reply mid-stream.
 */
class ChatRepository(
    private val context: Context,
    private val settings: Settings,
    private val hermes: HermesClient,
    private val apps: AppStore,
    private val pcMode: PcModeMonitor,
    private val reminders: ReminderStore,
    private val scope: CoroutineScope,
    private val inForeground: () -> Boolean,
) {
    private val file = File(context.filesDir, "chat.json")
    private val _messages = MutableStateFlow(load())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _done = MutableSharedFlow<ReplyDone>(extraBufferCapacity = 4)
    val done: SharedFlow<ReplyDone> = _done
    private var job: Job? = null

    /** The route the next request will use (shown in the chat header). */
    fun currentRoute(): ModelRoute = ModelRoute.pick(settings.modelChoice, pcMode.state.value,
        settings.localModel, settings.localProvider, settings.model)

    /** Said or typed while Hermes was still busy: sent as soon as the current reply finishes. */
    private var queued: Triple<String, Boolean, String?>? = null
        set(v) { field = v; _queuedText.value = v?.first }
    private val _queuedText = MutableStateFlow<String?>(null)
    /** Text waiting to be sent (shown so you know it wasn't lost). */
    val queuedText: StateFlow<String?> = _queuedText

    fun send(text: String, fromVoice: Boolean = false, targetAppId: String? = null) {
        val prompt = text.trim()
        if (prompt.isEmpty()) return
        if (_busy.value) {
            // Used to be dropped silently, so a spoken prompt had to be repeated.
            val prev = queued
            queued = if (prev == null) Triple(prompt, fromVoice, targetAppId)
            else Triple(prev.first + "\n" + prompt, prev.second || fromVoice, prev.third ?: targetAppId)
            return
        }
        val target = targetAppId?.let { apps.get(it) }
        val userMsg = ChatMessage(
            role = "user",
            text = if (target != null) "Remix “${target.title}”: $prompt" else prompt,
            targetAppId = target?.id)
        val reply = ChatMessage(role = "assistant", text = "", streaming = true, targetAppId = target?.id)
        _messages.value = _messages.value + userMsg + reply
        _busy.value = true

        val carry = settings.carryOver
        val wire = buildString {
            if (carry.isNotBlank()) {
                append("[Context from earlier in this chat; the full history was compacted into this summary]\n")
                append(carry.trim())
                append("\n[End of summary]\n\n")
            }
            append(prompt)
            if (target != null) {
                append("\n\nModify this existing app (\"${target.title}\"). Return the COMPLETE updated file ")
                append("in one ```html block.\n\n```html\n")
                append(apps.html(target.id).orEmpty())
                append("\n```")
            }
        }
        val system = Prompts.system(fromVoice)

        job = scope.launch {
            // Server mode -> the PC's local model (cached ~1 min, never waits more than ~5 s).
            if (settings.modelChoice == "auto") pcMode.current()
            val route = currentRoute()
            update(reply.id) { it.copy(model = route.label, local = route.local) }
            var failed: String? = null
            var usage: Usage? = null
            var answeredBy: String? = null
            update(reply.id) { it.copy(lastEventAt = System.currentTimeMillis()) }
            hermes.stream(system, wire, settings.sessionId, route).collect { ev ->
                val now = System.currentTimeMillis()
                when (ev) {
                    is StreamEvent.Delta -> update(reply.id) { it.copy(text = it.text + ev.text, thinking = false, lastEventAt = now) }
                    is StreamEvent.Thinking -> update(reply.id) { it.copy(thinking = true, lastEventAt = now) }
                    is StreamEvent.Tool -> update(reply.id) { m0 ->
                        val m = m0.copy(thinking = false, lastEventAt = now)
                        val others = m.tools.filterNot { it.id == ev.id }
                        val prev = m.tools.firstOrNull { it.id == ev.id }
                        val chip = if (ev.running) ToolChip(ev.id, ev.label, ev.emoji, true)
                        else (prev ?: ToolChip(ev.id, ev.label, ev.emoji, false)).copy(running = false)
                        m.copy(tools = others + chip)
                    }
                    is StreamEvent.Error -> failed = ev.message
                    is StreamEvent.Done -> { usage = ev.usage; answeredBy = ev.model }
                }
            }
            if (failed == null && carry.isNotBlank()) settings.carryOver = ""
            // Cloud replies report the profile's virtual name ("proxai"), so keep the route's label.
            val label = if (route.local) answeredBy?.let { "$it · local" } ?: route.label else "cloud"
            update(reply.id) {
                it.copy(model = label, promptTokens = usage?.promptTokens ?: 0, completionTokens = usage?.completionTokens ?: 0)
            }
            finish(reply.id, failed, target?.id, fromVoice)
            maybeAutoCompact(usage)
            sendQueued()
        }
        job?.invokeOnCompletion { cause ->
            if (cause != null) scope.launch { finish(reply.id, "Stopped", null, false, notify = false) }
        }
    }

    private fun sendQueued() {
        if (_busy.value) return
        val q = queued ?: return
        queued = null
        send(q.first, q.second, q.third)
    }

    fun cancel() {
        queued = null
        job?.cancel()
    }

    fun newChat() {
        cancel()
        settings.newSession()
        settings.carryOver = ""
        _messages.value = emptyList()
        persist()
    }

    // ---- auto-compact ----------------------------------------------------------------------------

    private fun maybeAutoCompact(usage: Usage?) {
        val limit = settings.compactAtTokens
        if (limit > 0 && (usage?.promptTokens ?: 0) >= limit) compact(auto = true)
    }

    /**
     * Long sessions get expensive: Hermes resends the whole history every turn. This asks Hermes
     * for a short summary, starts a fresh session, and sends the summary with the next message.
     */
    fun compact(auto: Boolean = false) {
        if (_busy.value) return
        if (sessionMessages(_messages.value).none { it.role == "user" }) return
        val stats = stats(_messages.value)
        _busy.value = true
        val note = ChatMessage(role = "note", text = "Compacting the conversation…", streaming = true)
        _messages.value = _messages.value + note
        val oldSession = settings.sessionId
        val route = currentRoute()
        job = scope.launch {
            val summary = runCatching { hermes.ask(Prompts.COMPACT, oldSession, route = route) }
            summary.onSuccess { text ->
                val clean = ReminderStore.strip(text).trim()
                if (clean.isBlank()) {
                    update(note.id) { it.copy(streaming = false, text = "Couldn't compact: Hermes sent an empty summary.") }
                } else {
                    settings.newSession()
                    settings.carryOver = clean
                    val why = if (auto) "The last reply read ${stats.lastPromptTokens / 1000}k tokens, so the conversation was compacted."
                        else "The conversation was compacted."
                    update(note.id) {
                        it.copy(streaming = false,
                            text = "$why Your next message starts a fresh, " +
                                "cheaper Hermes session that gets this summary:\n\n$clean")
                    }
                }
            }.onFailure { e ->
                update(note.id) { it.copy(streaming = false, text = "Couldn't compact: ${e.message ?: "error"}") }
            }
            _busy.value = false
            persist()
            sendQueued()
        }
        job?.invokeOnCompletion { cause ->
            if (cause != null) scope.launch {
                update(note.id) { if (it.streaming) it.copy(streaming = false, text = "Compacting stopped.") else it }
                _busy.value = false
                persist()
            }
        }
    }

    private suspend fun finish(id: String, error: String?, targetId: String?, fromVoice: Boolean, notify: Boolean = true) {
        val msg = _messages.value.firstOrNull { it.id == id } ?: return
        if (!msg.streaming) return
        val html = AppStore.extractHtml(msg.text)
        val saved = html?.let { withContext(Dispatchers.IO) { apps.save(it, targetId) } }
        // Reminder tags -> phone alarms; the tag itself isn't shown or spoken.
        val set = if (error == null) ReminderStore.parse(msg.text)
            .filter { it.at > System.currentTimeMillis() - 60_000 }
            .map { reminders.add(it.at, it.text) }
            .map { "${ReminderStore.format(it.at)}: ${it.text}" } else emptyList()
        update(id) {
            it.copy(streaming = false, thinking = false, appId = saved?.id ?: it.appId,
                text = ReminderStore.strip(it.text),
                reminders = set,
                error = if (it.text.isBlank() || error == "Stopped") error else null,
                tools = it.tools.map { t -> t.copy(running = false) })
        }
        _busy.value = false
        persist()
        val done = _messages.value.firstOrNull { it.id == id } ?: return
        if (notify) _done.tryEmit(ReplyDone(done, fromVoice))
        if (notify && settings.replyNotifications && !inForeground()) {
            Notifier.reply(context, SpeechController.spokenText(done.text).ifBlank { done.error ?: "" })
        }
    }

    private fun update(id: String, fn: (ChatMessage) -> ChatMessage) {
        _messages.value = _messages.value.map { if (it.id == id) fn(it) else it }
    }

    private fun persist() {
        val keep = _messages.value.takeLast(200)
        val arr = JSONArray()
        keep.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("role", m.role).put("text", m.text)
                .put("error", m.error).put("appId", m.appId).put("targetAppId", m.targetAppId)
                .put("time", m.time).put("model", m.model).put("local", m.local)
                .put("promptTokens", m.promptTokens).put("completionTokens", m.completionTokens)
                .put("reminders", JSONArray(m.reminders))
                .put("tools", JSONArray().apply {
                    m.tools.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("emoji", it.emoji)) }
                }))
        }
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(arr.toString()) } }
    }

    private fun load(): List<ChatMessage> = runCatching {
        val arr = JSONArray(file.readText())
        fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val tools = o.optJSONArray("tools")
            val rem = o.optJSONArray("reminders")
            ChatMessage(
                id = o.getString("id"), role = o.getString("role"), text = o.getString("text"),
                error = o.str("error"), appId = o.str("appId"), targetAppId = o.str("targetAppId"),
                time = o.optLong("time"), model = o.str("model"), local = o.optBoolean("local"),
                promptTokens = o.optInt("promptTokens"), completionTokens = o.optInt("completionTokens"),
                reminders = (0 until (rem?.length() ?: 0)).map { rem!!.getString(it) },
                tools = (0 until (tools?.length() ?: 0)).map {
                    val t = tools!!.getJSONObject(it)
                    ToolChip(t.getString("id"), t.getString("label"), t.optString("emoji"), false)
                })
        }
    }.getOrDefault(emptyList())

    companion object {
        // Rough cloud price (Sonnet 5.5, uncached): $2 per million input tokens, $10 per million output.
        // Prompt caching makes the real cost lower, so the app shows this as an upper bound.
        private const val INPUT_USD_PER_MTOK = 2.0
        private const val OUTPUT_USD_PER_MTOK = 10.0

        /** Messages of the current Hermes session: everything after the last compaction note. */
        fun sessionMessages(all: List<ChatMessage>): List<ChatMessage> {
            val lastNote = all.indexOfLast { it.role == "note" && !it.streaming && it.text.contains("compacted") }
            return if (lastNote < 0) all else all.drop(lastNote + 1)
        }

        fun stats(all: List<ChatMessage>): SessionStats {
            val session = sessionMessages(all).filter { it.role == "assistant" }
            val input = session.sumOf { it.promptTokens.toLong() }
            val output = session.sumOf { it.completionTokens.toLong() }
            val cloud = session.filterNot { it.local }
            val cost = cloud.sumOf { it.promptTokens * INPUT_USD_PER_MTOK + it.completionTokens * OUTPUT_USD_PER_MTOK } / 1_000_000.0
            val last = session.lastOrNull { it.promptTokens > 0 }?.promptTokens ?: 0
            return SessionStats(last, input, output, cost)
        }
    }
}
