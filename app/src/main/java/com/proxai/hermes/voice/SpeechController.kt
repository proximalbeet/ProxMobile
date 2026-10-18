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

    private fun createRecognizer(): SpeechRecognizer? {
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) {
            return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        }
        return null
    }

    fun listen() {
        stopSpeaking()
        _error.value = null
        _partial.value = ""
        val rec = recognizer ?: createRecognizer()?.also { recognizer = it }
        if (rec == null) {
            _error.value = "No speech recognizer on this phone. Install/enable the Google app."
            return
        }
        rec.setRecognitionListener(listener)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        _state.value = MicState.Listening
        rec.startListening(intent)
    }

    fun stopListening() {
        recognizer?.stopListening()
    }

    fun cancelListening() {
        recognizer?.cancel()
        if (_state.value == MicState.Listening) _state.value = MicState.Idle
        _level.value = 0f
    }

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
        stopSpeaking()
        scope.cancel()
        recognizer?.destroy(); recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { _state.value = MicState.Listening }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) { _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() { _level.value = 0f }
        override fun onError(error: Int) {
            _level.value = 0f
            if (_state.value == MicState.Listening) _state.value = MicState.Idle
            _error.value = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that — tap the mic to try again."
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is needed."
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs a network connection."
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy — try again."
                SpeechRecognizer.ERROR_CLIENT -> null
                else -> "Speech error ($error)"
            }
        }
        override fun onResults(results: Bundle?) {
            _level.value = 0f
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            _partial.value = text
            _state.value = MicState.Idle
            if (text.isNotBlank()) onFinal(text)
        }
        override fun onPartialResults(partialResults: Bundle?) {
            partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                ?.let { if (it.isNotBlank()) _partial.value = it }
        }
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
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
