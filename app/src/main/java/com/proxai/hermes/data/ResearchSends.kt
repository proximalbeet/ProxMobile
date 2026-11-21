package com.proxai.hermes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import java.io.File

/** What goes to the PC: someone else's app (its APK files), or a picked file already copied into the cache. */
sealed interface ResearchTarget {
    val title: String

    data class App(val app: PhoneApp) : ResearchTarget {
        override val title: String get() = app.label
    }

    data class PickedFile(val name: String, val copy: File) : ResearchTarget {
        override val title: String get() = name
    }
}

/** A send to the PC as the chat shows it: going ([done] of [total] bytes), failed ([error]) or finished. */
data class ResearchSend(
    val title: String,
    val done: Long = 0,
    val total: Long = 0,
    val finished: Boolean = false,
    val error: String? = null,
) {
    val percent: Int get() = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
}

/**
 * P3 fix (0.9.6): sends to the PC run here, outside any screen, so leaving ProxAI, switching tabs, the app
 * lock or turning the phone doesn't stop them. 0.9.5 sent from inside its dialog, and the first real try (the
 * Claude app, 10-31) was cut off. ResearchSendService runs [run] on its own thread and keeps ProxAI alive
 * with a progress notification; the chat shows [current] and the finished attachments in [ready] in its tray.
 * They stay here (not in the chat screen, which forgets its tray when you switch tabs) until the message that
 * carries them is sent ([takeReady]) or they're removed. One send at a time.
 */
object ResearchSends {
    private val _current = MutableStateFlow<ResearchSend?>(null)
    val current: StateFlow<ResearchSend?> = _current
    private val _ready = MutableStateFlow<List<Attachment>>(emptyList())
    val ready: StateFlow<List<Attachment>> = _ready
    @Volatile private var client: AppResearch? = null
    @Volatile private var cancelAsked = false

    /** A send is going (or about to start). */
    val busy: Boolean get() = _current.value?.finished == false

    /** Reserves the one send slot for [title]; false if another send is going. */
    fun claim(title: String): Boolean = synchronized(this) {
        if (busy) return false
        cancelAsked = false
        _current.value = ResearchSend(title)
        true
    }

    /**
     * Sends [target] to the end (blocking). The attachment lands in [ready]; null if it failed or was
     * cancelled. A picked file's cache copy is deleted either way. [icon] saves an app's icon for the bubble.
     */
    fun run(target: ResearchTarget, client: AppResearch, icon: (String) -> String? = { null },
            onChange: (ResearchSend) -> Unit = {}): Attachment? {
        var state = ResearchSend(target.title)
        fun publish(s: ResearchSend) {
            state = s
            _current.value = s
            onChange(s)
        }
        try {
            if (cancelAsked) {
                _current.value = null
                return null
            }
            this.client = client
            publish(state)
            val attachment = when (target) {
                is ResearchTarget.App -> {
                    val sent = client.send(target.app) { d, t -> publish(state.copy(done = d, total = t)) }
                    PhoneApps.attachment(target.app, icon(target.app.packageName), sent)
                }
                is ResearchTarget.PickedFile -> {
                    val sent = client.sendFile(target.name, target.copy) { d, t -> publish(state.copy(done = d, total = t)) }
                    PhoneApps.fileAttachment(target.name, sent)
                }
            }
            _ready.update { it + attachment }
            publish(state.copy(done = state.total, finished = true))
            return attachment
        } catch (e: Exception) {
            if (cancelAsked || e.message == "Cancelled") _current.value = null
            else publish(state.copy(finished = true, error = e.message ?: "Couldn't send it to the PC"))
            return null
        } finally {
            this.client = null
            (target as? ResearchTarget.PickedFile)?.copy?.delete()
        }
    }

    /** A send that failed before [run] could start (e.g. the picked file couldn't be read). */
    fun fail(title: String, error: String?) {
        _current.value = ResearchSend(title, finished = true, error = error ?: "Couldn't send it to the PC")
    }

    /** Stops the send that's going, or the one about to start. */
    fun cancel() {
        cancelAsked = true
        val c = client
        if (c != null) c.cancel() else if (busy) _current.value = null
    }

    /** The finished attachments, for the message being sent: each is handed out once. */
    fun takeReady(): List<Attachment> = _ready.getAndUpdate { emptyList() }

    /** Takes one out of the tray (its ✕); true if it was there. The caller deletes its icon. */
    fun remove(a: Attachment): Boolean {
        var found = false
        _ready.update { list -> found = a in list; list - a }
        return found
    }

    /** Clears a failed send from the tray. */
    fun dismiss() {
        if (_current.value?.finished == true) _current.value = null
    }

    /** Tests only: back to nothing going. */
    fun reset() {
        _current.value = null
        _ready.value = emptyList()
        client = null
        cancelAsked = false
    }
}
