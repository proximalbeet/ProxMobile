package com.proxai.hermes.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.proxai.hermes.ProxApp

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(0)
    private var remixAppId by mutableStateOf<String?>(null)
    private var draft by mutableStateOf("")
    private var pendingConnect by mutableStateOf<Pair<String, String>?>(null)
    private var settingsVersion by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!ProxApp.instance.settings.isConfigured) tab = 2
        handle(intent)

        setContent {
            ProxTheme {
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Outlined.ChatBubbleOutline, null) }, label = { Text("Chat") })
                            NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Outlined.Widgets, null) }, label = { Text("Apps") })
                            NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Outlined.Settings, null) }, label = { Text("Settings") })
                        }
                    },
                ) { pad ->
                    Box(Modifier.fillMaxSize().padding(pad)) {
                        when (tab) {
                            0 -> ChatScreen(
                                input = draft, onInput = { draft = it },
                                remixAppId = remixAppId, onClearRemix = { remixAppId = null },
                                onOpenApp = ::openApp, onRemixApp = ::remix)
                            1 -> AppsScreen(onOpenApp = ::openApp, onRemixApp = ::remix,
                                onBuildNew = { draft = it; tab = 0 })
                            else -> key(settingsVersion) { SettingsScreen() }
                        }
                    }
                }
                pendingConnect?.let { (url, key) ->
                    // Always confirm: a link from anywhere could otherwise repoint the app.
                    AlertDialog(
                        onDismissRequest = { pendingConnect = null },
                        title = { Text("Connect to Hermes?") },
                        text = { Text("Use this server for ProxAI?\n\n$url") },
                        confirmButton = {
                            TextButton(onClick = {
                                val s = ProxApp.instance.settings
                                s.serverUrl = url
                                s.apiKey = key
                                pendingConnect = null
                                tab = 2
                                settingsVersion++ // SettingsScreen reads settings once; rebuild it
                            }) { Text("Connect") }
                        },
                        dismissButton = { TextButton(onClick = { pendingConnect = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val uri = intent?.data
        if (intent?.action == Intent.ACTION_VIEW && uri?.scheme == "proxai" && uri.host == "connect") {
            val url = uri.getQueryParameter("url").orEmpty()
            val key = uri.getQueryParameter("key").orEmpty()
            if (url.startsWith("http") && key.isNotBlank()) pendingConnect = url to key
            intent.data = null // don't re-prompt on rotation
        }
        intent?.getStringExtra(EXTRA_REMIX)?.let { remix(it) }
        if (intent?.getBooleanExtra(EXTRA_CHAT, false) == true) tab = 0
    }

    private fun openApp(id: String) = startActivity(AppRunnerActivity.intent(this, id))

    private fun remix(id: String) {
        remixAppId = id
        tab = 0
    }

    companion object {
        const val EXTRA_REMIX = "remix_app_id"
        const val EXTRA_CHAT = "open_chat"
    }
}
