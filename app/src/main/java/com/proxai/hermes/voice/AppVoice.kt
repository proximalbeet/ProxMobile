package com.proxai.hermes.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import com.proxai.hermes.ProxApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Prox's voice for ProxAI apps ("voice" feature, 0.7.0): ProxMobile fetches the audio from the
 * PC (it holds the key) and plays it here. One voice at a time; a new [say] stops the old one.
 * [say] blocks until the text has been spoken, so an app can wait for it (voice quiz: speak, then
 * listen). It throws when Prox's voice can't be used, and the app falls back to the phone voice.
 */
object AppVoice {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val attrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private var current: Job? = null
    private val lock = Any()

    /** Max text per call; longer text is cut at a sentence end. */
    const val MAX_CHARS = 6000

    /** Returns [ProxVoice.SPOKEN_BY_PC] once spoken, or "stopped" if [stop] or a newer [say] cut it off. */
    fun say(ctx: Context, text: String): String {
        val settings = ProxApp.instance.settings
        if (!settings.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        if (!settings.proxVoice) throw IOException("Prox's voice is off in ProxAI's settings")
        val clean = SpeechController.spokenText(trim(text))
        if (clean.isBlank()) return ProxVoice.SPOKEN_BY_PC
        val prox = ProxVoice(settings, ctx.cacheDir)
        var failure: Exception? = null
        val job = synchronized(lock) {
            current?.cancel()
            scope.launch {
                try { speakAll(ctx, prox, clean) } catch (e: CancellationException) { throw e } catch (e: Exception) { failure = e }
            }.also { current = it }
        }
        runBlocking { job.join() }
        failure?.let { throw it }
        return if (job.isCancelled) "stopped" else ProxVoice.SPOKEN_BY_PC
    }

    fun stop() = synchronized(lock) { current?.cancel(); current = null }

    /** Cuts [text] to [MAX_CHARS] at the last sentence end before the limit. */
    fun trim(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val cut = text.substring(0, MAX_CHARS)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > MAX_CHARS / 2) cut.substring(0, end + 1) else cut
    }

    private suspend fun speakAll(ctx: Context, prox: ProxVoice, clean: String) {
        val parts = ProxVoice.chunks(clean)
        if (parts.isEmpty()) return
        val audio = ctx.getSystemService(AudioManager::class.java)
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(attrs).build()
        audio?.requestAudioFocus(focus)
        try {
            kotlinx.coroutines.coroutineScope {
                var next: Deferred<File>? = async { prox.synth(parts[0]) }
                for (i in parts.indices) {
                    val file = try {
                        next!!.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // GPU briefly full on the PC: one retry (same as the chat voice), then give up.
                        if (ProxVoice.unreachable(e)) throw e
                        delay(1200)
                        prox.synth(parts[i])
                    }
                    next = if (i + 1 < parts.size) async { prox.synth(parts[i + 1]) } else null
                    val played = try { play(file) } finally { file.delete() }
                    if (!played) throw IOException("Prox's voice couldn't be played")
                }
            }
        } finally {
            audio?.abandonAudioFocusRequest(focus)
        }
    }

    /** True when the file played to the end, false on a player error. */
    private suspend fun play(file: File): Boolean = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        fun done(ok: Boolean) {
            runCatching { mp.release() }
            if (cont.isActive) cont.resume(ok)
        }
        try {
            mp.setAudioAttributes(attrs)
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { done(true) }
            mp.setOnErrorListener { _, _, _ -> done(false); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            done(false)
        }
        cont.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() } }
    }
}
