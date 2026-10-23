package com.proxai.shell

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Locale

/** The one screen of every ProxAI-generated app: ProxAI title bar + the app's own web files. */
class ShellActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var info: ShellLogic.AppInfo
    private var tts: TextToSpeech? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        info = runCatching { ShellLogic.parseInfo(assets.open("proxai-app.json").bufferedReader().readText()) }
            .getOrElse { ShellLogic.parseInfo("{}") }
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(info.name)
            .setPrimaryColor(ShellLogic.BRAND_DEEP).build())
        Notifications.ensureChannel(this)

        val pad = dp(12)
        val title = TextView(this).apply {
            text = info.name
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val reload = TextView(this).apply {
            text = "⟳"
            contentDescription = "Reload"
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            setPadding(pad, 0, pad / 2, 0)
            setOnClickListener { web.reload() }
        }
        val badge = TextView(this).apply {
            text = "ProxAI"
            setTextColor(0xCCFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(0, 0, pad / 2, 0)
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(ShellLogic.BRAND_DEEP, ShellLogic.BRAND))
            setPadding(pad + dp(4), pad, pad, pad)
            addView(title); addView(badge); addView(reload)
        }
        web = createWebView()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ShellLogic.BACKGROUND)
            addView(bar)
            addView(web, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Edge to edge (Android 15 default): pad for the status and navigation bars ourselves.
        root.setOnApplyWindowInsetsListener { v, insets ->
            val sys = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            bar.setPadding(pad + dp(4), pad + sys.top, pad, pad)
            v.setPadding(sys.left, 0, sys.right, sys.bottom)
            insets
        }
        setContentView(root)
        if (savedInstanceState != null) web.restoreState(savedInstanceState) else web.loadUrl(ShellLogic.ORIGIN + "index.html")
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
        webChromeClient = WebChromeClient()
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

    override fun onDestroy() {
        tts?.shutdown()
        web.destroy()
        super.onDestroy()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun has(feature: String) = feature in info.features

    private fun notificationsAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return true
        runOnUiThread { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1) }
        return false
    }

    /** Native side of window.ProxAI. Calls arrive on a WebView background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun appInfo(): String = JSONObject().put("id", info.id).put("name", info.name)
            .put("version", info.version).put("features", org.json.JSONArray(info.features.sorted())).toString()

        @JavascriptInterface
        fun toast(text: String) = runOnUiThread { Toast.makeText(this@ShellActivity, text.take(200), Toast.LENGTH_SHORT).show() }

        @JavascriptInterface
        fun vibrate(ms: Int): Boolean {
            if (!has("vibrate")) return false
            val v = getSystemService(Vibrator::class.java) ?: return false
            v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 2000).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
            return true
        }

        @JavascriptInterface
        fun speak(text: String): Boolean {
            if (!has("speech")) return false
            runOnUiThread {
                val engine = tts
                if (engine == null) {
                    tts = TextToSpeech(applicationContext) {
                        if (it == TextToSpeech.SUCCESS) {
                            tts?.language = Locale.getDefault()
                            tts?.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "app")
                        }
                    }
                } else engine.speak(text.take(3500), TextToSpeech.QUEUE_FLUSH, null, "app")
            }
            return true
        }

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

        /** Asking ProxAI from a generated app goes through ProxMobile (it holds the key). Coming
         *  in a later step; until then the promise rejects with a clear message. */
        @JavascriptInterface
        fun ask(callId: String, prompt: String) {
            val msg = if (!has("ask")) "This app wasn't built with the 'ask' feature."
            else "ProxAI.ask from installed apps isn't connected yet (needs a ProxMobile update)."
            val payload = JSONObject().put("ok", false).put("error", msg)
            runOnUiThread { web.evaluateJavascript("window.__proxaiResolve(${JSONObject.quote(callId)}, $payload)", null) }
        }
    }
}
