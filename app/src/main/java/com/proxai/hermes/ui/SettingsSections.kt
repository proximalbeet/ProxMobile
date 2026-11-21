package com.proxai.hermes.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.ReminderStore
import com.proxai.hermes.notify.Notifier
import com.proxai.hermes.voice.SpeechLog
import kotlinx.coroutines.launch

@Composable
private fun Heading(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A setting with a switch: [label], an optional second line, and the switch on the right. */
@Composable
private fun SwitchRow(label: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Choices(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (label, value) ->
            FilterChip(selected = value == selected, onClick = { onPick(value) }, label = { Text(label) })
        }
    }
}

/** Fixes from the 2026-10-22 drive: messages sent mid-sentence, misheard words. */
@Composable
fun SpeechInputSection() {
    val s = ProxApp.instance.settings
    var pause by remember { mutableStateOf(s.sendPauseMs) }
    var vocab by remember { mutableStateOf(s.vocabulary) }
    var fixes by remember { mutableStateOf(s.wordFixes) }
    var showLog by remember { mutableStateOf(false) }

    Heading("Speech input")
    Text("Send a voice message after this much quiet")
    Choices(listOf("Right away" to 800L, "1.5 s" to 1500L, "3 s" to 3000L, "5 s" to 5000L, "Only when I tap Send" to 0L), pause) {
        pause = it; s.sendPauseMs = it
    }
    Hint("Short pauses while you think no longer send the message: the app keeps listening and joins " +
        "what you say. Tap the orb or Send to send early.")
    OutlinedTextField(vocab, { vocab = it; s.vocabulary = it }, Modifier.fillMaxWidth(),
        label = { Text("Words to expect (one per line)") }, minLines = 3, maxLines = 6)
    Hint(if (Build.VERSION.SDK_INT >= 33) "Passed to the speech recognizer as hints."
        else "Needs Android 13+ for recognizer hints; the fixes below still apply.")
    OutlinedTextField(fixes, { fixes = it; s.wordFixes = it }, Modifier.fillMaxWidth(),
        label = { Text("Fixes: heard => meant (one per line)") }, minLines = 3, maxLines = 8)
    Hint("Applied to what the phone heard before it's sent, e.g. \"crime job => cron job\". ProxAI is also " +
        "told voice text can be misheard and to go by context.")
    TextButton(onClick = { showLog = !showLog }) { Text(if (showLog) "Hide speech log" else "Show speech log") }
    if (showLog) {
        val lines = SpeechLog.all()
        Text(if (lines.isEmpty()) "No speech errors since the app started." else lines.joinToString("\n"),
            fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Server mode on the PC -> the phone uses the local model. */
@Composable
fun ModelSection() {
    val app = ProxApp.instance
    val s = app.settings
    val scope = rememberCoroutineScope()
    var choice by remember { mutableStateOf(s.modelChoice) }
    var cloudModel by remember { mutableStateOf(s.model) }
    var localModel by remember { mutableStateOf(s.localModel) }
    var provider by remember { mutableStateOf(s.localProvider) }
    val mode by app.pcMode.state.collectAsState()
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.pcMode.current() }

    Heading("Model")
    // X1-PM (11-16): no "Auto" any more (it sent chat to the local model in server mode); Cloud is the default.
    Choices(listOf("Cloud" to "cloud", "Local" to "local"), choice) { choice = it; s.modelChoice = it }
    Hint(when (choice) {
        "local" -> "Always the local model (needs the PC on; in personal mode it loads on demand and frees the GPU after a few minutes)."
        else -> "The cloud model, whatever mode the PC is in."
    })
    // B4: only the settings that apply to the chosen mode.
    val showCloud = choice != "local"
    val showLocal = choice == "local"
    if (showCloud) {
        Text("Cloud model")
        Choices(com.proxai.hermes.data.ModelRoute.CLOUD_CHOICES, cloudModel) { cloudModel = it; s.model = it }
        Hint("Sonnet is the default. Haiku is cheaper and faster; Opus and Fable cost more per message, Fable the most. " +
            "The chat header shows which model answered.")
    }
    if (showLocal) Row(verticalAlignment = Alignment.CenterVertically) {
        val m = mode
        Text(
            if (m == null) "PC mode: not checked yet"
            else "PC mode: ${m.mode.uppercase()}" + (if (m.detail.isNotBlank()) " (${m.detail})" else ""),
            Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !checking, onClick = {
            checking = true
            scope.launch { app.pcMode.refresh(); checking = false }
        }) { Text(if (checking) "Checking…" else "Check now") }
    }
    Text("Next message: ${app.chat.currentRoute().label}", style = MaterialTheme.typography.bodySmall)
    if (showLocal) {
        Text("Local model")
        Choices(com.proxai.hermes.data.ModelRoute.LOCAL_CHOICES, localModel) { localModel = it; s.localModel = it }
        OutlinedTextField(provider, { provider = it; s.localProvider = it }, Modifier.fillMaxWidth(),
            label = { Text("Local provider (custom provider name on the PC)") }, singleLine = true)
    }
}

/** S1 (Phase 3): the app lock. Locks after ProxMobile has been out of sight this long (1 min default, Nathan 10-30). */
private val LOCK_TIMES = listOf("30 seconds" to 30_000L, "1 minute" to 60_000L, "5 minutes" to 300_000L, "15 minutes" to 900_000L)

@Composable
fun AppLockSection() {
    val app = ProxApp.instance
    val s = app.settings
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(s.appLock) }
    var after by remember { mutableStateOf(s.lockAfterMs) }
    var choosePin by remember { mutableStateOf(false) }
    Heading("App lock")
    SwitchRow("Lock ProxAI", "Fingerprint or face, with an app PIN as backup. While locked, nothing reaches the AI or your data",
        on) {
        if (it) choosePin = true
        else { s.appLock = false; on = false; app.lock.check() }
    }
    if (on) {
        Text("Lock again after ProxAI has been out of sight for", style = MaterialTheme.typography.labelLarge)
        Choices(LOCK_TIMES, after) { after = it; s.lockAfterMs = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choosePin = true }) { Text("Change PIN") }
            OutlinedButton(onClick = { app.lock.lockNow() }) { Text("Lock now") }
        }
        Hint("While locked, the power-button assistant, replies typed into notifications and your PC-built apps wait for " +
            "an unlock. A reply typed while locked is sent right after you unlock. Apps you're using keep working until " +
            "you leave them for the same time.")
        if (!Biometrics.available(ctx)) Hint("No fingerprint or face is set up on this phone, so the lock asks for the PIN.")
    }
    if (choosePin) SetPinDialog(onDismiss = { choosePin = false }) {
        choosePin = false
        s.appLock = true
        on = true
        app.lock.turnedOn()
    }
}

