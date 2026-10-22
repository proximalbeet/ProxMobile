package com.proxai.hermes.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.proxai.hermes.ProxApp
import com.proxai.hermes.voice.MicState
import com.proxai.hermes.voice.SpeechController
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val s = ProxApp.instance.settings
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.serverUrl) }
    var key by remember { mutableStateOf(s.apiKey) }
    var model by remember { mutableStateOf(s.model) }
    var speak by remember { mutableStateOf(s.speakReplies) }
    var kanye by remember { mutableStateOf(s.kanyeVoice) }
    val voiceTester = remember { SpeechController(ctx) {} }
    DisposableEffect(Unit) { onDispose { voiceTester.release() } }
    val testerState by voiceTester.state.collectAsState()
    var autoListen by remember { mutableStateOf(s.autoListen) }
    var handsFree by remember { mutableStateOf(s.handsFree) }
    var status by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var testing by remember { mutableStateOf(false) }

    // Refresh these whenever the user comes back from system settings.
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

    fun save() { s.serverUrl = url; s.apiKey = key; s.model = model }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Settings", fontWeight = FontWeight.SemiBold) })
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {

            Section("Hermes connection")
            OutlinedTextField(url, { url = it; status = null }, Modifier.fillMaxWidth(),
                label = { Text("Server URL") }, singleLine = true,
                placeholder = { Text("https://desktop.tail773dbb.ts.net") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(key, { key = it; status = null }, Modifier.fillMaxWidth(),
                label = { Text("API key (proxai API_SERVER_KEY)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(),
                label = { Text("Model alias (optional)") }, singleLine = true,
                supportingText = { Text("Leave blank to use the proxai profile's default model.") })
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !testing, onClick = {
                    save(); testing = true
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
                    Text(msg, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Section("Power button")
            StepCard(
                done = hasMic, title = "1. Allow the microphone",
                body = "Needed for talking to Hermes.",
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
                    "Samsung: Settings → Advanced features → Side button → Press and hold — choose the digital assistant if your One UI version offers it.\n" +
                    "No option on your phone? Add the “Ask Hermes” Quick Settings tile instead.",
                action = null)

            Section("Voice")
            Toggle("Read replies aloud", speak) { speak = it; s.speakReplies = it }
            Toggle("Kanye voice (made on your PC)", kanye) { kanye = it; s.kanyeVoice = it }
            Text(
                if (kanye) "Same voice as Discord. Needs the PC on; the first reply after a few idle minutes takes ~10 s while the voice loads. Falls back to the phone voice if the PC can't be reached."
                else "Uses the phone's built-in voice.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(
                enabled = testerState == MicState.Idle,
                onClick = {
                    save()
                    voiceTester.speak("Yo, it's Ye. This is how I sound on your phone now. Let's build something.")
                },
            ) {
                Text(when (testerState) {
                    MicState.Preparing -> "Loading voice…"
                    MicState.Speaking -> "Speaking…"
                    else -> "Test voice"
                })
            }
            Toggle("Start listening when the assistant opens", autoListen) { autoListen = it; s.autoListen = it }
            Toggle("Hands-free: keep listening after each reply", handsFree) { handsFree = it; s.handsFree = it }

            SpeechInputSection()
            ModelSection()
            NotificationsSection()
            ContextSection()

            Spacer(Modifier.height(8.dp))
            Text("Session: ${s.sessionId}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp))
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
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
