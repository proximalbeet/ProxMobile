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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ToolChip(val id: String, val label: String, val emoji: String, val running: Boolean)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user" | "assistant" | "note" (app notices, e.g. "context compacted") | "notice" (from the PC, C1)
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
    /** The conversation's real size after this reply (latest model call on the PC); 0 = unknown. */
    val contextTokens: Int = 0,
    /** The model's context window, when known. */
    val contextWindow: Int = 0,
    /** Asked by voice: the reply is shown and spoken in parts (VoiceParts). */
    val voice: Boolean = false,
    /** Reminders this reply set, as shown text ("Fri Oct 23, 9:00 AM: call Mom"). */
    val reminders: List<String> = emptyList(),
    /** The whole session's real cost on the PC after this reply (USD); negative = unknown. */
    val sessionCostUsd: Double = -1.0,
    /** A PC notice (role "notice"): its title, kind ("rhythm", "scout"...) and key ("notice:123", once per chat). */
    val title: String? = null,
    val kind: String? = null,
    val noticeKey: String? = null,
    /** NOTICE-QUOTE (0.9.15): the PC marked this notice no_quote, so only its kind and title reach the model. */
    val noQuote: Boolean = false,
    /** A notice's decision and next step, when the PC sent them; they go along with a quoted notice. */
    val decision: String? = null,
    val next: String? = null,
    /** Files Nathan attached (C5): pictures, PDFs (as pictures of their pages), text files. */
    val attachments: List<Attachment> = emptyList(),
    /** While streaming: the model is thinking (its reasoning isn't shown). Not saved. */
    val thinking: Boolean = false,
    /** While streaming: when the last thing arrived from Hermes (for "still working…"). Not saved. */
    val lastEventAt: Long = 0,
)

/** Fired when Hermes finishes a reply, so the voice overlay can read it aloud. */
data class ReplyDone(val message: ChatMessage, val fromVoice: Boolean)

/** One finished part of a voice reply, to speak now; [last] = the reply is complete. */
data class VoicePart(val messageId: String, val text: String, val first: Boolean, val last: Boolean)

/** Size and rough cost of the current Hermes session, from the usage Hermes reports. */
data class SessionStats(
    /** Estimated conversation size from the last cloud reply (tokens per model call). */
    val lastPromptTokens: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val costUsd: Double,
    /** The last reply came from the PC's local model: Hermes tracks the size and compacts. */
    val localManaged: Boolean = false,
    /** [costUsd] is the PC's real number (else the uncached upper bound). */
    val costExact: Boolean = false,
)

