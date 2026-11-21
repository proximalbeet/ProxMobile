package com.proxai.hermes.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.Settings
import com.proxai.hermes.data.WorkbenchClient
import com.proxai.hermes.notify.CallMonitor
import com.proxai.hermes.notify.PcListener
import com.proxai.hermes.voice.MicState
import com.proxai.hermes.voice.SpeechController
import kotlinx.coroutines.launch

/**
 * F2 (0.9.9, Nathan 11-02: "cleaner and more organized settings"): a short list of pages, each with a line
 * saying how it's set now, instead of one long page. Same settings and buttons as before, grouped:
 * Connection · Chat and models · Voice and assistant · Notifications · Security · PC links.
 */
private enum class Page(val id: String, val title: String) {
    CONNECTION("connection", "Connection"),
    CHAT("chat", "Chat and models"),
    VOICE("voice", "Voice and assistant"),
    NOTIFICATIONS("notifications", "Notifications"),
    SECURITY("security", "Security"),
    LINKS("links", "PC links");

    companion object {
        fun of(id: String) = entries.firstOrNull { it.id == id }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val s = ProxApp.instance.settings
    // Not set up yet: straight to the server address and key.
    var pageId by rememberSaveable { mutableStateOf(if (s.isConfigured) "" else Page.CONNECTION.id) }
    val page = Page.of(pageId)
    BackHandler(enabled = page != null) { pageId = "" }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(
                containerColor = androidx.compose.ui.graphics.Color.Transparent),
            title = { Text(page?.title ?: "Settings", fontWeight = FontWeight.SemiBold) },
            navigationIcon = {
                if (page != null) IconButton(onClick = { pageId = "" }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Settings")
                }
            })
        key(pageId) {   // each page starts at its top
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (page) {
                    null -> SettingsHome { pageId = it.id }
                    Page.CONNECTION -> ConnectionPage()
                    Page.CHAT -> { ModelSection(); ContextSection() }
                    Page.VOICE -> VoicePage()
                    Page.NOTIFICATIONS -> NotificationsSection()
                    Page.SECURITY -> { AppLockSection(); LineSection() }
                    Page.LINKS -> PcLinksPage()
                }
            }
        }
    }
}

// ---- the list --------------------------------------------------------------------------------------

@Composable
private fun SettingsHome(open: (Page) -> Unit) {
    val s = ProxApp.instance.settings
    AboutCard()
    PageRow(Icons.Outlined.Dns, Page.CONNECTION.title, SettingsSummary.connection(s)) { open(Page.CONNECTION) }
    PageRow(Icons.Outlined.SmartToy, Page.CHAT.title, SettingsSummary.chat(s)) { open(Page.CHAT) }
    PageRow(Icons.Outlined.Mic, Page.VOICE.title, SettingsSummary.voice(s)) { open(Page.VOICE) }
    PageRow(Icons.Outlined.Notifications, Page.NOTIFICATIONS.title, SettingsSummary.notifications(s)) {
        open(Page.NOTIFICATIONS)
    }
    PageRow(Icons.Outlined.Lock, Page.SECURITY.title, SettingsSummary.security(s)) { open(Page.SECURITY) }
    PageRow(Icons.Outlined.Link, Page.LINKS.title, SettingsSummary.links(s)) { open(Page.LINKS) }
}

@Composable
private fun PageRow(icon: ImageVector, title: String, summary: String, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The one line under each page in the list: how it's set now. Pure, tested in SettingsSummaryTest. */
object SettingsSummary {
    fun connection(s: Settings): String =
        if (!s.isConfigured) "Not set up yet: add the server address and key"
        else "Connected to " + s.serverUrl.trim().removePrefix("https://").removePrefix("http://").trimEnd('/')
            .substringBefore('/')

    fun chat(s: Settings): String = when (s.modelChoice) {
        "local" -> "Local model on the PC"
        else -> "Cloud: ${com.proxai.hermes.data.ModelRoute.CLOUD_NAMES[s.model] ?: s.model}"
    } + " · compacts at ${s.compactAtTokens / 1000}k"

    fun voice(s: Settings): String = listOf(
        if (s.proxVoice) "Prox's voice" else "Phone voice",
        if (s.speakReplies) "replies read aloud" else "replies not read",
        if (s.handsFree) "hands-free" else null,
    ).filterNotNull().joinToString(" · ")

    fun notifications(s: Settings): String = when {
        !s.pcNotices -> "PC notices off"
        s.pcLive -> "Live PC notices (within seconds)"
        else -> "PC notices about every 15 minutes"
    }

    fun security(s: Settings): String =
        (if (!s.appLock) "App lock off" else "App lock on · relocks after ${lockTime(s.lockAfterMs)}") +
            (if (s.lineNoPin) " · no PIN on calls while unlocked" else "")

    fun links(s: Settings): String = listOf(
        if (s.dumpsyncToken.isNotBlank()) "Prox Dump sync set" else "Prox Dump sync not set",
        if (s.ownerToken.isNotBlank()) "Owner token set" else "Owner token not set",
        if (s.ownerCertPin.isNotBlank()) "owner key pinned" else "owner key not pinned",
        if (WorkbenchClient.validKey(s.workbenchKey)) "Workbench paired" else "Workbench not paired",
    ).joinToString(" · ")

    private fun lockTime(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000} s"
        ms % 60_000 == 0L -> "${ms / 60_000} min"
        else -> "${ms / 1000} s"
    }
}

