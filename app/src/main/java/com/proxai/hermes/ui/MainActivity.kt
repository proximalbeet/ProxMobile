package com.proxai.hermes.ui

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.WorkbenchClient
import com.proxai.hermes.data.WorkbenchLogin
import com.proxai.hermes.data.WorkbenchLogins
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var tab by mutableIntStateOf(0)
    private var remixAppId by mutableStateOf<String?>(null)
    private var draft by mutableStateOf("")
    private var pendingConnect by mutableStateOf<Pair<String, String>?>(null)
    private var pendingWorkbenchKey by mutableStateOf<String?>(null)
    private val workbench by lazy { WorkbenchLogins(ProxApp.instance.settings) }
    private var settingsVersion by mutableIntStateOf(0)
    private var showWhatsNew by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!ProxApp.instance.settings.isConfigured) tab = 2
        handle(intent)
        askForNotificationsOnce()
        com.proxai.hermes.notify.PcListener.sync(this)   // PC notices (0.9.9) and Workbench logins pop up right away
        // Once after each update: the list of changes, so it can be checked on the phone.
        if (savedInstanceState == null && ProxApp.instance.settings.isConfigured && Changelog.shouldShow(this)) showWhatsNew = true

        setContent {
            ProxTheme {
                // S1: locked = the lock screen only (no chat, no settings, no connect link).
                val locked by ProxApp.instance.lock.locked.collectAsState()
                if (locked) LockScreen() else Content()
            }
        }
    }

    @Composable
    private fun Content() {
        // proxDocs answers stay off screenshots, the recent-apps preview and "Look at my screen".
        LaunchedEffect(tab) {
            if (tab == 3) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        Scaffold(
            modifier = Modifier.background(ScreenGlow),
            containerColor = androidx.compose.ui.graphics.Color.Transparent,
            bottomBar = {
                NavigationBar(containerColor = androidx.compose.ui.graphics.Color(0xF20D121B)) {
                    NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Outlined.ChatBubbleOutline, null) }, label = { Text("Chat") })
                    NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Outlined.Widgets, null) }, label = { Text("Apps") })
                    // P4 (0.9.7): proxDocs, ProxAI's own docs behind their own password. Index 3 so Settings keeps 2.
                    NavigationBarItem(tab == 3, { tab = 3 }, { Icon(Icons.AutoMirrored.Outlined.MenuBook, null) }, label = { Text("Docs") })
                    NavigationBarItem(tab == 2, { tab = 2 }, { Icon(Icons.Outlined.Settings, null) }, label = { Text("Settings") })
                }
            },
        ) { pad ->
            // The tab bar and status bar are already padded here: consume them so the chat's
            // imePadding adds only the keyboard above the tab bar, and its TopAppBar doesn't
            // add the status bar again (10-29: with both counted twice, ~140 dp went missing
            // and a long prompt's input box got cut off when the keyboard opened).
            Box(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad)) {
                when (tab) {
                    0 -> ChatScreen(
                        input = draft, onInput = { draft = it },
                        remixAppId = remixAppId, onClearRemix = { remixAppId = null },
                        onOpenApp = ::openApp, onRemixApp = ::remix)
                    1 -> AppsScreen(onOpenApp = ::openApp, onRemixApp = ::remix,
                        onBuildNew = { draft = it; tab = 0 })
                    3 -> DocsScreen()
                    else -> key(settingsVersion) { SettingsScreen() }
                }
            }
        }
        if (showWhatsNew) WhatsNewDialog { showWhatsNew = false }
        // F1 (0.9.9): a question the PC is waiting on. Only here, behind the app lock (Content isn't shown locked).
        val waiting by ProxApp.instance.chat.approval.collectAsState()
        waiting?.let { p ->
            ApprovalDialog(p.approval, onAnswer = { ok -> ProxApp.instance.chat.answerApproval(ok); tab = 0 },
                onClose = { ProxApp.instance.chat.closeApproval() })
        }
        pendingConnect?.let { (url, key) ->
            // Always confirm: a link from anywhere could otherwise repoint the app.
            AlertDialog(
                onDismissRequest = { pendingConnect = null },
                title = { Text("Connect to ProxAI?") },
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
        pendingWorkbenchKey?.let { key ->
            // Like Connect: always ask, so a link from anywhere can't quietly swap the key.
            AlertDialog(
                onDismissRequest = { pendingWorkbenchKey = null },
                title = { Text("Answer Workbench logins?") },
                text = { Text("Let ProxAI approve logins to the Workbench on your PC? Only scan this code from your own " +
                    "PC (python3 workbench.py phone-key).") },
                confirmButton = {
                    TextButton(onClick = {
                        ProxApp.instance.settings.workbenchKey = key
                        com.proxai.hermes.notify.PcListener.sync(this@MainActivity)
                        pendingWorkbenchKey = null
                        settingsVersion++ // SettingsScreen reads settings once; rebuild it
                        Toast.makeText(this@MainActivity, "Paired: Workbench logins show up here", Toast.LENGTH_SHORT).show()
                    }) { Text("Allow") }
                },
                dismissButton = { TextButton(onClick = { pendingWorkbenchKey = null }) { Text("Cancel") } },
            )
        }
        // Workbench logins: checked every few seconds while this screen is up. Never while locked: Content isn't
        // shown then. The first waiting one gets the prompt.
        LaunchedEffect(Unit) { this@MainActivity.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { workbench.watch() } }
        val logins by workbench.waiting.collectAsState()
        logins.firstOrNull()?.let { WorkbenchPrompt(it) }
    }

    /** A browser is asking to log in to the Workbench: tap the number it shows, or Not me. */
    @Composable
    private fun WorkbenchPrompt(login: WorkbenchLogin) {
        AlertDialog(
            onDismissRequest = {
                workbench.putAway(login)
                com.proxai.hermes.notify.Notifier.cancelWorkbenchLogin(this@MainActivity, login.id)
            },
            title = { Text("Workbench login") },
            text = {
                Column {
                    Text("${login.label} is asking to log in to the Workbench. If that's you, tap the number it shows.")
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        login.choices.forEach { n ->
                            FilledTonalButton(onClick = { answer(login, n) }) {
                                Text("$n", style = MaterialTheme.typography.titleLarge)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { answer(login, null) }) { Text("Not me") } },
        )
    }

    private fun answer(login: WorkbenchLogin, number: Int?) {
        com.proxai.hermes.notify.Notifier.cancelWorkbenchLogin(this, login.id)
        lifecycleScope.launch {
            val said = workbench.answer(login, number).fold(
                onSuccess = { state ->
                    when {
                        state == "approved" -> "Approved: ${login.label} is logging in"
                        number == null -> "Refused: that browser stays logged out"
                        else -> "That wasn't its number, so the login was refused"
                    }
                },
                onFailure = { "Couldn't reach the Workbench: ${it.message}" },
            )
            Toast.makeText(this@MainActivity, said, Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        ProxApp.instance.chat.checkApproval()   // F1: a question left waiting shows again when ProxAI comes back
        // S1: with the lock on, the app switcher shows no snapshot of the chat.
        if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!ProxApp.instance.settings.appLock)
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
        if (intent?.action == Intent.ACTION_VIEW && uri?.scheme == "proxai" && uri.host == "workbench") {
            // 0.9.3: pairing for Workbench logins (the QR from `workbench.py phone-key`). Only the key; never a URL.
            val key = uri.getQueryParameter("key").orEmpty()
            if (WorkbenchClient.validKey(key)) pendingWorkbenchKey = key
            intent.data = null
        }
        intent?.getStringExtra(EXTRA_REMIX)?.let { remix(it) }
        if (intent?.getBooleanExtra(EXTRA_CHAT, false) == true) tab = 0
        // C2: a tapped notice lands in the chat (once; the key stops a rotation adding it again).
        com.proxai.hermes.notify.Notifier.addNoticeFrom(intent)
    }

    private val notificationPermission =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { }

    /** v0.3 reminders need notifications; ask once (Settings has a button after that). */
    private fun askForNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33) return
        val prefs = getSharedPreferences("proxai", MODE_PRIVATE)
        if (prefs.getBoolean("asked_notifications", false)) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
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