/**
 * The conversations shared by the main app and the power-button overlay: the DM and the channels
 * (C4), one Hermes session each. [messages] is the one on screen ([active]); a reply still streaming
 * in another channel keeps going there. One request at a time: a message for any channel sent while
 * ProxAI is answering waits its turn. Requests run in the application scope, so closing the
 * overlay doesn't kill a reply mid-stream. Main thread.
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
    private val channels = ChannelStore(context, settings)
    private val trash = TrashStore(context)
    private val _active = MutableStateFlow(settings.activeChannel.takeIf { channels.get(it) != null } ?: Channel.DM)
    /** The channel on screen. */
    val active: StateFlow<String> = _active
    private val _channelList = MutableStateFlow(channels.all())
    /** The DM first, then the channels. */
    val channelList: StateFlow<List<Channel>> = _channelList
    private val _messages = MutableStateFlow(load(fileOf(_active.value)))
    val messages: StateFlow<List<ChatMessage>> = _messages
    /** Conversations of the channels that aren't on screen (read from disk when first needed). */
    private val others = mutableMapOf<String, List<ChatMessage>>()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy
    private val _busyChannel = MutableStateFlow<String?>(null)
    /** Which channel ProxAI is answering in right now. */
    val busyChannel: StateFlow<String?> = _busyChannel
    private val _done = MutableSharedFlow<ReplyDone>(extraBufferCapacity = 4)
    val done: SharedFlow<ReplyDone> = _done
    private val _voiceParts = MutableSharedFlow<VoicePart>(extraBufferCapacity = 64)
    val voiceParts: SharedFlow<VoicePart> = _voiceParts
    private val partsSent = mutableMapOf<String, Int>()
    private var job: Job? = null

    init {
        // C7: what's been in the trash longer than Settings allows goes for good.
        scope.launch(Dispatchers.IO) { runCatching { trash.purge(settings.trashDays) } }
    }

    private fun fileOf(ch: String) = if (ch == Channel.DM) File(context.filesDir, "chat.json")
        else File(File(context.filesDir, "chats").apply { mkdirs() }, "$ch.json")

    private fun msgs(ch: String): List<ChatMessage> =
        if (ch == _active.value) _messages.value else others.getOrPut(ch) { load(fileOf(ch)) }

    private fun setMsgs(ch: String, list: List<ChatMessage>) {
        if (ch == _active.value) _messages.value = list else others[ch] = list
    }

    private fun setBusy(ch: String?) {
        _busyChannel.value = ch
        _busy.value = ch != null
    }

    fun channel(id: String = _active.value): Channel = channels.get(id) ?: channels.dm()

    /** The route the next request in [ch] will use (shown in the chat header). */
    fun currentRoute(ch: String = _active.value): ModelRoute = Channel.route(channel(ch).model, settings.modelChoice,
        settings.localModel, settings.localProvider, settings.model)

    /** Said or typed while Hermes was still busy: sent, in order, as soon as the current reply finishes. */
    private data class Queued(val channel: String, val text: String, val voice: Boolean, val target: String?,
                              val attachments: List<Attachment> = emptyList(), val approval: Pair<Approval, Boolean>? = null)
    private val queue = ArrayDeque<Queued>()
    private val _queuedText = MutableStateFlow<String?>(null)
    /** Text waiting to be sent in the channel on screen (shown so you know it wasn't lost). */
    val queuedText: StateFlow<String?> = _queuedText
    private fun refreshQueued() { _queuedText.value = queue.lastOrNull { it.channel == _active.value }?.text }

    /** The chat is on screen (ChatScreen); a notice that lands in it then needs no notification. */
    @Volatile var chatOnScreen = false
    /** The DM is the chat on screen (notices land there). */
    val dmOnScreen: Boolean get() = chatOnScreen && _active.value == Channel.DM

    // ---- channels (C4) and the trash (C7) ----------------------------------------------------------

    fun switchTo(ch: String) {
        if (channels.get(ch) == null || ch == _active.value) return
        others[_active.value] = _messages.value
        _active.value = ch
        settings.activeChannel = ch
        _messages.value = others.remove(ch) ?: load(fileOf(ch))
        refreshQueued()
    }

    fun createChannel(name: String, model: String?): Channel {
        val c = channels.create(name, model)
        _channelList.value = channels.all()
        switchTo(c.id)
        return c
    }

    fun renameChannel(id: String, name: String) {
        val clean = Channel.cleanName(name)
        if (clean.isBlank()) return
        channels.update(id) { it.copy(name = clean) }
        _channelList.value = channels.all()
    }

    fun setChannelModel(id: String, model: String?) {
        channels.update(id) { it.copy(model = model) }
        _channelList.value = channels.all()
    }

    /** Deletes a channel: its conversation goes to the trash (with the channel, so it can come back). */
    fun deleteChannel(id: String) {
        val c = channels.get(id) ?: return
        if (c.isDm) return
        if (_busyChannel.value == id) cancel()
        queue.removeAll { it.channel == id }
        if (_active.value == id) switchTo(Channel.DM)
        trash.add(c, msgs(id), channelDeleted = true)
        others.remove(id)
        fileOf(id).delete()
        channels.remove(id)
        _channelList.value = channels.all()
        refreshQueued()
    }

    fun trashItems(): List<TrashItem> {
        trash.purge(settings.trashDays)
        return trash.list()
    }

    /**
     * Brings a trashed conversation back into its channel (re-creating a deleted channel), with its
     * Hermes session, so ProxAI remembers it. Whatever that channel holds now goes to the trash first.
     */
    fun restore(itemId: String): Boolean {
        val item = trash.list().firstOrNull { it.id == itemId } ?: return false
        if (_busy.value) return false
        val messages = trash.messages(itemId)
        val ch = item.channel
        if (!ch.isDm && channels.get(ch.id) == null) channels.restore(ch)
        else moveToTrash(ch.id)
        channels.update(ch.id) { it.copy(sessionId = ch.sessionId, carryOver = ch.carryOver) }
        setMsgs(ch.id, messages)
        persist(ch.id)
        trash.delete(itemId)
        _channelList.value = channels.all()
        switchTo(ch.id)
        return true
    }

    /** B12: how many messages a Restore of [item] would move to the trash first (0 = its channel is empty or gone). */
    fun restoreDisplaces(item: TrashItem): Int {
        val ch = item.channel
        if (!ch.isDm && channels.get(ch.id) == null) return 0
        return msgs(ch.id).count { it.role == "user" || it.role == "assistant" || it.role == "notice" }
    }

    fun deleteFromTrash(itemId: String) = trash.delete(itemId)
    fun emptyTrash() = trash.empty()

    /** The channel's conversation to the trash and a fresh session for it. */
    private fun moveToTrash(ch: String) {
        trash.add(channel(ch), msgs(ch), channelDeleted = false)
        channels.update(ch) { it.copy(sessionId = Channel.newSessionId(), carryOver = "") }
        setMsgs(ch, emptyList())
        persist(ch)
    }

    // ---- notices (C1/C2) ---------------------------------------------------------------------------

    /**
     * C1/C2: a PC notice as a ProxAI message in the DM, once per [key]. Main thread (ProxApp.scope or
     * a receiver). Returns false if it's already there.
     */
    fun addNotice(key: String, kind: String, title: String, text: String, at: Long,
                  noQuote: Boolean = false, decision: String = "", next: String = ""): Boolean {
        if (text.isBlank() && title.isBlank()) return false
        val dm = msgs(Channel.DM)
        if (dm.any { it.noticeKey == key }) return false
        setMsgs(Channel.DM, dm + ChatMessage(role = "notice", text = text.trim(), title = title.trim(),
            kind = kind, noticeKey = key, time = at, noQuote = noQuote,
            decision = decision.trim().ifBlank { null }, next = next.trim().ifBlank { null }))
        persist(Channel.DM)
        return true
    }

    // ---- sending -----------------------------------------------------------------------------------

    fun send(text: String, fromVoice: Boolean = false, targetAppId: String? = null, channel: String = _active.value,
             attachments: List<Attachment> = emptyList(), approval: Pair<Approval, Boolean>? = null) {
        val prompt = text.trim()
        if (prompt.isEmpty() && attachments.isEmpty()) return
        val ch = if (channels.get(channel) != null) channel else Channel.DM
        // F1: any message in this chat answers the question the PC is waiting on (anything but yes cancels it).
        _approval.value?.takeIf { it.channel == ch }?.let { answered += it.approval.id; _approval.value = null }
        if (_busy.value) {
            // Used to be dropped silently, so a spoken prompt had to be repeated.
            val i = queue.indexOfLast { it.channel == ch }
            if (i >= 0 && i == queue.lastIndex && attachments.isEmpty() && queue[i].attachments.isEmpty()
                && approval == null && queue[i].approval == null) {
                val prev = queue[i]
                queue[i] = Queued(ch, prev.text + "\n" + prompt, prev.voice || fromVoice, prev.target ?: targetAppId)
            } else queue.addLast(Queued(ch, prompt, fromVoice, targetAppId, attachments, approval))
            refreshQueued()
            return
        }
        val chan = channel(ch)
        val target = targetAppId?.let { apps.get(it) }
        // Notices that arrived since his last message go along with this one (they aren't in the Hermes session).
        val notices = if (chan.isDm) ChatNotices.note(msgs(ch)) else ""
        val userMsg = ChatMessage(
            role = "user",
            text = when {
                approval != null -> Approval.shown(approval.second)
                target != null -> "Remix “${target.title}”: $prompt"
                else -> prompt
            },
            targetAppId = target?.id, attachments = attachments)
        val reply = ChatMessage(role = "assistant", text = "", streaming = true, targetAppId = target?.id, voice = fromVoice)
        setMsgs(ch, msgs(ch) + userMsg + reply)
        setBusy(ch)

        // B21 (0.9.9): the compact summary goes in the system part of every request of this session, never
        // in his message: the PC's plugins read his message as his words (11-01: a time inside a summary became
        // a scheduled job), and ProxAI took the summary's open items for what he was asking now.
        val carry = chan.carryOver
        val session = chan.sessionId
        // B21: the reminder list only when it matters (his message is about reminders, or the list changed),
        // not on every message for days.
        val reminderList = ReminderStore.listForNote(reminders.items.value, System.currentTimeMillis())
        val listReminders = ReminderStore.mentionsReminders(prompt) || reminderList != sentReminders[ch]
        if (listReminders) sentReminders[ch] = reminderList
        val wire = buildString {
            if (notices.isNotEmpty()) append(notices).append('\n')
            append(Prompts.turnNote(fromVoice, reminders = if (listReminders) reminderList else "",
                channel = if (chan.isDm) "" else chan.name)).append('\n')
            if (approval != null) append(Approval.note(approval.first, approval.second)).append('\n')
            if (attachments.isNotEmpty()) append(Attachments.note(attachments)).append('\n')
            append(prompt)
            if (target != null) {
                append("\n\nModify this existing app (\"${target.title}\"). Return the COMPLETE updated file ")
                append("in one ```html block.\n\n```html\n")
                append(apps.html(target.id).orEmpty())
                append("\n```")
            }
        }
        val system = Prompts.system(summary = carry)

        job = scope.launch {
            // X1-PM (11-16): no PC-mode check before sending (it could hold the first word up to ~5 s): the route
            // no longer depends on the mode.
            // C5: text files go in the message; pictures and PDF pages as image parts.
            val files = if (attachments.isEmpty()) "" else withContext(Dispatchers.IO) {
                runCatching { Attachments.textBlocks(attachments) }.getOrDefault("")
            }
            val images = if (attachments.isEmpty()) emptyList() else withContext(Dispatchers.IO) {
                runCatching { Attachments.dataUrls(attachments) }.getOrDefault(emptyList())
            }
            // The local model may not read pictures: a message with one goes to the cloud model.
            val picked = currentRoute(ch)
            val route = if (picked.local && images.isNotEmpty()) ModelRoute.cloud(settings.model) else picked
            update(ch, reply.id) { it.copy(model = route.label, local = route.local) }
            var failed: String? = null
            var usage: Usage? = null
            var answeredBy: String? = null
            var liveSession: String? = null
            update(ch, reply.id) { it.copy(lastEventAt = System.currentTimeMillis()) }
            hermes.stream(system, wire + files, session, route, images).collect { ev ->
                val now = System.currentTimeMillis()
                when (ev) {
                    is StreamEvent.Delta -> {
                        update(ch, reply.id) { it.copy(text = it.text + ev.text, thinking = false, lastEventAt = now) }
                        if (fromVoice) sendVoiceParts(ch, reply.id, done = false)
                    }
                    is StreamEvent.Thinking -> update(ch, reply.id) { it.copy(thinking = true, lastEventAt = now) }
                    is StreamEvent.Tool -> update(ch, reply.id) { m0 ->
                        val m = m0.copy(thinking = false, lastEventAt = now)
                        val others = m.tools.filterNot { it.id == ev.id }
                        val prev = m.tools.firstOrNull { it.id == ev.id }
                        val chip = if (ev.running) ToolChip(ev.id, ev.label, ev.emoji, true)
                        else (prev ?: ToolChip(ev.id, ev.label, ev.emoji, false)).copy(running = false)
                        m.copy(tools = others + chip)
                    }
                    is StreamEvent.Error -> failed = ev.message
                    is StreamEvent.Done -> { usage = ev.usage; answeredBy = ev.model; liveSession = ev.sessionId }
                    is StreamEvent.Status -> onPcStatus(ch, ev)
                }
            }
            // Cloud replies report the profile's virtual name ("proxai"), so keep the route's label.
            val label = if (route.local) answeredBy?.let { "$it · local" } ?: route.label else "cloud"
            // The reply's usage adds up every model call of the turn; ask the PC for the real size.
            val ctx = if (usage != null) withTimeoutOrNull(4_000) {
                withContext(Dispatchers.IO) { runCatching { PcStatsClient(settings).context(liveSession ?: session) }.getOrNull() }
            } else null
            // X1: the real cost (with prompt caching) instead of the old uncached upper bound.
            val realCost = if (usage != null && !route.local) withTimeoutOrNull(4_000) {
                hermes.sessionCost(liveSession ?: session)
            } else null
            update(ch, reply.id) {
                it.copy(model = label, promptTokens = usage?.promptTokens ?: 0, completionTokens = usage?.completionTokens ?: 0,
                    contextTokens = ctx?.tokens ?: 0, contextWindow = ctx?.window ?: 0, sessionCostUsd = realCost ?: -1.0)
            }
            finish(ch, reply.id, failed, target?.id, fromVoice, remindersAsked = ReminderStore.userAsked(prompt))
            endPcCompaction(done = failed == null)
            // F1: a question the PC now waits on pops up. No auto-compact under it: the answer would go to a new
            // session, where the waiting question isn't, and he'd have to say yes again.
            val waiting = if (failed == null) fetchApproval(ch, listOfNotNull(liveSession, session).distinct()) else null
            if (waiting == null) maybeAutoCompact(ch, route, msgs(ch).firstOrNull { it.id == reply.id })
            sendQueued()
        }
        job?.invokeOnCompletion { cause ->
            if (cause != null) scope.launch {
                finish(ch, reply.id, "Stopped", null, false, notify = false)
                // finish() skips a reply that's gone (New chat or a deleted channel mid-reply): still not busy.
                if (_busyChannel.value == ch) setBusy(null)
                // Close a voice reply that was being spoken part by part.
                if (fromVoice && partsSent.remove(reply.id) != null) _voiceParts.tryEmit(VoicePart(reply.id, "", false, true))
                sendQueued()
            }
        }
    }

    private fun sendQueued() {
        if (_busy.value) return
        val q = queue.removeFirstOrNull() ?: return
        refreshQueued()
        send(q.text, q.voice, q.target, q.channel, q.attachments, q.approval)
    }

    fun cancel() {
        queue.removeAll { it.channel == _busyChannel.value }
        refreshQueued()
        job?.cancel()
    }

    // ---- F1 (0.9.9): approvals ----------------------------------------------------------------------

    private val _approval = MutableStateFlow<PendingApproval?>(null)
    /** A question a yes-gate on the PC is waiting on: a pop-up with Reject / Accept (Chat and the assistant). */
    val approval: StateFlow<PendingApproval?> = _approval
    private val answered = mutableSetOf<String>()               // answered or closed on this phone
    private val sentReminders = mutableMapOf<String, String>()  // B21: the reminder list each chat last got

    /** Asks the PC whether a yes-gate waits on this chat (after each reply, and when ProxAI comes back on screen). */
    fun checkApproval(ch: String = _active.value) {
        if (_busyChannel.value == ch || !settings.isConfigured) return
        scope.launch {
            if (fetchApproval(ch, listOf(channel(ch).sessionId)) == null && _approval.value?.channel == ch) {
                _approval.value = null   // answered elsewhere, or it expired
            }
        }
    }

    private suspend fun fetchApproval(ch: String, sessions: List<String>): Approval? {
        val a = withTimeoutOrNull(4_000) {
            withContext(Dispatchers.IO) { runCatching { PcStatsClient(settings).approval(sessions) }.getOrNull() }
        }
        if (a == null || a.id in answered || a.expiresAt < System.currentTimeMillis()) return null
        _approval.value = PendingApproval(ch, a)
        return a
    }

    /**
     * WAKE-PHONE round 2 (yes-gates): is a yes-gate waiting on [ch] right now? Asked by a wake overlay right
     * before it sends. A waiting one shows its Accept / Reject sheet again, even if it was closed here before.
     * [GateCheck.UNKNOWN] when the PC can't be asked (off, off the tailnet, an error, 4 s gone by).
     */
    suspend fun gateNow(ch: String): GateCheck {
        if (!settings.isConfigured) return GateCheck.UNKNOWN
        val got = withTimeoutOrNull(4_000) {
            withContext(Dispatchers.IO) { runCatching { PcStatsClient(settings).approvalOrThrow(listOf(channel(ch).sessionId)) } }
        } ?: return GateCheck.UNKNOWN
        val a = got.getOrElse { return GateCheck.UNKNOWN } ?: return GateCheck.NONE
        if (a.expiresAt in 1 until System.currentTimeMillis()) return GateCheck.NONE
        answered -= a.id
        _approval.value = PendingApproval(ch, a)
        return GateCheck.WAITING
    }

    /** Accept / Reject: "yes" / "no" as his next message in that chat, with a note that it came from the pop-up. */
    fun answerApproval(accept: Boolean) {
        val p = _approval.value ?: return
        answered += p.approval.id
        _approval.value = null
        val voice = msgs(p.channel).lastOrNull { it.role == "assistant" }?.voice ?: false
        send(Approval.word(accept), fromVoice = voice, channel = p.channel, approval = p.approval to accept)
    }

    /** Closed without choosing: nothing is sent; he can still type yes or no. */
    fun closeApproval() {
        _approval.value?.let { answered += it.approval.id }
        _approval.value = null
    }

    /** C7: the conversation on screen goes to the trash and the channel starts a fresh session. */
    fun newChat() {
        val ch = _active.value
        if (_busyChannel.value == ch) cancel()
        queue.removeAll { it.channel == ch }
        refreshQueued()
        moveToTrash(ch)
        _channelList.value = channels.all()
    }

    // ---- auto-compact ----------------------------------------------------------------------------

    /**
     * Limits are unchanged (cloud: Settings → Context, default 60k; local: Hermes's own 85% of the
     * 64k window, ~56k). Only the measuring changed (0.4.1):
     * - Local model: the app never compacts by itself. The local model reports only newly read
     *   tokens (it reuses what it read before), so the app can't see the real size; Hermes can, and
     *   compacts on the PC, which [onPcStatus] shows.
     * - Cloud: a reply's token count adds up every model call of the turn, so tool-heavy replies
     *   looked huge. It's divided by the calls (tools used + 1) to estimate the conversation size.
     */
    private fun maybeAutoCompact(ch: String, route: ModelRoute, reply: ChatMessage?) {
        val limit = settings.compactAtTokens
        if (limit <= 0 || route.local || reply == null) return
        if (contextSize(reply) >= limit) compact(auto = true, ch = ch)
    }

    // ---- compaction done by Hermes on the PC ------------------------------------------------------

    private var pcNote: Pair<String, String>? = null   // channel, note id

    private fun onPcStatus(ch: String, ev: StreamEvent.Status) {
        when (pcCompactionStep(ev)) {
            "start" -> if (pcNote == null) {
                val note = ChatMessage(role = "note", text = "Compacting the conversation on the PC…", streaming = true)
                pcNote = ch to note.id
                setMsgs(ch, msgs(ch) + note)
            }
            "done" -> endPcCompaction(done = true)
        }
    }

    private fun endPcCompaction(done: Boolean) {
        val (ch, id) = pcNote ?: return
        pcNote = null
        update(ch, id) {
            it.copy(streaming = false, text = if (done)
                "The PC compacted the conversation: ProxAI summarized the earlier part and keeps going from that summary."
            else "Compacting on the PC was interrupted.")
        }
        persist(ch)
    }

    /**
     * Long sessions get expensive: Hermes resends the whole history every turn. This asks Hermes
     * for a short summary, starts a fresh session, and sends the summary with the next message.
     */
    fun compact(auto: Boolean = false, ch: String = _active.value) {
        if (_busy.value) return
        if (sessionMessages(msgs(ch)).none { it.role == "user" }) return
        val stats = stats(msgs(ch))
        setBusy(ch)
        val note = ChatMessage(role = "note", text = "Compacting the conversation…", streaming = true)
        setMsgs(ch, msgs(ch) + note)
        val oldSession = channel(ch).sessionId
        val earlier = channel(ch).carryOver
        val route = currentRoute(ch)
        job = scope.launch {
            // B21: an earlier summary lives in the system part now, so the new one is asked for with it.
            val summary = runCatching {
                hermes.ask(Prompts.COMPACT, oldSession, system = Prompts.system(summary = earlier), route = route)
            }
            summary.onSuccess { text ->
                val clean = ReminderStore.strip(text).trim()
                if (clean.isBlank()) {
                    update(ch, note.id) { it.copy(streaming = false, text = "Couldn't compact: ProxAI sent an empty summary.") }
                } else {
                    channels.update(ch) { it.copy(sessionId = Channel.newSessionId(), carryOver = clean) }
                    val why = if (auto) "The conversation reached about ${stats.lastPromptTokens / 1000}k tokens, so it was compacted."
                        else "The conversation was compacted."
                    update(ch, note.id) {
                        it.copy(streaming = false,
                            text = "$why Your next message starts a fresh, " +
                                "cheaper session that gets this summary:\n\n$clean")
                    }
                }
            }.onFailure { e ->
                update(ch, note.id) { it.copy(streaming = false, text = "Couldn't compact: ${e.message ?: "error"}") }
            }
            setBusy(null)
            persist(ch)
            sendQueued()
        }
        job?.invokeOnCompletion { cause ->
            if (cause != null) scope.launch {
                update(ch, note.id) { if (it.streaming) it.copy(streaming = false, text = "Compacting stopped.") else it }
                setBusy(null)
                persist(ch)
            }
        }
    }

    private suspend fun finish(ch: String, id: String, error: String?, targetId: String?, fromVoice: Boolean,
                               notify: Boolean = true, remindersAsked: Boolean = false) {
        val msg = msgs(ch).firstOrNull { it.id == id } ?: return
        if (!msg.streaming) return
        val html = AppStore.extractHtml(msg.text)
        val saved = html?.let { withContext(Dispatchers.IO) { apps.save(it, targetId) } }
        // Reminder tags -> phone alarms; the tag itself isn't shown or spoken. ReminderStore.keep drops
        // past times and repeats, and allows one unasked reminder per reply (10-23: it set them every reply).
        val set = if (error == null) ReminderStore.keep(ReminderStore.parse(msg.text), remindersAsked,
                reminders.items.value, System.currentTimeMillis())
            .map { reminders.add(it.at, it.text) }
            .map { "${ReminderStore.format(it.at)}: ${it.text}" } else emptyList()
        // B6: moves and cancels, only when Nathan's message was about reminders.
        val changed = if (error == null && remindersAsked) {
            ReminderStore.parseMoves(msg.text).mapNotNull { (rid, at) ->
                reminders.move(rid, at)?.let { "Moved to ${ReminderStore.format(it.at)}: ${it.text}" }
            } + ReminderStore.parseCancels(msg.text).mapNotNull { rid ->
                reminders.get(rid)?.let { r -> reminders.remove(rid); "Cancelled: ${r.text}" }
            }
        } else emptyList()
        update(ch, id) {
            it.copy(streaming = false, thinking = false, appId = saved?.id ?: it.appId,
                text = ReminderStore.strip(it.text),
                reminders = set + changed,
                error = if (it.text.isBlank() || error == "Stopped") error else null,
                tools = it.tools.map { t -> t.copy(running = false) })
        }
        setBusy(null)
        persist(ch)
        val done = msgs(ch).firstOrNull { it.id == id } ?: return
        if (notify && fromVoice) sendVoiceParts(ch, id, done = true)
        if (notify) _done.tryEmit(ReplyDone(done, fromVoice))
        if (notify && settings.replyNotifications && !inForeground()) {
            val c = channel(ch)
            Notifier.reply(context, SpeechController.spokenText(done.text).ifBlank { done.error ?: "" },
                channelId = c.id, channelName = if (c.isDm) null else c.name)
        }
    }

    /** Emits the voice reply's newly finished parts (all of them, plus last=true, when [done]). */
    private fun sendVoiceParts(ch: String, id: String, done: Boolean) {
        val msg = msgs(ch).firstOrNull { it.id == id } ?: return
        val parts = VoiceParts.finished(msg.text, done)
        val sent = partsSent[id] ?: 0
        for (i in sent until parts.size) {
            _voiceParts.tryEmit(VoicePart(id, parts[i], first = i == 0, last = done && i == parts.lastIndex))
        }
        partsSent[id] = maxOf(sent, parts.size)
        if (done) {
            // Nothing new to say at the end (or no text at all): still close the reply; say the error if any.
            if (parts.size <= sent) _voiceParts.tryEmit(VoicePart(id, if (parts.isEmpty()) msg.error.orEmpty() else "",
                first = sent == 0, last = true))
            partsSent.remove(id)
        }
    }

    private fun update(ch: String, id: String, fn: (ChatMessage) -> ChatMessage) {
        setMsgs(ch, msgs(ch).map { if (it.id == id) fn(it) else it })
    }

    private fun persist(ch: String) {
        val arr = messagesToJson(msgs(ch).takeLast(200))
        val file = fileOf(ch)
        scope.launch(Dispatchers.IO) { runCatching { file.writeText(arr.toString()) } }
    }

    private fun load(file: File): List<ChatMessage> = runCatching { messagesFromJson(JSONArray(file.readText())) }
        .getOrDefault(emptyList())

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
            val real = session.lastOrNull { it.sessionCostUsd >= 0 }?.sessionCostUsd
            val cost = real ?: (cloud.sumOf { it.promptTokens * INPUT_USD_PER_MTOK + it.completionTokens * OUTPUT_USD_PER_MTOK } / 1_000_000.0)
            val lastReply = session.lastOrNull { it.promptTokens > 0 || it.local }
            val local = lastReply?.local == true
            val last = when {
                lastReply == null -> 0
                lastReply.contextTokens > 0 -> lastReply.contextTokens
                local -> 0
                else -> contextEstimate(lastReply)
            }
            return SessionStats(last, input, output, cost, localManaged = local, costExact = real != null)
        }

        /** A reply's tokens cover every model call of its turn; per call ≈ the conversation's size. */
        fun contextEstimate(m: ChatMessage): Int = m.promptTokens / (m.tools.size + 1)

        /** The PC's real number when we have it, else the estimate. */
        fun contextSize(m: ChatMessage): Int = if (m.contextTokens > 0) m.contextTokens else contextEstimate(m)

        /** "start" / "done" / null for one of Hermes's status lines. */
        fun pcCompactionStep(ev: StreamEvent.Status): String? = when {
            ev.kind == "compacted" || ev.text.contains("compaction complete", ignoreCase = true) -> "done"
            ev.text.contains("Compacting context", ignoreCase = true) -> "start"
            else -> null
        }
    }
}

