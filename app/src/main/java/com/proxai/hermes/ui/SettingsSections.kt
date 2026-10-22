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
    Hint("Applied to what the phone heard before it's sent, e.g. \"crime job => cron job\". Hermes is also " +
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
    var localModel by remember { mutableStateOf(s.localModel) }
    var provider by remember { mutableStateOf(s.localProvider) }
    val mode by app.pcMode.state.collectAsState()
    var checking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { app.pcMode.current() }

    Heading("Model")
    Choices(listOf("Auto" to "auto", "Cloud" to "cloud", "Local" to "local"), choice) { choice = it; s.modelChoice = it }
    Hint(when (choice) {
        "auto" -> "PC in SERVER mode: the phone uses the local model below. PERSONAL mode or unknown: the cloud model (the proxai default, or the alias above)."
        "local" -> "Always the local model (needs the PC on; in personal mode it loads on demand and frees the GPU after a few minutes)."
        else -> "Always the cloud model."
    })
    Row(verticalAlignment = Alignment.CenterVertically) {
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
    OutlinedTextField(localModel, { localModel = it; s.localModel = it }, Modifier.fillMaxWidth(),
        label = { Text("Local model") }, singleLine = true)
    OutlinedTextField(provider, { provider = it; s.localProvider = it }, Modifier.fillMaxWidth(),
        label = { Text("Local provider (Hermes custom provider name)") }, singleLine = true)
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
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Notify me when Hermes replies while the app is closed", Modifier.weight(1f))
        Switch(replies, { replies = it; s.replyNotifications = it })
    }
    Hint("Reminders: say \"remind me at 5 to call Mom\" or \"in 20 minutes, check the oven\". Hermes sets them " +
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
fun ContextSection() {
    val app = ProxApp.instance
    val s = app.settings
    var limit by remember { mutableStateOf(s.compactAtTokens) }
    Heading("Context and cost")
    Text("Auto-compact when one reply reads more than")
    Choices(listOf("40k" to 40_000, "60k" to 60_000, "100k" to 100_000, "Off" to 0), limit) { limit = it; s.compactAtTokens = it }
    Hint("Hermes resends the whole conversation every turn, so long chats get expensive. Compacting asks Hermes " +
        "for a short summary and continues in a fresh session that starts from it. A fresh session already " +
        "reads about 20k tokens (Hermes's instructions and tools). The chat header shows the size and a cost " +
        "estimate; \"New chat\" starts a completely fresh session.")
    OutlinedButton(onClick = { app.chat.compact() }) { Text("Compact now") }
}