// ---- pages -----------------------------------------------------------------------------------------

@Composable
private fun ConnectionPage() {
    val s = ProxApp.instance.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.serverUrl) }
    var key by remember { mutableStateOf(s.apiKey) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    Section("ProxAI server")
    OutlinedTextField(url, { url = it; status = null }, Modifier.fillMaxWidth(),
        label = { Text("Server URL") }, singleLine = true,
        placeholder = { Text("https://your-desktop.your-tailnet.ts.net") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(key, { key = it; status = null }, Modifier.fillMaxWidth(),
        label = { Text("API key (proxai API_SERVER_KEY)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Button(enabled = !testing, onClick = {
            s.serverUrl = url; s.apiKey = key; PcListener.sync(ctx); testing = true
            scope.launch {
                val r = ProxApp.instance.hermes.testConnection()
                status = r.fold({ true to it }, { false to (it.message ?: "Failed") })
                testing = false
            }
        }) { Text(if (testing) "Testing…" else "Save & test") }
        OutlinedButton(onClick = { ProxApp.instance.chat.newChat() }) { Text("New session") }
    }
    status?.let { (ok, msg) ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline, null,
                tint = if (ok) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.error)
            Spacer(Modifier.padding(4.dp))
            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
    Hint("Pair a new phone from the PC: scripts/phone-qr.sh shows a code to scan.")
    Spacer(Modifier.height(8.dp))
    Text("Session: ${s.sessionId}", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun VoicePage() {
    val s = ProxApp.instance.settings
    val ctx = LocalContext.current
    var speak by remember { mutableStateOf(s.speakReplies) }
    var prox by remember { mutableStateOf(s.proxVoice) }
    val voiceTester = remember { SpeechController(ctx) {} }
    DisposableEffect(Unit) { onDispose { voiceTester.release() } }
    val testerState by voiceTester.state.collectAsState()
    var autoListen by remember { mutableStateOf(s.autoListen) }
    var handsFree by remember { mutableStateOf(s.handsFree) }

    // Refresh these whenever he comes back from Android's settings.
    var isAssistant by remember { mutableStateOf(false) }
    var hasMic by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            isAssistant = ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            hasMic = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        }
    }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasMic = it }

    Section("Voice")
    Toggle("Read replies aloud", speak) { speak = it; s.speakReplies = it }
    Toggle("Prox's voice (made on your PC)", prox) { prox = it; s.proxVoice = it }
    Hint(if (prox) "Needs the PC on; falls back to the phone voice when the PC can't be reached."
        else "Uses the phone's built-in voice.")
    OutlinedButton(
        enabled = testerState == MicState.Idle,
        onClick = { voiceTester.speak("Hi, it's Prox. This is how I sound on your phone now. Let's build something.") },
    ) {
        Text(when (testerState) {
            MicState.Preparing -> "Loading voice…"
            MicState.Speaking -> "Speaking…"
            else -> "Test voice"
        })
    }

    Section("Assistant")
    Toggle("Start listening when the assistant opens", autoListen) { autoListen = it; s.autoListen = it }
    Toggle("Hands-free: keep listening after each reply", handsFree) { handsFree = it; s.handsFree = it }

    SpeechInputSection()

    WakeWordSection()

    Section("Power button")
    StepCard(
        done = hasMic, title = "1. Allow the microphone",
        body = "Needed for talking to ProxAI.",
        action = "Allow" to { micPermission.launch(Manifest.permission.RECORD_AUDIO) })
    StepCard(
        done = isAssistant, title = "2. Make ProxAI your digital assistant",
        body = "Pick ProxAI under “Digital assistant app”.",
        action = "Open settings" to {
            val intents = listOf(
                Intent(AndroidSettings.ACTION_VOICE_INPUT_SETTINGS),
                Intent(AndroidSettings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS),
                Intent(AndroidSettings.ACTION_SETTINGS))
            intents.firstOrNull { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess }
        })
    StepCard(
        done = false, title = "3. Set the power button to the assistant",
        body = "Pixel: Settings → System → Gestures → Press & hold power button → Digital assistant.\n" +
            "Samsung: Settings → Advanced features → Side button → Press and hold — choose the digital assistant if " +
            "your One UI version offers it.\n" +
            "No option on your phone? Add the “Ask ProxAI” Quick Settings tile instead.",
        action = null)
}

@Composable
private fun PcLinksPage() {
    val s = ProxApp.instance.settings
    val ctx = LocalContext.current
    var dumpUrl by remember { mutableStateOf(s.dumpsyncUrl) }
    var dumpToken by remember { mutableStateOf(s.dumpsyncToken) }
    var wbKey by remember { mutableStateOf(s.workbenchKey) }
    var wbListen by remember { mutableStateOf(s.workbenchListen) }

    Section("Prox Dump sync")
    Hint("For PC-built apps with the \"dumpsync\" feature (brain dumps and projects). " +
        "The token is DUMPSYNC_TOKEN from the PC; apps never see it or the URL.")
    OutlinedTextField(dumpUrl, { dumpUrl = it; s.dumpsyncUrl = it }, Modifier.fillMaxWidth(),
        label = { Text("Prox Dump sync URL (optional)") }, singleLine = true,
        placeholder = { Text((s.serverUrl.trim().trimEnd('/').removeSuffix("/v1")
            .ifBlank { "https://your-desktop.your-tailnet.ts.net" }) + "/dumpsync") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    OutlinedTextField(dumpToken, { dumpToken = it; s.dumpsyncToken = it }, Modifier.fillMaxWidth(),
        label = { Text("Prox Dump sync token (DUMPSYNC_TOKEN)") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation())

    // HUB-APP-ONLY (0.9.15): the field starts empty every time; the saved token is never put back on screen.
    Section("Owner apps")
    Hint("Your own presses (Prox Hub's answers and Accepts, the Pipeline's changes) are taken only from the Prox Hub " +
        "and BoardRoom apps, with this second token from the PC. Other apps never get it: they can only read the " +
        "Pipeline's summaries. Once saved, the token isn't shown again; save a new one to replace it.")
    var ownerEntry by remember { mutableStateOf("") }
    var ownerSet by remember { mutableStateOf(s.ownerToken.isNotBlank()) }
    OutlinedTextField(ownerEntry, { ownerEntry = it }, Modifier.fillMaxWidth(),
        label = { Text(if (ownerSet) "Owner token (set)" else "Owner token") }, singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // round 4: a password field to the keyboard too, so it doesn't learn or suggest the token
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = ownerEntry.isNotBlank(), onClick = {
            s.ownerToken = ownerEntry; ownerEntry = ""; ownerSet = s.ownerToken.isNotBlank()
        }) { Text("Save Owner token") }
        if (ownerSet) OutlinedButton(onClick = { s.ownerToken = ""; ownerSet = false }) { Text("Forget Owner token") }
    }

    // OWNER-SIGN (0.9.16): the owner key's fingerprint, typed by Nathan from the PC. Shown (it isn't a secret);
    // only ProxMobile's own screen sets it, never an app.
    Hint("The token goes only to Prox Hub and BoardRoom when Android says they're signed with your owner key: paste " +
        "its SHA-256 fingerprint from the PC here, before you install them. No fingerprint, no token, and the Apps " +
        "tab won't install them.")
    var pinned by remember { mutableStateOf(s.ownerCertPin) }
    var pinEntry by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf<String?>(null) }
    var signed by remember { mutableStateOf(com.proxai.hermes.data.OwnerSign.status(ctx)) }
    if (pinned.isNotBlank()) Text("Owner key: " + pinned.chunked(2).joinToString(":").uppercase(),
        style = MaterialTheme.typography.bodySmall, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
    else Text("Owner key: not pinned", style = MaterialTheme.typography.bodySmall)
    for ((pkg, ok) in signed) Text(
        (if (pkg == com.proxai.hermes.data.DumpsyncClient.HUB_APP) "Prox Hub" else "BoardRoom") +
            (if (ok) ": signed with your owner key" else ": NOT signed with your owner key (gets what other apps get)"),
        style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(pinEntry, { pinEntry = it; pinError = null }, Modifier.fillMaxWidth(),
        label = { Text("Owner key fingerprint (SHA-256)") }, singleLine = true, isError = pinError != null,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false))
    pinError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = pinEntry.isNotBlank(), onClick = {
            pinError = com.proxai.hermes.data.OwnerSign.pinRefusal(pinEntry, com.proxai.hermes.data.OwnerSign.appsKey(ctx))
            if (pinError == null) {
                s.ownerCertPin = pinEntry; pinEntry = ""; pinned = s.ownerCertPin
                signed = com.proxai.hermes.data.OwnerSign.status(ctx)
            }
        }) { Text("Pin owner key") }
        if (pinned.isNotBlank()) OutlinedButton(onClick = {
            s.ownerCertPin = ""; pinned = ""; signed = com.proxai.hermes.data.OwnerSign.status(ctx)
        }) { Text("Forget owner key") }
    }

    VaultChangesSection()

    Section("Workbench logins")
    Hint("When a browser asks to log in to the Workbench (the AI-engineering IDE on your PC), ProxAI pops up " +
        "with three numbers: tap the one that browser shows. Pair once by scanning the code from " +
        "python3 workbench.py phone-key on the PC. Apps never see this key.")
    OutlinedTextField(wbKey, {
            val wasPaired = WorkbenchClient.validKey(s.workbenchKey)
            wbKey = it
            s.workbenchKey = it
            if (WorkbenchClient.validKey(it.trim()) != wasPaired) PcListener.sync(ctx)
        }, Modifier.fillMaxWidth(),
        label = { Text(if (WorkbenchClient.validKey(wbKey.trim())) "Workbench phone key (paired)"
            else "Workbench phone key (PHONE_KEY)") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    Toggle("Pop up right away", wbListen) { wbListen = it; s.workbenchListen = it; PcListener.sync(ctx) }
    Hint("Uses the same quiet connection to your PC as live PC notices (a small \"Connected to your PC\" " +
        "notification you can hide). Off: logins show only while ProxAI is open.")
    val power = ctx.getSystemService(android.os.PowerManager::class.java)
    if ((wbListen || s.pcLive) && power?.isIgnoringBatteryOptimizations(ctx.packageName) == false) {
        OutlinedButton(onClick = {
            ctx.startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                android.net.Uri.parse("package:${ctx.packageName}")))
        }) { Text("Let it run in the background (for instant pop-ups)") }
    }
}

/**
 * ProxAI line (0.9.9, Phase 14c): no spoken PIN on a ProxAI call while ProxMobile is unlocked, and ProxMobile
 * stays unlocked through any call that began unlocked. The token comes from `setup_phone.py --mobile-token`.
 */
@Composable
private fun LineSection() {
    val s = ProxApp.instance.settings
    val ctx = LocalContext.current
    var token by remember { mutableStateOf(s.lineToken) }
    var url by remember { mutableStateOf(s.lineUrl) }
    var noPin by remember { mutableStateOf(s.lineNoPin && CallMonitor.canWatch(ctx)) }
    var denied by remember { mutableStateOf(false) }
    val phonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        noPin = ok
        s.lineNoPin = ok
        CallMonitor.sync(ctx)
    }

    Section("ProxAI line")
    Hint("Calling the ProxAI number while ProxMobile is unlocked: no PIN to say, and ProxMobile stays unlocked " +
        "until the call ends, then relocks on its usual timer. A call that starts while it's locked changes nothing. " +
        "Get the token on the PC with setup_phone.py --mobile-token.")
    OutlinedTextField(token, { token = it; s.lineToken = it; CallMonitor.sync(ctx) }, Modifier.fillMaxWidth(),
        label = { Text("Line token") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
    OutlinedTextField(url, { url = it; s.lineUrl = it }, Modifier.fillMaxWidth(),
        label = { Text("URL (optional)") }, singleLine = true,
        placeholder = { Text((s.serverUrl.trim().trimEnd('/').ifBlank { "https://your-desktop.your-tailnet.ts.net" }) + "/phone/call") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
    Toggle("No PIN while unlocked", noPin) { on ->
        when {
            !on -> { noPin = false; s.lineNoPin = false; CallMonitor.sync(ctx) }
            android.os.Build.VERSION.SDK_INT < 31 -> denied = true
            CallMonitor.canWatch(ctx) -> { noPin = true; s.lineNoPin = true; CallMonitor.sync(ctx) }
            else -> phonePermission.launch(Manifest.permission.READ_PHONE_STATE)
        }
    }
    Hint("Switching it on asks for Android's Phone permission (Android calls it \"make and manage phone calls\"). " +
        "ProxAI only uses it to see when a call starts and ends: it never makes, answers or listens to calls.")
    if (denied) Hint("No Phone permission, so calls still ask for the PIN (it also needs Android 12 or newer).")
    if (noPin && token.isBlank()) Hint("Add the line token above, or calls still ask for the PIN.")
}

// ---- small pieces ----------------------------------------------------------------------------------

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(value, onChange)
    }
}

@Composable
private fun StepCard(done: Boolean, title: String, body: String, action: Pair<String, () -> Unit>?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (done) Icon(Icons.Outlined.CheckCircle, "Done", tint = MaterialTheme.colorScheme.secondary)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null && !done) OutlinedButton(onClick = action.second) { Text(action.first) }
        }
    }
}
