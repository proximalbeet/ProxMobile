package com.proxai.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.GradientDrawable
import android.content.ContentValues
import android.net.Uri
import android.provider.MediaStore
import android.util.Base64
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The one screen of every ProxAI-generated app: the app's own web files, full screen (no title
 * bar since 0.7.0), with a small floating ⋯ button for Reload.
 */
class ShellActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var info: ShellLogic.AppInfo
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val ttsDone = ConcurrentHashMap<String, CountDownLatch>()
    private var recognizer: SpeechRecognizer? = null
    private var listenCall: String? = null
    private var pendingListen: Pair<String, String>? = null
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info = runCatching { ShellLogic.parseInfo(assets.open("proxai-app.json").bufferedReader().readText()) }
            .getOrElse { ShellLogic.parseInfo("{}") }
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(info.name)
            .setPrimaryColor(ShellLogic.BRAND_DEEP).build())
        Notifications.ensureChannel(this)
        if (has("hub")) notificationsAllowed()   // Prox Hub's notices need the permission before the first one comes

        web = createWebView()
        val size = dp(ShellLogic.MENU_DP)
        val menu = TextView(this).apply {
            text = "⋯"
            contentDescription = "App menu"
            gravity = Gravity.CENTER
            setTextColor(0xE6FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0x8C161A24.toInt())
                setStroke(dp(1), 0x334DA8FF)
            }
            setOnClickListener { showMenu(it) }
        }
        val menuParams = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END)
        val root = FrameLayout(this).apply {
            setBackgroundColor(ShellLogic.BACKGROUND)
            addView(web, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(menu, menuParams)
        }
        // Edge to edge (Android 15 default): keep the app clear of the status and navigation bars.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(sys.left, sys.top, sys.right, sys.bottom)
            menuParams.setMargins(0, dp(6), dp(6), 0)
            menu.layoutParams = menuParams
            insets
        }
        setContentView(root)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(ShellLogic.ORIGIN + "index.html")
    }

    private fun showMenu(anchor: android.view.View) {
        PopupMenu(this, anchor).apply {
            menu.add(0, 1, 0, "Reload")
            if (has("voice") || has("speech")) menu.add(0, 2, 1, "Stop talking")
            menu.add(0, 3, 2, "About ${info.name}")
            setOnMenuItemClickListener {
                when (it.itemId) {
                    1 -> web.reload()
                    2 -> stopAllVoice()
                    3 -> Toast.makeText(this@ShellActivity,
                        "${info.name} ${info.version}" + (if (info.description.isNotBlank()) " · ${info.description}" else "") +
                            " · made by ProxAI", Toast.LENGTH_LONG).show()
                }
                true
            }
        }.show()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() = WebView(this).apply {
        setBackgroundColor(ShellLogic.BACKGROUND)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webChromeClient = object : WebChromeClient() {
            /** <input type="file"> ("files" feature): the system file picker; nothing else on the phone is readable. */
            override fun onShowFileChooser(view: WebView, cb: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!has("files")) {
                    toastOnUi("This app wasn't built with the 'files' feature.")
                    cb.onReceiveValue(null); return true
                }
                fileCallback?.onReceiveValue(null)
                fileCallback = cb
                val mimes = ShellLogic.acceptMimes(params.acceptTypes?.toList().orEmpty())
                val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                    .setType(if (mimes.size == 1) mimes[0] else "*/*")
                if (mimes.size > 1) pick.putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                if (params.mode == FileChooserParams.MODE_OPEN_MULTIPLE) pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                return try {
                    @Suppress("DEPRECATION") startActivityForResult(pick, REQ_FILE); true
                } catch (e: Exception) {
                    fileCallback = null; cb.onReceiveValue(null); true
                }
            }
        }
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.url.host != ShellLogic.HOST) return null   // no INTERNET permission unless allowed
                val path = ShellLogic.assetPath(request.url.path) ?: return notFound()
                return runCatching {
                    val bytes = assets.open(path).use { it.readBytes() }
                    val mime = ShellLogic.mimeType(path)
                    val body = if (mime == "text/html") ShellLogic.injectShim(String(bytes, Charsets.UTF_8)).toByteArray() else bytes
                    WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
                }.getOrElse { notFound() }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.url.host == ShellLogic.HOST) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, request.url)) }   // links open in the browser
                return true
            }
        }
        addJavascriptInterface(Bridge(), "__proxaiNative")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_DOCS) {
            val id = pendingDocsUnlock ?: return
            pendingDocsUnlock = null
            reply(id, ok(JSONObject().put("ok", true).put("unlocked", resultCode == RESULT_OK).toString()))
            return
        }
        if (requestCode != REQ_FILE) return
        val cb = fileCallback ?: return
        fileCallback = null
        if (resultCode != RESULT_OK || data == null) { cb.onReceiveValue(null); return }
        val clip = data.clipData
        val uris = if (clip != null) Array(clip.itemCount) { clip.getItemAt(it).uri } else data.data?.let { arrayOf(it) }
        cb.onReceiveValue(uris)
    }

    private fun notFound() = WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(),
        ByteArrayInputStream(ByteArray(0)))

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

    /** proxDocs ("docs", 0.9.8): while this app shows, ProxMobile hears every 20 s that it's in use, so proxDocs
     *  stays open here like it does on ProxAI's own screen (and locks after the lock time once both are gone). */
    private val docsPing = object : Runnable {
        override fun run() {
            Thread { viaProxMobile("docs", DOCS_ALIVE) }.start()
            docsHandler.postDelayed(this, 20_000)
        }
    }
    private val docsHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun onResume() {
        super.onResume()
        if (has("docs")) docsHandler.post(docsPing)
    }

    override fun onPause() {
        docsHandler.removeCallbacks(docsPing)
        super.onPause()
    }

    /** Leaving the app stops its voice and the microphone. */
    override fun onStop() {
        stopListening(resolveWith = "")
        if (has("voice") || has("speech")) stopAllVoice()
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        recognizer?.destroy()
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun has(feature: String) = feature in info.features

    /** The ProxAI.docs.unlock() call waiting for ProxMobile's prompt to close. */
    private var pendingDocsUnlock: String? = null

    private fun toastOnUi(text: String) = runOnUiThread { Toast.makeText(this, text.take(200), Toast.LENGTH_SHORT).show() }

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY) }
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_MIC) return
        val (callId, opts) = pendingListen ?: return
        pendingListen = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening(callId, opts)
        else reply(callId, fail("Microphone permission was denied. Allow it in the app's settings to answer by voice."))
    }

    // ---- replies to the page ------------------------------------------------------------------

    private fun ok(text: String) = JSONObject().put("ok", true).put("text", text)
    private fun fail(msg: String) = JSONObject().put("ok", false).put("error", msg)
    private fun reply(callId: String, payload: JSONObject) =
        runOnUiThread { web.evaluateJavascript("window.__proxaiResolve(${JSONObject.quote(callId)}, $payload)", null) }

    /** One call through ProxMobile's door (it holds the PC key; this app never sees it). Returns (ok, json-or-error). */
    private fun viaProxMobile(method: String, arg: String?): Pair<Boolean, String> = try {
        val b = contentResolver.call(Uri.parse(ShellLogic.PC_STATS_URI), method, arg, null)
        ShellLogic.readAnswer(b != null, b?.getString("json"), b?.getString("error"))
    } catch (e: Exception) {
        ShellLogic.callFailed(e, proxMobileVersion()).let { false to it }
    }

    /** Prox Hub calls go one at a time (their answers can be big; see ShellLogic.CallGate). */
    private val hubGate = ShellLogic.CallGate()

    /** ProxMobile's version, or null when it isn't installed or Android hides it from this app. */
    private fun proxMobileVersion(): String? = try {
        packageManager.getPackageInfo(ShellLogic.PROXMOBILE, 0).versionName ?: "?"
    } catch (e: Exception) {
        null
    }

    private fun proxMobileCall(callId: String, feature: String, method: String, arg: String?, pick: (String) -> String = { it }) {
        if (!has(feature)) { reply(callId, fail("This app wasn't built with the '$feature' feature.")); return }
        Thread {
            val (good, body) = viaProxMobile(method, arg)
            reply(callId, if (good) runCatching { ok(pick(body)) }.getOrElse { fail("Bad answer from ProxMobile") } else fail(body))
        }.start()
    }

    // ---- phone voice ----------------------------------------------------------------------------

    private fun ensureTts() {
        if (tts != null) return
        tts = TextToSpeech(applicationContext) {
            if (it == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String) {}
                    override fun onDone(id: String) { ttsDone.remove(id)?.countDown() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String) { ttsDone.remove(id)?.countDown() }
                    override fun onStop(id: String, interrupted: Boolean) { ttsDone.remove(id)?.countDown() }
                })
                ttsReady = true
            }
        }
    }

    /** Says [text] in the phone voice; with [wait], blocks (off the UI thread) until it's been said. */
    private fun sayPhone(text: String, wait: Boolean): Boolean {
        runOnUiThread { ensureTts() }
        var waited = 0
        while (!ttsReady && waited < 5000) { Thread.sleep(100); waited += 100 }
        val engine = tts ?: return false
        if (!ttsReady) return false
        val pieces = text.take(6000).chunked(3500)
        var first = true
        for (piece in pieces) {
            val id = "app-${System.nanoTime()}"
            val latch = CountDownLatch(1)
            if (wait) ttsDone[id] = latch
            engine.speak(piece, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
            first = false
            if (wait && !latch.await(3, TimeUnit.MINUTES)) return true
        }
        return true
    }

    private fun stopAllVoice() {
        tts?.stop()
        ttsDone.values.forEach { it.countDown() }; ttsDone.clear()
        if (has("voice")) Thread { viaProxMobile("voice-stop", null) }.start()
    }

    // ---- listening ("listen" feature) -----------------------------------------------------------

    private fun startListening(callId: String, optsJson: String) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingListen?.let { reply(it.first, fail("Replaced by a newer listen() call")) }
            pendingListen = callId to optsJson
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            reply(callId, fail("Speech recognition isn't available on this phone")); return
        }
        stopListening(resolveWith = "")
        val opts = ShellLogic.listenOptions(optsJson)
        val rec = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = rec
        listenCall = callId
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onResults(results: Bundle) {
                val text = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                finishListen(rec, ok(text))
            }
            override fun onError(error: Int) {
                // Nothing heard is a normal answer (""), not an error.
                val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                finishListen(rec, if (quiet) ok("") else fail("Speech recognition failed (${ShellLogic.recognizerError(error)})"))
            }
            override fun onReadyForSpeech(params: Bundle?) { if (has("vibrate")) vibrateNow(20) }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, opts.silenceMs)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, opts.silenceMs)
        if (opts.hints.isNotEmpty() && Build.VERSION.SDK_INT >= 33)
            intent.putExtra(RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(opts.hints))
        rec.startListening(intent)
    }

    private fun finishListen(rec: SpeechRecognizer, payload: JSONObject) {
        if (recognizer !== rec) return
        val id = listenCall
        recognizer = null; listenCall = null
        rec.destroy()
        if (id != null) reply(id, payload)
    }

    /** Ends listening now; a pending listen() resolves with [resolveWith]. */
    private fun stopListening(resolveWith: String) {
        val rec = recognizer ?: return
        finishListen(rec, ok(resolveWith))
    }

    private fun vibrateNow(ms: Int) {
        getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(ms.toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
    }

    /** Native side of window.ProxAI. Calls arrive on a WebView background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun appInfo(): String = JSONObject().put("id", info.id).put("name", info.name)
            .put("version", info.version).put("features", org.json.JSONArray(info.features.sorted())).toString()

        @JavascriptInterface
        fun toast(text: String) = toastOnUi(text)

        @JavascriptInterface
        fun vibrate(ms: Int): Boolean {
            if (!has("vibrate")) return false
            vibrateNow(ms.coerceIn(1, 2000))
            return true
        }

        /** Phone voice, fire and forget ("speech" feature). */
        @JavascriptInterface
        fun speak(text: String): Boolean {
            if (!has("speech")) return false
            Thread { sayPhone(text, wait = false) }.start()
            return true
        }

        /** Prox's voice through ProxMobile ("voice" feature); resolves when it's been said with
         *  "pc", "phone" (PC unreachable or voice off: the phone voice said it) or "stopped". */
        @JavascriptInterface
        fun voice(callId: String, text: String) {
            if (!has("voice")) { reply(callId, fail("This app wasn't built with the 'voice' feature.")); return }
            Thread {
                val (good, body) = viaProxMobile("voice", text.take(6000))
                val spoken = if (good) runCatching { JSONObject(body).optString("spoken", "pc") }.getOrDefault("pc")
                    else if (sayPhone(text, wait = true)) "phone" else null
                reply(callId, if (spoken != null) ok(spoken) else fail(body))
            }.start()
        }

        @JavascriptInterface
        fun stopVoice() = runOnUiThread { stopAllVoice() }

        /** Keep the screen on (a voice quiz, the audio overview) or let it sleep again. */
        @JavascriptInterface
        fun keepAwake(on: Boolean) = runOnUiThread {
            if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        /** Wake Prox's voice on the PC now, e.g. when a voice quiz opens. */
        @JavascriptInterface
        fun warmVoice() {
            if (has("voice")) Thread { viaProxMobile("voice-warmup", null) }.start()
        }

        /** Speech to text ("listen" feature). Resolves with what was said ("" if nothing). */
        @JavascriptInterface
        fun listen(callId: String, optsJson: String) {
            if (!has("listen")) { reply(callId, fail("This app wasn't built with the 'listen' feature.")); return }
            runOnUiThread { startListening(callId, optsJson) }
        }

        /** Stop listening now and resolve with what was heard so far. */
        @JavascriptInterface
        fun stopListening() = runOnUiThread { recognizer?.stopListening() }

        @JavascriptInterface
        fun notify(title: String, text: String): Boolean {
            if (!has("notifications") || !notificationsAllowed()) return false
            Notifications.show(this@ShellActivity, title.ifBlank { info.name }, text)
            return true
        }

        /** A notification at [atMillis] (phone time). Inexact by a few minutes when the phone dozes. */
        @JavascriptInterface
        fun remind(atMillis: Double, text: String): Boolean {
            if (!has("notifications") || !notificationsAllowed()) return false
            val at = atMillis.toLong()
            if (at <= System.currentTimeMillis()) return false
            val pi = PendingIntent.getBroadcast(this@ShellActivity, (at / 1000).toInt(),
                Intent(this@ShellActivity, ReminderReceiver::class.java)
                    .putExtra(ReminderReceiver.EXTRA_TITLE, info.name).putExtra(ReminderReceiver.EXTRA_TEXT, text.take(300)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            return true
        }

        /** The PC's stats through ProxMobile. Resolves with the stats JSON text; the shim parses it. */
        @JavascriptInterface
        fun pcStats(callId: String) = proxMobileCall(callId, "pc-stats", "stats", null)

        /** The scout's gig board through ProxMobile ("scout" feature). Resolves with the board JSON text. */
        @JavascriptInterface
        fun scout(callId: String) = proxMobileCall(callId, "scout", "scout", null)

        /** A gig-board change ({"id", "status"?, "notes"?, "hours"?, "block"?}); the PC applies it within a minute. */
        @JavascriptInterface
        fun scoutUpdate(callId: String, json: String) = proxMobileCall(callId, "scout", "scout-update", json.take(8192))

        /** The Study Coach through ProxMobile ("tutor" feature, 0.7.0+). [json] = {"method", "route", "body"};
         *  resolves with the plugin's JSON text (the shim parses it and rejects {"ok": false}). */
        @JavascriptInterface
        fun tutor(callId: String, json: String) {
            if (json.length > 600_000) { reply(callId, fail("That request is too big")); return }
            proxMobileCall(callId, "tutor", "tutor", json)
        }

        /** Prox Dump sync through ProxMobile ("dumpsync" feature, 0.7.1+). [json] = {"op", "since"?, "id"?, "status"?}.
         *  Always resolves with JSON text (the PC's answer or {"ok": false, "code", "error"}); the shim rejects
         *  errors with Error.code. ProxMobile holds the sync URL and token; this app never sees them. */
        @JavascriptInterface
        fun dumpsync(callId: String, json: String) {
            if (!has("dumpsync")) {
                reply(callId, ok(ShellLogic.dumpsyncError("This app wasn't built with the 'dumpsync' feature."))); return
            }
            if (json.length > 8192) { reply(callId, ok(ShellLogic.dumpsyncError("That request is too big"))); return }
            Thread {
                val (good, body) = viaProxMobile("dumpsync", json)
                reply(callId, ok(if (good) body else ShellLogic.dumpsyncError(body)))
            }.start()
        }

        /** Daily Log through ProxMobile ("daily" feature, 0.7.2+). [json] = {"op": "get"|"add", "date"?, "section"?,
         *  "items"?}. Same contract as [dumpsync]: always resolves with JSON text; the shim rejects errors. */
        @JavascriptInterface
        fun daily(callId: String, json: String) {
            if (!has("daily")) {
                reply(callId, ok(ShellLogic.dailyError("This app wasn't built with the 'daily' feature."))); return
            }
            if (json.length > 32_768) { reply(callId, ok(ShellLogic.dailyError("That request is too big"))); return }
            Thread {
                val (good, body) = viaProxMobile("daily", json)
                reply(callId, ok(if (good) body else ShellLogic.dailyError(body)))
            }.start()
        }

        /** The Pipeline through ProxMobile ("pipeline" feature, 0.7.7+). [json] = {"op", ...}; ProxMobile passes it
         *  to the PC's proxai-pipeline. Same contract as [daily]: always resolves with JSON text. */
        @JavascriptInterface
        fun pipeline(callId: String, json: String) {
            if (!has("pipeline")) {
                reply(callId, ok(ShellLogic.pipelineError("This app wasn't built with the 'pipeline' feature."))); return
            }
            if (json.length > 32_768) { reply(callId, ok(ShellLogic.pipelineError("That request is too big"))); return }
            Thread {
                val (good, body) = viaProxMobile("pipeline", json)
                reply(callId, ok(if (good) body else ShellLogic.pipelineError(body)))
            }.start()
        }

        /** Prox Hub through ProxMobile ("hub" feature, 0.9.10+). [json] = {"op", ...}; ProxMobile passes it to the
         *  PC (POST /dumpsync/hub/<op>). Same contract as [pipeline]: always resolves with JSON text. */
        @JavascriptInterface
        fun hub(callId: String, json: String) {
            if (!has("hub")) {
                reply(callId, ok(ShellLogic.hubError("This app wasn't built with the 'hub' feature."))); return
            }
            if (json.length > 32_768) { reply(callId, ok(ShellLogic.hubError("That request is too big"))); return }
            Thread {
                val (good, body) = hubGate.run { viaProxMobile("hub", json) }
                reply(callId, ok(if (good) body else ShellLogic.hubError(body)))
            }.start()
        }

        /** Save one Prox Hub artifact to the phone's Downloads/Prox Hub folder ("hub" feature, ProxMobile 0.9.12+).
         *  [json] = {"session", "key"}: ProxMobile fetches it from the PC (POST /dumpsync/hub/file), this writes the
         *  bytes with MediaStore (no storage permission needed on Android 10+). Resolves {ok, name, path, size}. */
        @JavascriptInterface
        fun hubSave(callId: String, json: String) {
            if (!has("hub")) {
                reply(callId, ok(ShellLogic.hubError("This app wasn't built with the 'hub' feature."))); return
            }
            if (json.length > 4096) { reply(callId, ok(ShellLogic.hubError("That request is too big"))); return }
            Thread {
                val req = runCatching { JSONObject(json) }.getOrNull()
                val session = req?.optString("session") ?: ""
                val key = req?.optString("key") ?: ""
                val (good, body) = hubGate.run {
                    viaProxMobile("hub", JSONObject().put("op", "file").put("session", session).put("key", key).toString())
                }
                if (!good) { reply(callId, ok(ShellLogic.hubError(body))); return@Thread }
                val r = runCatching { JSONObject(body) }.getOrNull()
                if (r == null || !r.optBoolean("ok")) { reply(callId, ok(body)); return@Thread }
                val name = ShellLogic.safeFileName(r.optString("name"))
                val mime = r.optString("mime").ifBlank { "application/octet-stream" }
                val bytes = runCatching { Base64.decode(r.optString("data"), Base64.DEFAULT) }.getOrNull()
                if (bytes == null) { reply(callId, ok(ShellLogic.hubError("The file didn't decode"))); return@Thread }
                val saved = runCatching {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Prox Hub")
                    }
                    val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("Downloads refused the file")
                    contentResolver.openOutputStream(uri)?.use { it.write(bytes) } ?: throw IllegalStateException("Couldn't write the file")
                    uri
                }
                if (saved.isFailure) {
                    reply(callId, ok(ShellLogic.hubError("Couldn't save: " + (saved.exceptionOrNull()?.message ?: "unknown")))); return@Thread
                }
                runOnUiThread { Toast.makeText(this@ShellActivity, "Saved to Downloads/Prox Hub/$name", Toast.LENGTH_LONG).show() }
                reply(callId, ok(JSONObject().put("ok", true).put("name", name).put("path", "Download/Prox Hub/$name").put("size", bytes.size).toString()))
            }.start()
        }

        /** proxDocs through ProxMobile ("docs" feature, 0.9.8+). [json] = {"op": "status"|"ask"|"load"|"lock", ...}.
         *  Same contract as [pipeline]: always resolves with JSON text. ProxMobile keeps the proxDocs session. */
        @JavascriptInterface
        fun docs(callId: String, json: String) {
            if (!has("docs")) {
                reply(callId, ok(ShellLogic.docsError("This app wasn't built with the 'docs' feature."))); return
            }
            if (json.length > 4096) { reply(callId, ok(ShellLogic.docsError("That request is too big"))); return }
            Thread {
                val (good, body) = viaProxMobile("docs", json)
                reply(callId, ok(if (good) body else ShellLogic.docsError(body)))
            }.start()
        }

        /** Opens ProxAI's own proxDocs prompt (ProxMobile's DocsUnlockActivity): the password is typed there, never
         *  here. Resolves {"ok": true, "unlocked": true|false}. */
        @JavascriptInterface
        fun docsUnlock(callId: String) {
            if (!has("docs")) {
                reply(callId, ok(ShellLogic.docsError("This app wasn't built with the 'docs' feature."))); return
            }
            runOnUiThread {
                if (pendingDocsUnlock != null) {
                    reply(callId, ok(ShellLogic.docsError("proxDocs is already asking for the password")))
                    return@runOnUiThread
                }
                try {
                    pendingDocsUnlock = callId
                    @Suppress("DEPRECATION")
                    startActivityForResult(Intent().setClassName(PROXMOBILE, DOCS_UNLOCK_ACTIVITY), REQ_DOCS)
                } catch (e: Exception) {           // ProxMobile missing, or older than 0.9.8
                    pendingDocsUnlock = null
                    reply(callId, ok(ShellLogic.docsError("ProxMobile isn't installed or is too old (needs 0.9.8)")))
                }
            }
        }

        /** Ask ProxAI through ProxMobile ("ask" feature, ProxMobile 0.7.0+). Resolves with the reply text. */
        @JavascriptInterface
        fun ask(callId: String, prompt: String) = proxMobileCall(callId, "ask", "ask",
            JSONObject().put("prompt", prompt.take(20_000)).toString()) { JSONObject(it).optString("text") }
    }

    companion object {
        private const val REQ_NOTIFY = 1
        private const val REQ_MIC = 2
        private const val REQ_FILE = 3
        private const val REQ_DOCS = 4
        private const val PROXMOBILE = "com.proxai.hermes"
        private const val DOCS_UNLOCK_ACTIVITY = "com.proxai.hermes.ui.DocsUnlockActivity"
        private const val DOCS_ALIVE = """{"op":"alive"}"""
    }
}
