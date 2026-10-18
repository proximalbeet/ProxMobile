package com.proxai.hermes.ui

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.Prompts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

/** Runs one generated HTML app full-screen in a WebView, with the window.ProxAI bridge. */
class AppRunnerActivity : ComponentActivity() {
    private var web: WebView? = null
    private var tts: TextToSpeech? = null
    private val bridgeScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val id = intent.getStringExtra(EXTRA_ID)
        val store = ProxApp.instance.apps
        val meta = id?.let { store.get(it) }
        val html = id?.let { store.html(it) }
        if (meta == null || html == null) {
            Toast.makeText(this, "That app no longer exists", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        setTaskDescription(ActivityManager.TaskDescription.Builder().setLabel(meta.title)
            .setPrimaryColor(appColor(meta.title).toInt()).build())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web?.canGoBack() == true) web?.goBack() else finish()
            }
        })

        setContent {
            ProxTheme {
                var menu by remember { mutableStateOf(false) }
                Column(Modifier.fillMaxSize()) {
                    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Close") }
                            Text(meta.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f))
                            IconButton(onClick = { web?.let { load(it, meta.id) } }) { Icon(Icons.Outlined.Refresh, "Reload") }
                            IconButton(onClick = ::remix) { Icon(Icons.Outlined.AutoFixHigh, "Remix with Hermes") }
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                                DropdownMenu(menu, { menu = false }) {
                                    DropdownMenuItem(text = { Text("Add to home screen") }, onClick = {
                                        menu = false; pinToHome(this@AppRunnerActivity, meta)
                                    })
                                    DropdownMenuItem(text = { Text("Clear app data") }, onClick = {
                                        menu = false
                                        web?.evaluateJavascript("try{localStorage.clear();sessionStorage.clear()}catch(e){}", null)
                                        web?.let { load(it, meta.id) }
                                    })
                                }
                            }
                        }
                    }
                    AndroidView(
                        modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
                        factory = { ctx -> createWebView(ctx).also { web = it; load(it, meta.id) } },
                    )
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(ctx: Context) = WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.parseColor("#0E0F13"))
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        webChromeClient = WebChromeClient()
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                // Links leaving the app open in the real browser.
                val url = request.url
                if (url.host?.endsWith(".apps.proxai.local") == true) return false
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }
        addJavascriptInterface(Bridge(), "__proxaiNative")
    }

    /** Each app gets its own fake https origin, so localStorage stays separate per app. */
    private fun load(view: WebView, id: String) {
        val html = ProxApp.instance.apps.html(id) ?: return
        view.loadDataWithBaseURL("https://$id.apps.proxai.local/", injectShim(html), "text/html", "utf-8", null)
    }

    private fun injectShim(html: String): String {
        val head = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(html)
        return if (head != null) html.substring(0, head.range.last + 1) + SHIM + html.substring(head.range.last + 1)
        else SHIM + html
    }

    private fun remix() {
        val id = intent.getStringExtra(EXTRA_ID) ?: return
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_REMIX, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
    }

    override fun onDestroy() {
        bridgeScope.cancel()
        tts?.shutdown()
        web?.destroy()
        super.onDestroy()
    }

    /** Native side of window.ProxAI. Calls arrive on a WebView background thread. */
    inner class Bridge {
        @JavascriptInterface
        fun ask(callId: String, prompt: String) {
            val appId = intent.getStringExtra(EXTRA_ID) ?: return
            bridgeScope.launch {
                val result = runCatching {
                    ProxApp.instance.hermes.ask(prompt, sessionId = "android-app-$appId", system = Prompts.APP_BRIDGE)
                }
                val payload = JSONObject()
                result.onSuccess { payload.put("ok", true).put("text", it) }
                    .onFailure { payload.put("ok", false).put("error", it.message ?: "Hermes error") }
                web?.evaluateJavascript("window.__proxaiResolve(${JSONObject.quote(callId)}, $payload)", null)
            }
        }

        @JavascriptInterface
        fun speak(text: String) {
            runOnUiThread {
                val engine = tts
                if (engine == null) {
                    tts = TextToSpeech(applicationContext) { if (it == TextToSpeech.SUCCESS) {
                        tts?.language = Locale.getDefault(); tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "app") } }
                } else engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "app")
            }
        }

        @JavascriptInterface
        fun toast(text: String) = runOnUiThread { Toast.makeText(this@AppRunnerActivity, text.take(200), Toast.LENGTH_SHORT).show() }

        @JavascriptInterface
        fun vibrate(ms: Int) {
            val v = getSystemService(Vibrator::class.java) ?: return
            v.vibrate(VibrationEffect.createOneShot(ms.coerceIn(1, 2000).toLong(), VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    companion object {
        const val EXTRA_ID = "app_id"

        fun intent(ctx: Context, id: String) = Intent(ctx, AppRunnerActivity::class.java)
            .putExtra(EXTRA_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        private const val SHIM = """<script>(function(){
  if (window.ProxAI) return;
  var n = window.__proxaiNative, pending = {}, seq = 0;
  window.__proxaiResolve = function(id, r){ var p = pending[id]; if(!p) return; delete pending[id];
    r.ok ? p.resolve(r.text) : p.reject(new Error(r.error)); };
  window.ProxAI = {
    ask: function(prompt){ return new Promise(function(resolve, reject){
      var id = 'c' + (++seq); pending[id] = {resolve: resolve, reject: reject}; n.ask(id, String(prompt)); }); },
    speak: function(t){ n.speak(String(t)); },
    toast: function(t){ n.toast(String(t)); },
    vibrate: function(ms){ n.vibrate(ms || 30); }
  };
})();</script>"""
    }
}
