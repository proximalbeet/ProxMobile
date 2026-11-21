package com.proxai.hermes.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.PhoneApp
import com.proxai.hermes.data.PhoneApps
import com.proxai.hermes.data.ResearchSend
import com.proxai.hermes.data.ResearchSends
import com.proxai.hermes.notify.ResearchSendService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * P3 (0.9.5): Chat > Attach > "An app on this phone". Lists other people's apps (Nathan: REA is for other
 * apps, widgets and firmware); picking one starts sending its APK files to the PC. Since 0.9.6 the send runs
 * in the background (ResearchSendService), so this closes at once; the tray shows its progress and the app
 * joins the message when it's on the PC.
 */
@Composable
fun AppPickerDialog(onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val settings = ProxApp.instance.settings
    var apps by remember { mutableStateOf<List<PhoneApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { PhoneApps.list(ctx) } }

    fun pick(app: PhoneApp) {
        if (!settings.isConfigured) {
            error = "Connect ProxAI to your PC first (Settings)"
            return
        }
        val why = ResearchSendService.startApp(ctx, app)
        if (why == null) onDismiss() else error = why
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("An app on this phone") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Pick one and its APK files go to your PC, where ProxAI can look inside it: what it's built " +
                    "with and how it works. It keeps sending if you leave ProxAI. Nothing is installed or run.",
                    style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(query, { query = it }, singleLine = true, placeholder = { Text("Search apps") },
                    modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val q = query.trim().lowercase(Locale.ROOT)
                    val shown = if (q.isEmpty()) list else list.filter {
                        it.label.lowercase(Locale.ROOT).contains(q) || it.packageName.lowercase(Locale.ROOT).contains(q)
                    }
                    if (shown.isEmpty()) Text("No apps match", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LazyColumn(Modifier.heightIn(max = 380.dp)) {
                        items(shown, key = { it.packageName }) { app -> PhoneAppRow(app) { pick(app) } }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun PhoneAppRow(app: PhoneApp, onPick: () -> Unit) {
    val ctx = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onPick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val i = icon
        if (i != null) Image(i, contentDescription = null, modifier = Modifier.size(36.dp))
        else Icon(Icons.Outlined.Android, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            val version = app.versionName.ifBlank { "code ${app.versionCode}" }
            val splits = if (app.parts.size > 1) " · ${app.parts.size} files" else ""
            val widgets = when (app.widgets.size) { 0 -> ""; 1 -> " · 1 widget"; else -> " · ${app.widgets.size} widgets" }
            Text("$version · ${PhoneApps.mb(app.bytes)}$splits$widgets", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

/**
 * 0.9.6: in the chat's tray while an app or file goes to the PC ("Sending Clock… 45%", ✕ stops it), or
 * after it failed (the reason, ✕ clears it). Nothing once it's on the PC: then the attachment itself is there.
 */
@Composable
fun ResearchSendChip(s: ResearchSend) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
        Row(Modifier.padding(start = 10.dp, top = 6.dp, bottom = 6.dp, end = 2.dp).width(220.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (s.error == null) {
                    Text(when {
                        s.total <= 0 -> "Sending ${s.title} to the PC…"
                        s.done >= s.total -> "${s.title}: finishing on the PC…"
                        else -> "Sending ${s.title} to the PC · ${s.percent}%"
                    }, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                    if (s.total > 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Couldn't send ${s.title}", style = MaterialTheme.typography.labelMedium, maxLines = 1,
                        color = MaterialTheme.colorScheme.error)
                    Text(s.error, style = MaterialTheme.typography.labelSmall, maxLines = 3,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            IconButton(onClick = { if (s.error == null) ResearchSends.cancel() else ResearchSends.dismiss() },
                modifier = Modifier.size(32.dp)) {
                Icon(Icons.Outlined.Close, if (s.error == null) "Stop sending" else "Clear", modifier = Modifier.size(18.dp))
            }
        }
    }
}