@Composable
fun NotificationsSection() {
    val app = ProxApp.instance
    val s = app.settings
    val ctx = LocalContext.current
    var allowed by remember { mutableStateOf(Notifier.canPost(ctx)) }
    var replies by remember { mutableStateOf(s.replyNotifications) }
    val reminders by app.reminders.items.collectAsState()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { allowed = Notifier.canPost(ctx) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed = Notifier.canPost(ctx) }

    Heading("Notifications and reminders")
    if (!allowed) {
        Hint("Notifications are off for ProxAI, so reminders can't show.")
        OutlinedButton(onClick = {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                ctx.startActivity(Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(AndroidSettings.EXTRA_APP_PACKAGE, ctx.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }) { Text("Allow notifications") }
    }
    // B5 (10-30): one switch per kind. Off = no buzz, but it still lands in Chat.
    Text("Buzz my phone for", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
    var buzzReminders by remember { mutableStateOf(s.buzzReminders) }
    SwitchRow("Reminders", null, buzzReminders) { buzzReminders = it; s.buzzReminders = it }
    var buzzCalendar by remember { mutableStateOf(s.buzzCalendar) }
    SwitchRow("Calendar heads-ups", "A few minutes before an event", buzzCalendar) { buzzCalendar = it; s.buzzCalendar = it }
    var buzzBrief by remember { mutableStateOf(s.buzzBrief) }
    SwitchRow("Brief and nudges", "Morning brief, check-ins, end of day", buzzBrief) { buzzBrief = it; s.buzzBrief = it }
    var buzzUpdates by remember { mutableStateOf(s.buzzUpdates) }
    SwitchRow("App builds and PC updates", "Apps ready, scheduled jobs, Study Coach, wiki, scout, pipeline",
        buzzUpdates) { buzzUpdates = it; s.buzzUpdates = it }
    SwitchRow("ProxAI's replies", "When it finishes answering while the app is closed", replies) {
        replies = it; s.replyNotifications = it
    }
    var pcAlerts by remember { mutableStateOf(s.pcAlerts) }
    SwitchRow("PC temperature alerts", "GPU, CPU, memory. The Monitor and Vitals apps show these too", pcAlerts) {
        pcAlerts = it; s.pcAlerts = it
        com.proxai.hermes.data.PcAlertWorker.schedule(ctx)
    }
    Hint("Switched off = no buzz. Reminders, the brief and PC notices still land in Chat.")
    var pcNotices by remember { mutableStateOf(s.pcNotices) }
    SwitchRow("Check the PC for notices", "Off: no brief, nudges or app notices at all, not even in Chat", pcNotices) {
        pcNotices = it; s.pcNotices = it
        com.proxai.hermes.data.PcAlertWorker.schedule(ctx)
        com.proxai.hermes.notify.PcListener.sync(ctx)
    }
    var pcLive by remember { mutableStateOf(s.pcLive) }
    SwitchRow("Live PC notices", "Within seconds, over one quiet connection (it keeps a silent \"Connected to your " +
        "PC\" notification). Off: about every 15 minutes", pcLive) {
        pcLive = it; s.pcLive = it
        com.proxai.hermes.notify.PcListener.sync(ctx)
    }
    Hint("Scheduled jobs: say \"every morning at 8 summarize my calendar\" or \"delete the old app at 2am\". " +
        "ProxAI asks first; your yes also approves the action. Results arrive here as notices.")
    Hint("The PC watches itself every second and unloads the local model if the GPU gets critical. " +
        "The phone checks for its alerts about every 15 minutes (Android's limit while ProxAI is closed) " +
        "and right away when you open ProxAI.")
    Hint("Reminders: say \"remind me at 5 to call Mom\" or \"in 20 minutes, check the oven\". ProxAI sets them " +
        "on the phone, and they work offline once set.")
    if (reminders.isEmpty()) Text("No reminders set.", style = MaterialTheme.typography.bodyMedium)
    reminders.forEach { r ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.text, style = MaterialTheme.typography.bodyMedium)
                Text(ReminderStore.format(r.at), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { app.reminders.remove(r.id) }) { Icon(Icons.Outlined.Delete, "Delete reminder") }
        }
    }
}

@Composable
private fun TrustedSitesBlock() {
    val trusted = ProxApp.instance.trusted
    val sites by trusted.sites.collectAsState()
    var add by remember { mutableStateOf("") }
    Text("Trusted sites")
    Hint("Links to these sites (and their subdomains) are labelled ✅ Human-verified. 🤖 AI-checked means ProxAI " +
        "confirmed it in a second source; ❔ Unknown is neither. Tap a source under a reply to trust its site.")
    sites.sorted().forEach { d ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✅ $d", Modifier.weight(1f))
            TextButton(onClick = { trusted.set(d, false) }) { Text("Remove") }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = add, onValueChange = { add = it.take(80) }, singleLine = true, modifier = Modifier.weight(1f),
            label = { Text("Add a site, e.g. nih.gov") })
        TextButton(enabled = com.proxai.hermes.data.WebSources.cleanDomain(add) != null,
            onClick = { trusted.set(add, true); add = "" }) { Text("Add") }
    }
}

