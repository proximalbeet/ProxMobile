package com.proxai.hermes.data

import android.content.Context
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
    val role: String, // "user" | "assistant"
    val text: String,
    val tools: List<ToolChip> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    /** Generated app saved from this reply. */
    val appId: String? = null,
    /** App this request asked Hermes to modify. */
    val targetAppId: String? = null,
    val time: Long = System.currentTimeMillis(),
)

/** Fired when Hermes finishes a reply, so the voice overlay can read it aloud. */
data class ReplyDone(val message: ChatMessage, val fromVoice: Boolean)

/**
 * One conversation shared by the main app and the power-button overlay. The request runs in
 * the application scope, so closing the overlay doesn't kill a reply mid-stream.
 */
class ChatRepository(
    context: Context,
    private val settings: Settings,
    private val hermes: HermesClient,
    private val apps: AppStore,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "chat.json")
    private val _messages = MutableStateFlow(load())
    val messages: StateFlow<List<ChatMessage>> = _messages
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _done = MutableSharedFlow<ReplyDone>(extraBufferCapacity = 4)
    val done: SharedFlow<ReplyDone> = _done
    private var job: Job? = null

    fun send(text: String, fromVoice: Boolean = false, targetAppId: String? = null) {
        val prompt = text.trim()
        if (prompt.isEmpty() || _busy.value) return
        val target = targetAppId?.let { apps.get(it) }
        val userMsg = ChatMessage(
            role = "user",
            text = if (target != null) "Remix “${target.title}”: $prompt" else prompt,
            targetAppId = target?.id)
        val reply = ChatMessage(role = "assistant", text = "", streaming = true, targetAppId = target?.id)
        _messages.value = _messages.value + userMsg + reply
        _busy.value = true

        val wire = buildString {
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
            var failed: String? = null
            hermes.stream(system, wire, settings.sessionId).collect { ev ->
                when (ev) {
                    is StreamEvent.Delta -> update(reply.id) { it.copy(text = it.text + ev.text) }
                    is StreamEvent.Tool -> update(reply.id) { m ->
                        val others = m.tools.filterNot { it.id == ev.id }
                        val prev = m.tools.firstOrNull { it.id == ev.id }
                        val chip = if (ev.running) ToolChip(ev.id, ev.label, ev.emoji, true)
                        else (prev ?: ToolChip(ev.id, ev.label, ev.emoji, false)).copy(running = false)
                        m.copy(tools = others + chip)
                    }
                    is StreamEvent.Error -> failed = ev.message
                    is StreamEvent.Done -> Unit
                }
            }
            finish(reply.id, failed, target?.id, fromVoice)
        }
        job?.invokeOnCompletion { cause ->
            if (cause != null) scope.launch { finish(reply.id, "Stopped", null, false, notify = false) }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    fun newChat() {
        cancel()
        settings.newSession()
        _messages.value = emptyList()
        persist()
    }

    private suspend fun finish(id: String, error: String?, targetId: String?, fromVoice: Boolean, notify: Boolean = true) {
        val msg = _messages.value.firstOrNull { it.id == id } ?: return
        if (!msg.streaming) return
        val html = AppStore.extractHtml(msg.text)
        val saved = html?.let { withContext(Dispatchers.IO) { apps.save(it, targetId) } }
        update(id) {
            it.copy(streaming = false, appId = saved?.id ?: it.appId,
                error = if (it.text.isBlank() || error == "Stopped") error else null,
                tools = it.tools.map { t -> t.copy(running = false) })
        }
        _busy.value = false
        persist()
        if (notify) _messages.value.firstOrNull { it.id == id }?.let { _done.tryEmit(ReplyDone(it, fromVoice)) }
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
                .put("time", m.time)
                .put("tools", JSONArray().apply {
                    m.tools.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("emoji", it.emoji)) }
                }))
        }
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(arr.toString()) } }
    }

    private fun load(): List<ChatMessage> = runCatching {
        val arr = JSONArray(file.readText())
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val tools = o.optJSONArray("tools")
            ChatMessage(
                id = o.getString("id"), role = o.getString("role"), text = o.getString("text"),
                error = o.optString("error").takeIf { it.isNotEmpty() && it != "null" },
                appId = o.optString("appId").takeIf { it.isNotEmpty() && it != "null" },
                targetAppId = o.optString("targetAppId").takeIf { it.isNotEmpty() && it != "null" },
                time = o.optLong("time"),
                tools = (0 until (tools?.length() ?: 0)).map {
                    val t = tools!!.getJSONObject(it)
                    ToolChip(t.getString("id"), t.getString("label"), t.optString("emoji"), false)
                })
        }
    }.getOrDefault(emptyList())
}
