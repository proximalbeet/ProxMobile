package com.proxai.hermes.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.widget.Toast
import com.proxai.hermes.ProxApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.supervisorScope
import java.io.File
import java.util.Locale
import kotlin.coroutines.resume

/** Preparing = waiting for the PC to generate the first Kanye audio. */
enum class MicState { Idle, Listening, Thinking, Preparing, Speaking }

/**
 * Speech-to-text + text-to-speech for one screen. Create in onCreate, call [release] in onDestroy.
 *
 * Once ProxAI is the default assistant, Android points the *default* recognizer at our stub
 * service, so we always bind a real recognizer explicitly (Google's, else the on-device one).
 */
class SpeechController(private val context: Context, private val onFinal: (String) -> Unit) {
    private val _state = MutableStateFlow(MicState.Idle)
    val state: StateFlow<MicState> = _state
    private val _partial = MutableStateFlow("")
    val partial: StateFlow<String> = _partial
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    var onSpeakDone: (() -> Unit)? = null

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: String? = null
    private var lastUtterance: String? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings get() = ProxApp.instance.settings
    private val kanye by lazy { KanyeVoice(settings, context.cacheDir) }
    private var kanyeJob: Job? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private val speechAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(speechAttrs).build()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { _state.value = MicState.Speaking }
                    override fun onDone(id: String?) { if (id == lastUtterance) spokeDone() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { if (id == lastUtterance) spokeDone() }
                    override fun onStop(id: String?, interrupted: Boolean) {}
                })
                pendingSpeech?.let { speak(it) }
                pendingSpeech = null
            }
        }
    }

    private fun spokeDone() {
        audio?.abandonAudioFocusRequest(focus)
        if (_state.value == MicState.Speaking || _state.value == MicState.Preparing) _state.value = MicState.Idle
        onSpeakDone?.let { cb -> android.os.Handler(context.mainLooper).post { cb() } }
    }

    // ---- Speech to text ------------------------------------------------------------------------
    //
    // One "dictation" = one mic tap. Google's recognizer ends a segment at every short pause, so
    // the segments are joined and listening restarts until you've been quiet for
    // settings.sendPauseMs (or you tap send). Fixes from the 2026-10-22 drive:
    //  * a fresh recognizer on every mic tap, and a rebuilt one after any error, so a restarted or
    //    updated Google speech service can't leave the mic dead until the phone reboots
    //  * after repeated Google failures, the phone's on-device recognizer is used instead
    //  * every error is shown and logged (ERROR_CLIENT used to be hidden)

    private var dictating = false
    private var generation = 0
    private val heard = StringBuilder()
    private var segmentPartial = ""
    private var retries = 0
    private var emptyRetryUsed = false
    private var startedAt = 0L
    private var pauseJob: Job? = null
    private var failuresInARow = 0
    private var midFailures = 0
    private var useOnDevice = false

    private fun createRecognizer(): SpeechRecognizer? {
        val onDeviceOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        if (useOnDevice && onDeviceOk) {
            SpeechLog.add("using the on-device recognizer")
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        val own = context.packageName
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo }
            .filter { it.packageName != own }
        val preferred = services.firstOrNull { it.packageName == "com.google.android.googlequicksearchbox" }
            ?: services.firstOrNull { it.packageName.startsWith("com.google") }
            ?: services.firstOrNull()
        if (preferred != null) {
            return SpeechRecognizer.createSpeechRecognizer(context, ComponentName(preferred.packageName, preferred.name))
        }
        if (onDeviceOk) return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        return null
    }

    private fun dropRecognizer() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // Ask for longer pauses; many recognizers ignore these, which is why segments are joined.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        if (Build.VERSION.SDK_INT >= 33) {
            // Words it should expect (cron, firmware, Hermes...): fewer "crime"/"firewall" mix-ups.
            putStringArrayListExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, SpeechFixes.vocabulary(settings.vocabulary))
        }
    }

    /** Start a dictation (mic tap, power button, hands-free). */
    fun listen() {
        stopSpeaking()
        cancelPause()
        _error.value = null
        _partial.value = ""
        heard.clear()
        segmentPartial = ""
        retries = MAX_RETRIES
        midFailures = 0
        emptyRetryUsed = false
        startedAt = System.currentTimeMillis()
        dictating = true
        // A recognizer kept from earlier can be bound to a speech service that has since restarted.
        dropRecognizer()
        startSegment()
    }

    private fun startSegment() {
        if (!dictating) return
        val rec = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (rec == null) {
            endDictation("No speech recognizer on this phone. Install or enable the Google app.")
            return
        }
        val gen = ++generation
        rec.setRecognitionListener(Listener(gen))
        segmentPartial = ""
        _state.value = MicState.Listening
        try {
            rec.startListening(recognizerIntent())
        } catch (e: Exception) {
            SpeechLog.add("startListening threw ${e.javaClass.simpleName}: ${e.message}")
            recover("Speech recognizer failed to start", SpeechRecognizer.ERROR_CLIENT)
        }
    }

    private fun restartSegmentSoon() {
        scope.launch {
            delay(200) // restarting instantly can make some recognizers answer "busy"
            if (dictating) startSegment()
        }
    }

    private fun schedulePause(ms: Long) {
        cancelPause()
        pauseJob = scope.launch {
            delay(ms)
            if (dictating) finishNow()
        }
    }

    private fun cancelPause() {
        pauseJob?.cancel(); pauseJob = null
    }

    private fun shownText(): String = listOf(heard.toString().trim(), segmentPartial.trim())
        .filter { it.isNotEmpty() }.joinToString(" ")

    /** True while a dictation is running (between the mic tap and sending). */
    val isDictating: Boolean get() = dictating

    /** Send what was heard so far (send button, orb tap, or the pause timer). */
    fun finishNow() {
        if (!dictating) return
        val raw = shownText()
        endDictation(null)
        val text = SpeechFixes.apply(raw, SpeechFixes.parse(settings.wordFixes)).trim()
        _partial.value = text
        if (text.isNotBlank()) onFinal(text)
    }

    /** Old name, kept for callers: tapping the mic while listening sends what you said. */
    fun stopListening() = finishNow()

    /** Stop and throw away what was heard. */
    fun cancelListening() {
        if (dictating) endDictation(null)
        if (_state.value == MicState.Listening) _state.value = MicState.Idle
        _level.value = 0f
    }

    private fun endDictation(error: String?) {
        dictating = false
        generation++
        cancelPause()
        runCatching { recognizer?.cancel() }
        _level.value = 0f
        if (_state.value == MicState.Listening) _state.value = MicState.Idle
        _error.value = error
    }

    /** Recognizer broke (service died, busy, network blip...): rebuild it and try again. */
    private fun recover(message: String, code: Int) {
        dropRecognizer()
        failuresInARow++
        if (failuresInARow >= 3 && !useOnDevice && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            useOnDevice = true
        }
        // Mid-dictation (you've already said something): a hiccup while restarting must not send
        // half a sentence. Keep listening, up to MAX_MID_FAILURES in a row; the pause timer
        // (or Send) still ends the message.
        val midDictation = heard.isNotBlank() && midFailures < MAX_MID_FAILURES
        if (midDictation) {
            midFailures++
            val pause = settings.sendPauseMs
            if (pause > SEND_AT_ONCE_MS && pauseJob?.isActive != true) schedulePause(pause)
        }
        if (midDictation || retries > 0) {
            if (!midDictation) retries--
            SpeechLog.add("$message (${errorName(code)}), retrying with a new recognizer")
            scope.launch {
                delay(350)
                if (dictating) startSegment()
            }
            return
        }
        SpeechLog.add("$message (${errorName(code)}), giving up")
        val note = "$message (${errorName(code)})"
        if (shownText().isNotBlank()) {
            finishNow()
            _error.value = note
        } else {
            endDictation("$note. Tap the mic to try again.")
        }
    }

    private inner class Listener(private val gen: Int) : RecognitionListener {
        private val live get() = dictating && gen == generation

        override fun onReadyForSpeech(params: Bundle?) { if (live) _state.value = MicState.Listening }
        override fun onBeginningOfSpeech() { if (live) cancelPause() }
        override fun onRmsChanged(rmsdB: Float) { if (live) _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { if (live) _level.value = 0f }

        override fun onError(error: Int) {
            if (!live) return
            _level.value = 0f
            // Words already shown for this phrase are kept, not lost when listening restarts.
            if (segmentPartial.isNotBlank()) {
                if (heard.isNotEmpty()) heard.append(' ')
                heard.append(segmentPartial.trim())
                segmentPartial = ""
            }
            val pause = settings.sendPauseMs
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (heard.isNotBlank()) {
                        // Silence after you'd already said something.
                        when {
                            overTime() || pause in 1..SEND_AT_ONCE_MS -> finishNow()
                            else -> {
                                // e.g. a cough or road noise stopped the timer: start it again.
                                if (pause > 0 && pauseJob?.isActive != true) schedulePause(pause)
                                restartSegmentSoon()
                            }
                        }
                    } else if (!emptyRetryUsed) {
                        // Nothing heard at all: listen once more before giving up.
                        emptyRetryUsed = true
                        SpeechLog.add("${errorName(error)} with no words, listening once more")
                        restartSegmentSoon()
                    } else {
                        endDictation("Didn't catch that. Tap the mic to try again.")
                    }
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    SpeechLog.add(errorName(error))
                    endDictation("Microphone permission is needed.")
                }
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    recover("Speech recognition lost the network", error)
                SpeechRecognizer.ERROR_AUDIO ->
                    recover("Couldn't use the microphone (another app or a Bluetooth mic may hold it)", error)
                else -> recover("Speech recognizer error", error)
            }
        }

        override fun onResults(results: Bundle?) {
            if (!live) return
            _level.value = 0f
            failuresInARow = 0
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                .ifEmpty { segmentPartial.trim() }
            segmentPartial = ""
            if (text.isNotEmpty()) {
                if (heard.isNotEmpty()) heard.append(' ')
                heard.append(text)
                retries = MAX_RETRIES
                midFailures = 0
            }
            _partial.value = shownText()
            val pause = settings.sendPauseMs
            when {
                heard.isEmpty() -> onError(SpeechRecognizer.ERROR_NO_MATCH)
                overTime() -> finishNow()
                pause in 1..SEND_AT_ONCE_MS -> finishNow()
                else -> {
                    // Keep listening; send after the pause unless you start talking again.
                    if (pause > 0) schedulePause(pause)
                    restartSegmentSoon()
                }
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live) return
            val p = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (p.isNotBlank()) {
                cancelPause()
                segmentPartial = p
                _partial.value = shownText()
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun overTime() = System.currentTimeMillis() - startedAt > MAX_DICTATION_MS

    fun setThinking(thinking: Boolean) {
        if (thinking) _state.value = MicState.Thinking
        else if (_state.value == MicState.Thinking) _state.value = MicState.Idle
    }

    /** Load the Kanye model on the PC now, so the reply doesn't wait for it (~9 s cold). */
    fun warmupVoice() {
        if (settings.kanyeVoice) kanye.warmup()
    }

    fun speak(text: String) {
        val clean = spokenText(text)
        if (clean.isBlank()) { spokeDone(); return }
        stopSpeaking()
        audio?.requestAudioFocus(focus)
        if (settings.kanyeVoice && settings.isConfigured) speakKanye(clean) else speakPhone(clean)
    }

    private fun speakPhone(clean: String) {
        if (!ttsReady) { pendingSpeech = clean; return }
        _state.value = MicState.Speaking
        // TTS has a per-utterance length limit; split long replies.
        val parts = clean.chunked(3500)
        lastUtterance = "proxai-${parts.size - 1}"
        parts.forEachIndexed { i, part ->
            tts?.speak(part, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "proxai-$i")
        }
    }

    /** Plays the reply sentence by sentence, fetching the next piece while the current one plays. */
    private fun speakKanye(clean: String) {
        val parts = KanyeVoice.chunks(clean)
        if (parts.isEmpty()) { spokeDone(); return }
        _state.value = MicState.Preparing
        kanyeJob = scope.launch {
            supervisorScope {
                var next: Deferred<File>? = async(Dispatchers.IO) { kanye.synth(parts[0]) }
                for (i in parts.indices) {
                    val file = try {
                        next!!.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // PC off, service down, GPU busy...: say the rest in the phone voice.
                        Toast.makeText(context, "${e.message ?: "Kanye voice unavailable"} — using phone voice", Toast.LENGTH_SHORT).show()
                        speakPhone(parts.drop(i).joinToString(" "))
                        return@supervisorScope
                    }
                    next = if (i + 1 < parts.size) async(Dispatchers.IO) { kanye.synth(parts[i + 1]) } else null
                    _state.value = MicState.Speaking
                    try { play(file) } finally { file.delete() }
                }
                spokeDone()
            }
        }
    }

    private suspend fun play(file: File) = suspendCancellableCoroutine { cont ->
        val mp = MediaPlayer()
        fun done() {
            runCatching { mp.release() }
            if (cont.isActive) cont.resume(Unit)
        }
        try {
            mp.setAudioAttributes(speechAttrs)
            mp.setDataSource(file.path)
            mp.setOnCompletionListener { done() }
            mp.setOnErrorListener { _, _, _ -> done(); true }
            mp.prepare()
            mp.start()
        } catch (e: Exception) {
            done()
        }
        cont.invokeOnCancellation { runCatching { mp.stop() }; runCatching { mp.release() } }
    }

    fun stopSpeaking() {
        kanyeJob?.cancel(); kanyeJob = null
        if (tts?.isSpeaking == true) tts?.stop()
        if (_state.value == MicState.Speaking || _state.value == MicState.Preparing) _state.value = MicState.Idle
        audio?.abandonAudioFocusRequest(focus)
    }

    fun release() {
        onSpeakDone = null
        dictating = false
        cancelPause()
        stopSpeaking()
        scope.cancel()
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
    }

    companion object {
        private const val MAX_RETRIES = 2
        private const val MAX_MID_FAILURES = 6
        /** A pause setting this short means "send as soon as the recognizer stops" (the old behavior). */
        const val SEND_AT_ONCE_MS = 900L
        private const val MAX_DICTATION_MS = 3 * 60_000L

        fun errorName(code: Int): String = when (code) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "network timeout"
            SpeechRecognizer.ERROR_NETWORK -> "network"
            SpeechRecognizer.ERROR_AUDIO -> "audio"
            SpeechRecognizer.ERROR_SERVER -> "server"
            SpeechRecognizer.ERROR_CLIENT -> "client"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "speech timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "no match"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "recognizer busy"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no mic permission"
            10 -> "too many requests"
            11 -> "speech service disconnected"
            12 -> "language not supported"
            13 -> "language unavailable"
            else -> "error $code"
        }

        /** What TTS should say: drop code blocks and markdown noise. */
        fun spokenText(text: String): String {
            val hadApp = text.contains("```html", ignoreCase = true)
            var t = text.replace(Regex("```[\\s\\S]*?(```|$)"), " ")
            t = t.replace(Regex("`([^`]*)`"), "$1")
                .replace(Regex("!?\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")
                .replace(Regex("[*_#>|]+"), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (t.isBlank() && hadApp) t = "Your app is ready."
            return t
        }
    }
}
