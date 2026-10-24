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
        Release("0.6.4", "2026-10-24", listOf(
            "Gig board for apps you build: a ProxAI app with the \"scout\" feature can show the overnight scout's " +
                "gigs and app ideas, move them (interested, pitched, won, done, passed), keep notes and hours, and " +
                "block time on your ProxAI calendar",
        )),
        Release("0.6.3", "2026-10-23", listOf(
            "Scheduled jobs: \"every morning at 8 summarize my calendar\", \"delete the old app at 2am\". ProxAI asks " +
                "first; your yes also approves the action. Say \"what's scheduled\" or \"cancel the … job\"",
            "Notices from the PC as notifications: scheduled-job results, \"your app is ready\", wiki updates " +
                "(Settings → Notifications)",
        )),
        Release("0.6.2", "2026-10-23", listOf(
            "Apps built on your PC: ProxAI asks before it builds, changes or rolls back an app; only your yes " +
                "starts it",
            "A \"Stop\" button on an app that's building (also \"stop the build\" in chat)",
        )),
        Release("0.6.1", "2026-10-23", listOf(
            "Much faster replies from the PC's local model: the app's instructions no longer change every " +
                "minute (the clock moved into your message), so the PC reuses its cache instead of re-reading " +
                "the whole chat before every answer (was 40-100 s)",
        )),
        Release("0.6.0", "2026-10-23", listOf(
            "Voice replies come in short parts: each part is its own bubble and is spoken as soon as it " +
                "arrives, while ProxAI writes the next one",
            "Token counter shows the real conversation size from the PC (\"33k context\"), not every step of " +
                "a turn added up (it showed 126k / 376k when each call was ~32k)",
            "Reminders: no more alarms for times already past or repeats of one already set; ProxAI can set " +
                "one on its own per reply, only for something you mentioned",
            "\"How do I…\" questions get a short explanation instead of ProxAI going and doing it",
        )),
        Release("0.5.2", "2026-10-23", listOf(
            "Apps tab: \"Remove…\" on apps built on your PC: uninstall from the phone, and/or delete from " +
                "the PC (ProxAI asks you to confirm; the files go to a trash folder on the PC)",
        )),
        Release("0.5.1", "2026-10-22", listOf(
            "Apps tab: the \"Built on your PC\" and \"On this phone\" headings were dark on dark; now readable",
        )),
        Release("0.5.0", "2026-10-22", listOf(
            "PC temperature alerts: the PC watches GPU/CPU/RAM every second (and unloads the local " +
                "model if the GPU gets critical); the phone shows its alerts (checked about every 15 min " +
                "while ProxAI is closed, right away when you open it). Settings → Notifications",
            "Apps ProxAI builds can show your PC's live stats (new \"pc-stats\" feature), e.g. \"make me " +
                "a PC monitor app\". Only apps signed with your ProxAI apps key can read them",
        )),
        Release("0.4.1", "2026-10-22", listOf(
            "Auto-compact measures correctly; the limits didn't change (cloud 60k, PC model 85% ≈ 56k)",
            "Server mode: the PC compacts by itself, and the app now shows its \"Compacting the " +
                "conversation on the PC…\" note. The app no longer guesses there (the local model only " +
                "reports newly read tokens)",
            "Cloud: replies that used tools no longer trigger it early (their token count covered every " +
                "step of the reply); the header shows the estimated size (~)",
        )),
        Release("0.4.0", "2026-10-22", listOf(
            "Apps tab → \"Built on your PC\": ask ProxAI for an app and Claude Code builds a real app " +
                "(its own icon and APK) on the PC; progress shows here while it builds",
            "Install / Update / Open buttons; Android asks before every install. The first install " +
                "remembers the ProxAI apps signing key and refuses APKs signed with any other",
            "Versions: every build is kept; \"Roll back\" asks ProxAI to rebuild an older version as an update",
            "\"Change with ProxAI\" sends a change request for that app",
            "Needs the PC side: Hermes proxai_app tool + apps server (docs/PROXAI-APPS.md)",
        )),
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
