package com.proxai.hermes.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.voice.MicState
import com.proxai.hermes.voice.SpeechController

val APP_IDEAS = listOf(
    "Build me a tip calculator that splits the bill",
    "Make a habit tracker with streaks",
    "Build a pomodoro timer with a progress ring",
    "Make a grocery list I can check off",
    "Build a workout interval timer that beeps",
    "Make a flashcard app that uses ProxAI.ask to quiz me",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(
    input: String,
    onInput: (String) -> Unit,
    remixAppId: String?,
    onClearRemix: () -> Unit,
    onOpenApp: (String) -> Unit,
    onRemixApp: (String) -> Unit,
) {
    val app = ProxApp.instance
    val repo = app.chat
    val messages by repo.messages.collectAsState()
    val busy by repo.busy.collectAsState()
    val ctx = LocalContext.current
    val listState = rememberLazyListState()

    val speech = remember {
        SpeechController(ctx) { heard -> onInput(heard) }
    }
    DisposableEffect(Unit) { onDispose { speech.release() } }
    val mic by speech.state.collectAsState()
    val partial by speech.partial.collectAsState()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) speech.listen()
    }

    fun send(text: String = input) {
        if (text.isBlank()) return
        repo.send(text, fromVoice = false, targetAppId = remixAppId)
        onInput("")
        onClearRemix()
    }

    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length, last?.tools?.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1, Int.MAX_VALUE / 2)
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            title = {
                Column {
                    Text("Hermes", fontWeight = FontWeight.SemiBold)
                    Text(if (app.settings.isConfigured) "proxai · ${if (busy) "working…" else "ready"}" else "not connected — open Settings",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            actions = {
                IconButton(onClick = { repo.newChat() }) { Icon(Icons.Outlined.AddComment, "New chat") }
            },
        )

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ask Hermes anything", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text("…or ask for an app and it lands in your Apps tab.\nHold the power button to talk hands-free.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(20.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        APP_IDEAS.take(4).forEach { idea -> AssistChip(onClick = { send(idea) }, label = { Text(idea) }) }
                    }
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)) {
                    items(messages, key = { it.id }) { MessageBubble(it, onOpenApp, onRemixApp) }
                }
            }
        }

        if (remixAppId != null) {
            val target = app.apps.get(remixAppId)
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth()) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Remixing “${target?.title ?: "app"}” — describe the change",
                        style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onClearRemix) { Icon(Icons.Outlined.Close, "Cancel remix") }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = if (mic == MicState.Listening && partial.isNotEmpty()) partial else input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (mic == MicState.Listening) "Listening…" else "Message Hermes") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 6,
            )
            if (busy) {
                FilledIconButton(onClick = { repo.cancel() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Stop, "Stop") }
            } else if (input.isBlank()) {
                FilledIconButton(
                    onClick = {
                        if (mic == MicState.Listening) speech.stopListening()
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.listen()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.size(52.dp),
                    colors = if (mic == MicState.Listening) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    else IconButtonDefaults.filledIconButtonColors(),
                ) { Icon(Icons.Filled.Mic, "Speak") }
            } else {
                FilledIconButton(onClick = { send() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
}
