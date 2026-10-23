package com.proxai.hermes.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** What changed in each version, shown in the app so updates can be checked without GitHub. */
object Changelog {
    data class Release(val version: String, val date: String, val items: List<String>)

    val releases = listOf(
        Release("0.3.2", "2026-10-22", listOf(
            "Kanye voice no longer gives up after one error: a failed sentence is retried once, " +
                "and if it still fails only that sentence uses the phone voice",
            "Shorter voice pieces (180 characters, was 300) so the PC's graphics card doesn't run " +
                "out of memory while the local model is loaded (the \"CUDA error\")",
            "PC side (after restarting the voice service): out of GPU memory → clears memory and " +
                "retries the piece in two halves",
        )),
        Release("0.3.1", "2026-10-22", listOf(
            "New look: glowing blue gradients instead of purple",
            "Named ProxAI everywhere (was Hermes), including the \"Ask ProxAI\" tile",
            "New ProxAI logo: a \"P\" with a spark (app icon and in the app)",
            "Shows \"thinking…\" while the local model thinks, instead of a frozen blank line",
            "Shows \"still working…\" if nothing arrives for 20 seconds",
            "Hands-free: no error when you answer after a reply (waits for the mic, retries if busy)",
            "Hands-free: saying nothing after a reply ends quietly, no error",
            "This What's new list (Settings → What's new)",
        )),
        Release("0.3.0", "2026-10-22", listOf(
            "Voice no longer sends half a sentence: keeps listening through pauses, sends after 3 s of quiet",
            "Send button while you talk; pause length in Settings → Speech input",
            "Speech-to-text resets itself after errors (no more phone restarts)",
            "Fewer misheard words: word list + \"heard => meant\" fixes",
            "Speech log in Settings → Speech input",
            "Talking while ProxAI is busy queues your message instead of losing it",
            "Server mode on the PC → the phone uses the local qwen3.5:35b-a3b model",
            "Settings → Model: Auto / Cloud / Local",
            "Reminders: \"remind me in 20 minutes to…\" → phone notification (Snooze / Done)",
            "Notification when a reply finishes while the app is closed",
            "Each reply shows the model and tokens; header shows context size and cost",
            "Auto-compact long chats (summary + fresh session) and a Compact button",
        )),
    )

    val latest get() = releases.first()

    private const val PREF = "whats_new_seen"

    /** True once per new version (then remembered). */
    fun shouldShow(context: Context): Boolean {
        val prefs = context.getSharedPreferences("proxai", Context.MODE_PRIVATE)
        if (prefs.getString(PREF, null) == latest.version) return false
        prefs.edit().putString(PREF, latest.version).apply()
        return true
    }
}

@Composable
fun ReleaseNotes(releases: List<Changelog.Release>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        releases.forEach { r ->
            Text("Version ${r.version} · ${r.date}", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
            r.items.forEach { item ->
                Row {
                    Text("✓", color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                    Text(item, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** Top of Settings: installed version + the What's new list. */
@Composable
fun AboutCard() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val installed = androidx.compose.runtime.remember {
        runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?"
    }
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            ProxLogo(40.dp)
            androidx.compose.foundation.layout.Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                BrandTitle("ProxAI")
                Text("Version $installed", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { open = true }) { Text("What's new") }
        }
    }
    if (open) WhatsNewDialog { open = false }
}

@Composable
fun WhatsNewDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What's new in ProxAI ${Changelog.latest.version}") },
        text = {
            Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()).padding(end = 4.dp)) {
                ReleaseNotes(Changelog.releases)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Got it") } },
    )
}
