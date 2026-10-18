package com.proxai.hermes.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.voice.MicState
import com.proxai.hermes.voice.SpeechController

/**
 * The power-button overlay: a bottom sheet over whatever you were doing. Starts listening
 * immediately, sends what you say to Hermes, reads the answer back.
 */
class AssistActivity : ComponentActivity() {
    private lateinit var speech: SpeechController
    private val app get() = ProxApp.instance
    private var lastVoiceReplyId: String? = null
    private var unlocking = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) speech.listen()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        speech = SpeechController(this) { heard -> app.chat.send(heard, fromVoice = true) }
        speech.onSpeakDone = {
            if (app.settings.handsFree && !isFinishing && !app.chat.busy.value) startListening()
        }

        setContent { ProxTheme { Overlay() } }

        if (app.settings.autoListen && app.settings.isConfigured) startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Power button pressed again while open: start a fresh utterance.
        if (app.settings.isConfigured) startListening()
    }

    override fun onStop() {
        super.onStop()
        speech.cancelListening()
        // Leaving the overlay ends it, like other assistants; the reply keeps streaming into chat.
        if (!isChangingConfigurations && !unlocking) finish()
    }

    override fun onDestroy() {
        speech.release()
        super.onDestroy()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.listen()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    /** Unlocks first if needed, then runs [block] (used before leaving the lock screen). */
    private fun afterUnlock(block: () -> Unit) {
        val km = getSystemService(KeyguardManager::class.java)
        if (km?.isKeyguardLocked == true) {
            unlocking = true
            km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() { unlocking = false; block() }
                override fun onDismissCancelled() { unlocking = false }
                override fun onDismissError() { unlocking = false }
            })
        } else block()
    }

    private fun openApp(id: String) = afterUnlock {
        startActivity(AppRunnerActivity.intent(this, id))
        finish()
    }

    private fun openChat() = afterUnlock {
        startActivity(Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_CHAT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    @Composable
    private fun Overlay() {
        val messages by app.chat.messages.collectAsState()
        val busy by app.chat.busy.collectAsState()
        val mic by speech.state.collectAsState()
        val partial by speech.partial.collectAsState()
        val level by speech.level.collectAsState()
        val error by speech.error.collectAsState()
        var typing by remember { mutableStateOf(false) }
        var text by remember { mutableStateOf("") }

        // Only show this overlay's exchange, not the whole chat history.
        val openedAt = remember { System.currentTimeMillis() - 1000 }
        val visible = messages.filter { it.time >= openedAt }

        LaunchedEffect(busy) { speech.setThinking(busy) }
        LaunchedEffect(Unit) {
            app.chat.done.collect { done ->
                if (done.fromVoice && app.settings.speakReplies && done.message.id != lastVoiceReplyId) {
                    lastVoiceReplyId = done.message.id
                    speech.speak(done.message.text.ifBlank { done.message.error ?: "" })
                } else if (app.settings.handsFree && done.fromVoice) startListening()
            }
        }

        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { finish() },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth()
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .imePadding(),
            ) {
                Column(Modifier.navigationBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIconBadge("Hermes", 28.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Hermes", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        IconButton(onClick = ::openChat) { Icon(Icons.Outlined.OpenInFull, "Open in app") }
                        IconButton(onClick = { finish() }) { Icon(Icons.Outlined.Close, "Close") }
                    }

                    if (!app.settings.isConfigured) {
                        Text("Connect ProxAI to your Hermes server first.", Modifier.padding(16.dp))
                        Button(onClick = ::openChat) { Text("Open settings") }
                        return@Column
                    }

                    val listState = rememberLazyListState()
                    LaunchedEffect(visible.size, visible.lastOrNull()?.text?.length) {
                        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1, Int.MAX_VALUE / 2)
                    }
                    if (visible.isNotEmpty()) {
                        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp)) {
                            items(visible, key = { it.id }) { m ->
                                MessageBubble(m, onOpenApp = ::openApp, onRemixApp = { id ->
                                    afterUnlock {
                                        startActivity(Intent(this@AssistActivity, MainActivity::class.java)
                                            .putExtra(MainActivity.EXTRA_REMIX, id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                        finish()
                                    }
                                })
                            }
                        }
                    }
                    // Big "open it" button when the last reply produced an app.
                    visible.lastOrNull()?.appId?.let { id ->
                        val a = app.apps.get(id)
                        if (a != null) Button(onClick = { openApp(id) }, modifier = Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Outlined.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("Open ${a.title}")
                        }
                    }

                    val caption = when {
                        error != null && mic == MicState.Idle -> error!!
                        mic == MicState.Listening -> partial.ifEmpty { "Listening…" }
                        mic == MicState.Thinking || busy -> "Hermes is working…"
                        mic == MicState.Speaking -> "Speaking — tap the orb to interrupt"
                        visible.isEmpty() -> "Tap to talk"
                        else -> ""
                    }
                    if (caption.isNotEmpty()) {
                        Text(caption, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium,
                            color = if (mic == MicState.Listening) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp, horizontal = 8.dp))
                    }

                    if (typing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Type to Hermes") },
                                shape = RoundedCornerShape(24.dp), maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { app.chat.send(text, fromVoice = false); text = "" }))
                            IconButton(onClick = { app.chat.send(text, fromVoice = false); text = "" }) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send")
                            }
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { typing = !typing; if (typing) speech.cancelListening() }) {
                            Icon(Icons.Outlined.Keyboard, "Type instead")
                        }
                        MicOrb(mic, level, busy) {
                            when {
                                busy -> app.chat.cancel()
                                mic == MicState.Listening -> speech.stopListening()
                                else -> { typing = false; startListening() }
                            }
                        }
                        Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE = "source"
    }
}

@Composable
private fun MicOrb(state: MicState, level: Float, busy: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
    val voice by animateFloatAsState(1f + level * 0.35f, label = "v")
    val scale = when {
        state == MicState.Listening -> voice
        busy || state == MicState.Speaking -> breathe
        else -> 1f
    }
    val colors = when {
        state == MicState.Listening -> listOf(Mint, Color(0xFF3FA796))
        busy -> listOf(Brand, BrandDeep)
        state == MicState.Speaking -> listOf(Color(0xFFC9BCFF), Brand)
        else -> listOf(Brand, BrandDeep)
    }
    Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(84.dp).scale(scale * 1.12f).clip(CircleShape).background(colors[0].copy(alpha = 0.18f)))
        Box(Modifier.size(72.dp).scale(scale).clip(CircleShape).background(Brush.linearGradient(colors)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(if (busy) Icons.Filled.Stop else Icons.Filled.Mic, if (busy) "Stop" else "Talk",
                tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