/** chat.json / chats/<channel>.json / the trash: a conversation as JSON (streaming state isn't saved). */
fun messagesToJson(list: List<ChatMessage>): JSONArray {
    val arr = JSONArray()
    list.forEach { m ->
        arr.put(JSONObject().put("id", m.id).put("role", m.role).put("text", m.text)
            .put("error", m.error).put("appId", m.appId).put("targetAppId", m.targetAppId)
            .put("time", m.time).put("model", m.model).put("local", m.local)
            .put("promptTokens", m.promptTokens).put("completionTokens", m.completionTokens)
            .put("contextTokens", m.contextTokens).put("contextWindow", m.contextWindow)
            .put("reminders", JSONArray(m.reminders)).put("voice", m.voice)
            .put("sessionCostUsd", m.sessionCostUsd)
            .put("title", m.title).put("kind", m.kind).put("noticeKey", m.noticeKey)
            .put("noQuote", m.noQuote).put("decision", m.decision).put("next", m.next)
            .put("attachments", JSONArray().apply {
                m.attachments.forEach { a ->
                    put(JSONObject().put("name", a.name).put("kind", a.kind).put("files", JSONArray(a.files)).put("pages", a.pages)
                        .put("info", a.info))
                }
            })
            .put("tools", JSONArray().apply {
                m.tools.forEach { put(JSONObject().put("id", it.id).put("label", it.label).put("emoji", it.emoji)) }
            }))
    }
    return arr
}

