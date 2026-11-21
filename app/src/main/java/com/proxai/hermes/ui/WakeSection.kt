package com.proxai.hermes.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.pm.PackageManager
import android.os.Build
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.proxai.hermes.data.WakeLockScreen
import com.proxai.hermes.data.WakeMute
import com.proxai.hermes.data.WakeSettings
import com.proxai.hermes.notify.CallMonitor
import com.proxai.hermes.voice.WakeEngines
import com.proxai.hermes.voice.WakeWordService

/**
 * WAKE-PHONE: Settings > Voice > Listen for "Hey Prox". One switch, off until he turns it on, kept in a file of
 * its own (WakeSettings) so Settings.kt stays as it is.
 */
@Composable
internal fun WakeWordSection() {
    val ctx = LocalContext.current
    val wake = remember { WakeSettings(ctx) }
    var on by remember { mutableStateOf(wake.enabled) }
    var muteUntil by remember { mutableLongStateOf(wake.muteUntil) }
    var hasMic by remember { mutableStateOf(false) }
    var isAssistant by remember { mutableStateOf(false) }
    var onDevice by remember { mutableStateOf(false) }
    var noticeOk by remember { mutableStateOf(true) }
    var today by remember { mutableStateOf(0) }
    var window by remember { mutableLongStateOf(wake.handsFreeWindowMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            WakeWordService.channels(ctx)   // so "notifications are off" is about him, not a channel not made yet
            noticeOk = WakeWordService.noticeVisible(ctx)
            today = wake.wakesOn(java.time.LocalDate.now().toEpochDay())
            hasMic = WakeWordService.canListen(ctx)
            isAssistant = ctx.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
            onDevice = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)
            muteUntil = wake.muteUntil
        }
    }

    fun setOn(value: Boolean) {
        on = value
        wake.enabled = value
        WakeWordService.sync(ctx)
        CallMonitor.sync(ctx)
    }

    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { got ->
        hasMic = got[Manifest.permission.RECORD_AUDIO] == true || WakeWordService.canListen(ctx)
        if (hasMic) setOn(true)
    }

    Text("“Hey Prox”", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Listen for “Hey Prox”", Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface)
        Switch(on, enabled = WakeEngines.AVAILABLE || on, onCheckedChange = { want ->
            if (!want) setOn(false)
            else if (WakeWordService.canListen(ctx) && notificationsOk(ctx)) setOn(true)
            else ask.launch(buildList {
                add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
            }.toTypedArray())
        })
    }
    WakeHint("Say “Hey Prox” and the same voice overlay as the power button opens. The word is heard on this " +
        "phone only; nothing before it is saved or sent. While it listens Android's green mic dot and a " +
        "notification show. It stops during calls, while your PC says you're at the desk, and on low battery. " +
        "A wake opens the overlay and nothing else.")
    if (!WakeEngines.AVAILABLE) WakeHint("This build has no wake engine yet (README, “Hey Prox”: scripts/wake-engine.sh).")
    if (on && !hasMic) WakeHint("Allow the microphone (Power button, step 1) or it can't listen.")
    if (on && !isAssistant) WakeHint("Make ProxAI your digital assistant (below) so the overlay opens over other " +
        "apps and the lock screen. Until then you get a “tap to talk” notification.")
    if (on && !noticeOk) WakeHint("Notifications for ProxAI (or its “Listening for Hey Prox” channel) are off, so " +
        "it doesn't listen: it never listens without its notification showing.")
    // Round 2: shown whenever the switch is on, so the live test can check it first.
    if (on) WakeHint(if (onDevice) "On-device speech recognizer: available."
        else "On-device speech recognizer: not available. After the word you'd have to type " +
            "(Google's online recognizer is never used after “Hey Prox”).")
    if (on) WakeHint("Wakes today: $today")
    if (on) {
        // Round 3 (shape C): only used while ProxAI's own app lock is off.
        WakeHint("On the lock screen: hands-free this long after you unlock your phone, then “Unlock to talk”. " +
            "With ProxAI's app lock on, its own fingerprint prompt decides instead.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (w in WakeLockScreen.WINDOWS) {
                FilterChip(selected = window == w, onClick = { wake.handsFreeWindowMs = w; window = w },
                    label = { Text(WakeLockScreen.label(w)) })
            }
        }
    }
    if (on && WakeMute.muted(muteUntil, System.currentTimeMillis())) {
        WakeHint(WakeMute.label(muteUntil, System.currentTimeMillis()) + " (the Hey Prox tile).")
        OutlinedButton(onClick = { wake.muteUntil = 0L; muteUntil = 0L; WakeWordService.sync(ctx) }) { Text("Listen again") }
    } else if (on) {
        WakeHint("Mute it from the “Hey Prox” Quick Settings tile: for 1 hour, or until you turn it back on.")
    }
}

private fun notificationsOk(ctx: android.content.Context): Boolean = Build.VERSION.SDK_INT < 33 ||
    ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

@Composable
private fun WakeHint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