@Composable
fun ContextSection() {
    val app = ProxApp.instance
    val s = app.settings
    var limit by remember { mutableStateOf(s.compactAtTokens) }
    Heading("Context and cost")
    Text("Auto-compact when one reply reads more than")
    Choices(listOf("40k" to 40_000, "60k" to 60_000, "100k" to 100_000, "Off" to 0), limit) { limit = it; s.compactAtTokens = it }
    Hint("ProxAI resends the whole conversation every turn, so long chats get expensive. Compacting asks ProxAI " +
        "for a short summary and continues in a fresh session that starts from it. A fresh session already " +
        "reads about 20k tokens (ProxAI's instructions and tools). The chat header shows the size and a cost " +
        "estimate; \"New chat\" starts a completely fresh session.")
    OutlinedButton(onClick = { app.chat.compact() }) { Text("Compact now") }
    // C9: sites whose links are labelled Human-verified.
    TrustedSitesBlock()
    // C7: "New chat" and deleted channels go to the trash (Chats button in the chat header).
    var days by remember { mutableStateOf(s.trashDays) }
    Text("Empty the trash after")
    Choices(listOf("7 days" to 7, "30 days" to 30, "90 days" to 90, "Never" to 0), days) { days = it; s.trashDays = it }
    Hint("\"New chat\" and deleting a channel move the conversation to the trash instead of wiping it. Restore it " +
        "from the # button in the chat header; ProxAI remembers it again.")
}