fun messagesFromJson(arr: JSONArray): List<ChatMessage> {
    fun JSONObject.str(k: String) = optString(k).takeIf { it.isNotEmpty() && it != "null" }
    return (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val tools = o.optJSONArray("tools")
        val rem = o.optJSONArray("reminders")
        val att = o.optJSONArray("attachments")
        ChatMessage(
            id = o.getString("id"), role = o.getString("role"), text = o.getString("text"),
            error = o.str("error"), appId = o.str("appId"), targetAppId = o.str("targetAppId"),
            time = o.optLong("time"), model = o.str("model"), local = o.optBoolean("local"),
            promptTokens = o.optInt("promptTokens"), completionTokens = o.optInt("completionTokens"),
            contextTokens = o.optInt("contextTokens"), contextWindow = o.optInt("contextWindow"),
            reminders = (0 until (rem?.length() ?: 0)).map { rem!!.getString(it) },
            voice = o.optBoolean("voice"),
            sessionCostUsd = o.optDouble("sessionCostUsd", -1.0),
            title = o.str("title"), kind = o.str("kind"), noticeKey = o.str("noticeKey"),
            noQuote = ChatNotices.noQuoteOf(o.opt("noQuote")), decision = o.str("decision"), next = o.str("next"),
            attachments = (0 until (att?.length() ?: 0)).map {
                val a = att!!.getJSONObject(it)
                val fs = a.optJSONArray("files")
                Attachment(a.optString("name"), a.optString("kind"), (0 until (fs?.length() ?: 0)).map { j -> fs!!.getString(j) },
                    a.optInt("pages"), a.optString("info"))
            },
            tools = (0 until (tools?.length() ?: 0)).map {
                val t = tools!!.getJSONObject(it)
                ToolChip(t.getString("id"), t.getString("label"), t.optString("emoji"), false)
            })
    }
}
