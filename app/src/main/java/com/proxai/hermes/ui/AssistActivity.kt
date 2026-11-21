package com.proxai.hermes.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.MotionEvent
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.outlined.ImageSearch
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.proxai.hermes.data.Channel
import com.proxai.hermes.data.VoicePolicy
import com.proxai.hermes.data.VoiceSend
import com.proxai.hermes.voice.MicState
import com.proxai.hermes.voice.SpeechController
import com.proxai.hermes.voice.SpeechLog
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The power-button overlay: a bottom sheet over whatever you were doing. Starts listening
 * immediately, sends what you say to Hermes, reads the answer back.
 */
class AssistActivity : ComponentActivity() {
    private lateinit var speech: SpeechController
    private val app get() = ProxApp.instance
    private var lastVoiceReplyId: String? = null
    private var unlocking = false
    /** S2: this overlay's power-button press (its screenshot, if Android sent one); -1 = none (tile, app). */
    private var lookGen by androidx.compose.runtime.mutableIntStateOf(-1)

    // ---- WAKE-PHONE round 2 ----
    /** How this overlay was opened: "power", "tile", or "wake" ("Hey Prox"). Fixed when it opens. */
    private var source: String? = null
    private val wake: Boolean get() = source == VoicePolicy.WAKE
    /** He touched this wake overlay, pressed the power button in it or unlocked in it. */
    private var used = false
    /** For ProxApp: an untouched wake overlay isn't "on screen" for the app lock's timer. */
    val countsOnScreen: Boolean get() = VoicePolicy.countsOnScreen(source, used)
    private val wakeScope = MainScope()
    /** A request this wake overlay sent whose reply hasn't been followed up yet (hands-free listens only then). */
    private var answeredRequestPending = false
    /** Round 3 (#49): hands-free follow-ups this wake overlay has started. */
    private var followUps = 0
    /** "That needs your tap" on the Accept / Reject sheet, when voice met a waiting yes-gate. */
    private var gateNote by mutableStateOf<String?>(null)
    /** A line about what happened to the words he said (held, waiting for the reply). */
    private var wakeNote by mutableStateOf<String?>(null)
    private var typing by mutableStateOf(false)
    private var text by mutableStateOf("")
    /** Round 3 (shape C): a wake on the lock screen past the hands-free window: only "Unlock to talk" shows, and
     *  nothing listens until the phone is unlocked. */
    private var needsPhoneUnlock by mutableStateOf(false)

    private fun pressOf(intent: Intent?): Int =
        if (intent?.getStringExtra(EXTRA_SOURCE) == "power") com.proxai.hermes.data.ScreenContext.generation else -1

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startListening()   // through the same gate as every other path (round 3)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // C4: the power-button assistant always talks in the ProxAI DM (where the briefs are), not a channel.
        app.chat.switchTo(com.proxai.hermes.data.Channel.DM)
        lookGen = pressOf(intent)
        source = intent?.getStringExtra(EXTRA_SOURCE)
        // WAKE-PHONE: while the overlay is open the "Hey Prox" listener lets go of the mic.
        com.proxai.hermes.data.MicGate.hold()
        // After "Hey Prox", speech becomes text on the phone only (never Google's online recognizer).
        val onDeviceOnly = VoicePolicy.onDeviceOnly(source)
        // "Hey Prox" with the screen off: the overlay lights the screen, like the power-button hold does.
        if (onDeviceOnly) setTurnScreenOn(true)
        // Round 3 (shape C): the overlay decides the lock screen itself (the listener's hint can only make it stricter).
        if (wake) {
            val keyguard = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
            val d = com.proxai.hermes.voice.WakeWordService.lockScreen(this, keyguard, System.currentTimeMillis())
            needsPhoneUnlock = intent?.getBooleanExtra(EXTRA_UNLOCK_TO_TALK, false) == true ||
                !com.proxai.hermes.data.WakeLockScreen.autoListen(d)
        }
        speech = SpeechController(this, onDeviceOnly, VoicePolicy.dictation(source)) { heard -> sendSpoken(heard) }
        // Round 2 (false wakes): nothing heard after the word and nobody touched the overlay: it closes quietly.
        speech.onNothingHeard = { if (wake && !used) { finish(); true } else false }
        speech.onSpeakDone = {
            if (app.settings.handsFree && !isFinishing && !app.chat.busy.value && followUpAllowed()) startListening(followUp = true)
        }

        setContent { ProxTheme { Overlay() } }

        if (app.settings.isConfigured && app.settings.speakReplies) speech.warmupVoice()
        // Server mode? (decides local vs cloud model); runs while you talk.
        if (app.settings.isConfigured) app.scope.launch { app.pcMode.current() }
        // S1: while locked, nothing listens until the unlock (Overlay shows the lock screen).
        // Round 3: a wake overlay listens when it opens (if it may), whatever "start listening" says.
        if (VoicePolicy.listenAfterAppUnlock(source, app.settings.autoListen) && app.settings.isConfigured &&
            !app.lock.check()) startListening()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Power button pressed again while open: start a fresh utterance (and a fresh screen to look at).
        lookGen = pressOf(intent)
        if (intent.getStringExtra(EXTRA_SOURCE) == "power") {
            markUsed()                 // a press is him, like a touch
            needsPhoneUnlock = false   // and the power-button hold works on the lock screen as it always has
        }
        if (app.settings.isConfigured && !app.lock.check()) startListening()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Round 3: a touch on "Unlock to talk" isn't use yet; the phone's unlock after it is (UnlockToTalk).
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && !needsPhoneUnlock) markUsed()
        return super.dispatchTouchEvent(ev)
    }

    /** Round 2 (app lock): from now on this wake overlay counts as on screen (ProxApp runs the lock's onScreen). */
    private fun markUsed() {
        if (!wake || used) return
        used = true
        app.screenUsed(this)
    }

    /** Round 2 (false wakes): in a wake overlay, hands-free listens again only after the reply to a request it sent. */
    private fun followUpAllowed(): Boolean {
        val ok = VoicePolicy.mayFollowUp(source, answeredRequestPending, used, followUps)
        if (wake) answeredRequestPending = false
        if (ok && wake) followUps++
        return ok
    }

    /**
     * What he said goes out. From the power button and the tile, at once as always. In a wake overlay (round 2,
     * yes-gates): after any reply on its way, it asks the PC whether a yes-gate is waiting. Voice never answers
     * one (any message would): the Accept / Reject sheet shows with "that needs your tap" and nothing is sent.
     * When the PC can't be asked, nothing is sent either: the words wait in the text box for his tap on Send.
     */
    private fun sendSpoken(heard: String) {
        if (!wake) {
            app.chat.send(heard, fromVoice = true)
            return
        }
        wakeScope.launch {
            if (app.chat.busy.value) {
                wakeNote = "Got it. I'll send that when ProxAI finishes."
                app.chat.busy.first { !it }
            }
            wakeNote = null
            // The app lock's time can run out while an untouched wake overlay is open: then nothing is sent.
            if (app.lock.check()) return@launch
            when (VoicePolicy.voiceSend(source, app.chat.gateNow(Channel.DM))) {
                VoiceSend.SEND -> {
                    answeredRequestPending = true
                    app.chat.send(heard, fromVoice = true)
                }
                VoiceSend.NEEDS_TAP -> {
                    gateNote = "That needs your tap: a spoken answer never counts here."
                    SpeechLog.add("from=wake: an OK is waiting on the PC, nothing sent")
                }
                VoiceSend.HOLD -> {
                    text = heard
                    typing = true
                    wakeNote = "Couldn't ask your PC whether an OK is waiting, so nothing was sent. Tap Send to send it."
                    SpeechLog.add("from=wake: couldn't check the PC's OKs, nothing sent")
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        speech.cancelListening()
        // Leaving the overlay ends it, like other assistants; the reply keeps streaming into chat.
        if (!isChangingConfigurations && !unlocking) finish()
    }

    override fun onDestroy() {
        wakeScope.cancel()
        speech.release()
        com.proxai.hermes.data.MicGate.release()
        // S2: the screen is only for this overlay; it's dropped when it closes.
        if (isFinishing && lookGen >= 0) com.proxai.hermes.data.ScreenContext.drop(lookGen)
        super.onDestroy()
    }

    /**
     * S2 (Nathan 10-30: only when he taps): sends this press's screenshot with his question (typed, or
     * "What am I looking at?"), once. Pictures go to the cloud model (C5).
     */
    private fun lookAtScreen(typed: String, spoken: Boolean) {
        val shot = com.proxai.hermes.data.ScreenContext.take(lookGen) ?: return
        speech.cancelListening()
        app.scope.launch {
            val a = runCatching {
                com.proxai.hermes.data.Attachments.fromScreenshot(this@AssistActivity, shot.bitmap, shot.appName)
            }.getOrNull()
            if (a == null) {
                android.widget.Toast.makeText(this@AssistActivity, "Couldn't read the screen", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val q = typed.trim().ifEmpty { "What am I looking at?" } + (shot.appName?.let { " [On screen: $it]" } ?: "")
            app.chat.send(q, fromVoice = spoken, attachments = listOf(a))
        }
    }

    /** [followUp]: hands-free listening after a reply (silence ends it quietly). */
    private fun startListening(followUp: Boolean = false) {
        // Round 3 (shape C): past the hands-free window, nothing listens while the phone is still locked.
        if (needsPhoneUnlock) {
            if (getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false) return
            needsPhoneUnlock = false
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.listen(followUp)
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
        // S1: the power-button assistant is blocked while ProxAI is locked.
        val locked by app.lock.locked.collectAsState()
        // Round 2 (app lock): an untouched wake overlay doesn't stop the lock's timer, so it checks it as it goes.
        // It also closes when untouched and idle a minute (locked, nothing heard, the reply done): while open it
        // keeps the wake listener off.
        LaunchedEffect(Unit) {
            var lastActive = System.currentTimeMillis()
            while (wake) {
                delay(5_000)
                if (!used) app.lock.check()
                val now = System.currentTimeMillis()
                if (speech.state.value != MicState.Idle || app.chat.busy.value) lastActive = now
                else if (VoicePolicy.closeIdle(source, used, now - lastActive)) {
                    SpeechLog.add("from=wake overlay idle, closed")
                    finish()
                }
            }
        }
        if (locked) {
            LockScreen(onUnlocked = {
                markUsed()   // a fingerprint or the PIN in this overlay: it's him
                // Round 3 (shape C, path b): after the app's own unlock a wake overlay listens at once.
                if (VoicePolicy.listenAfterAppUnlock(source, app.settings.autoListen) && app.settings.isConfigured) startListening()
            }, onPrompt = { unlocking = it })
            return
        }
        if (needsPhoneUnlock) {
            UnlockToTalk()
            return
        }
        val messages by app.chat.messages.collectAsState()
        val busy by app.chat.busy.collectAsState()
        val mic by speech.state.collectAsState()
        val partial by speech.partial.collectAsState()
        val level by speech.level.collectAsState()
        val error by speech.error.collectAsState()
        val pcMode by app.pcMode.state.collectAsState()
        val queued by app.chat.queuedText.collectAsState()
        val shot by com.proxai.hermes.data.ScreenContext.shot.collectAsState()
        // F1 (0.9.9): a question the PC is waiting on pops up here too, with Reject / Accept (past the lock only).
        val waiting by app.chat.approval.collectAsState()
        LaunchedEffect(Unit) { app.chat.checkApproval(com.proxai.hermes.data.Channel.DM) }
        waiting?.let { p ->
            ApprovalDialog(p.approval, note = gateNote,
                onAnswer = { ok -> gateNote = null; app.chat.answerApproval(ok) },
                onClose = { gateNote = null; app.chat.closeApproval() })
        }

        // Only show this overlay's exchange, not the whole chat history.
        val openedAt = remember { System.currentTimeMillis() - 1000 }
        val visible = messages.filter { it.time >= openedAt }

        LaunchedEffect(busy) { speech.setThinking(busy) }
        LaunchedEffect(Unit) {
            app.chat.done.collect { done ->
                if (!(done.fromVoice && app.settings.speakReplies) && app.settings.handsFree && done.fromVoice &&
                    followUpAllowed())
                    startListening(followUp = true)
            }
        }
        LaunchedEffect(Unit) {
            // Voice replies are spoken part by part as they arrive, not after the whole reply.
            app.chat.voiceParts.collect { p ->
                if (!app.settings.speakReplies) return@collect
                if (p.first) {
                    if (p.messageId == lastVoiceReplyId) return@collect
                    lastVoiceReplyId = p.messageId
                    speech.beginParts()
                } else if (p.messageId != lastVoiceReplyId) return@collect
                speech.addPart(p.text)
                if (p.last) speech.endParts()
            }
        }

        Box(
            // statusBarsPadding: a tall panel stops below the status bar (it slid under it with the keyboard up).
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { finish() }
                .statusBarsPadding(),
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
                        ProxLogo(30.dp)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text("ProxAI", fontWeight = FontWeight.SemiBold)
                            if (app.settings.isConfigured) Text(routeLine(pcMode), maxLines = 1,
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = ::openChat) { Icon(Icons.Outlined.OpenInFull, "Open in app") }
                        IconButton(onClick = { finish() }) { Icon(Icons.Outlined.Close, "Close") }
                    }

                    if (!app.settings.isConfigured) {
                        Text("Connect ProxAI to your PC first (Settings).", Modifier.padding(16.dp))
                        Button(onClick = ::openChat) { Text("Open settings") }
                        return@Column
                    }

                    val listState = rememberLazyListState()
                    LaunchedEffect(visible.size, visible.lastOrNull()?.text?.length) {
                        if (visible.isNotEmpty()) listState.animateScrollToItem(visible.size - 1, Int.MAX_VALUE / 2)
                    }
                    if (visible.isNotEmpty()) {
                        // weight(fill = false): the replies give up room first when the keyboard opens, so the
                        // text box below keeps its full height (10-29: it got squeezed to half).
                        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 380.dp)) {
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

                    val pauseSec = app.settings.sendPauseMs / 1000.0
                    val caption = when {
                        wakeNote != null && mic != MicState.Listening -> wakeNote!!
                        error != null && mic == MicState.Idle -> error!!
                        mic == MicState.Listening -> partial.ifEmpty {
                            if (app.settings.sendPauseMs == 0L) "Listening… tap Send when you're done"
                            else "Listening… (sends after ${"%.1f".format(pauseSec).removeSuffix(".0")} s of quiet)"
                        }
                        queued != null -> "Got it. I'll send that when ProxAI finishes."
                        mic == MicState.Thinking || busy ->
                            if (visible.lastOrNull()?.thinking == true) "ProxAI is thinking…" else "ProxAI is working…"
                        mic == MicState.Preparing -> "Ye is getting his voice ready…"
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
                            OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Type to ProxAI") },
                                shape = RoundedCornerShape(24.dp), maxLines = 4,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                                keyboardActions = KeyboardActions(onSend = { app.chat.send(text, fromVoice = false); text = ""; wakeNote = null }))
                            IconButton(onClick = { app.chat.send(text, fromVoice = false); text = ""; wakeNote = null }) {
                                Icon(Icons.AutoMirrored.Filled.Send, "Send")
                            }
                        }
                    }

                    // S2: only when Android sent this press's screen ("Use screenshot" on), and only if tapped.
                    if (lookGen >= 0 && shot?.generation == lookGen && !busy) {
                        OutlinedButton(onClick = { lookAtScreen(text, spoken = !typing); text = "" },
                            modifier = Modifier.padding(top = 4.dp)) {
                            Icon(Icons.Outlined.ImageSearch, null)
                            Spacer(Modifier.width(6.dp))
                            Text(shot?.appName?.let { "Look at my screen ($it)" } ?: "Look at my screen", maxLines = 1)
                        }
                    }

                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly) {
                        IconButton(onClick = { typing = !typing; if (typing) speech.cancelListening() }) {
                            Icon(Icons.Outlined.Keyboard, "Type instead")
                        }
                        MicOrb(mic, level, busy && mic != MicState.Listening) {
                            when {
                                mic == MicState.Listening -> speech.finishNow()
                                busy -> app.chat.cancel()
                                else -> { typing = false; startListening() }
                            }
                        }
                        if (mic == MicState.Listening) {
                            // Send now, without waiting for the pause.
                            IconButton(onClick = { speech.finishNow() }) { Icon(Icons.AutoMirrored.Filled.Send, "Send now") }
                        } else Spacer(Modifier.size(48.dp))
                    }
                }
            }
        }
    }

    /** Round 3 (shape C): one touch asks for the phone's unlock; once it's unlocked, ProxAI listens. */
    @Composable
    private fun UnlockToTalk() {
        Box(
            Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { finish() }
                .statusBarsPadding(),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier.fillMaxWidth().clickable(remember { MutableInteractionSource() }, indication = null) { },
            ) {
                Column(Modifier.navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ProxLogo(30.dp)
                    Text("Heard “Hey Prox”", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    Text("Your phone is locked. Unlock it and ProxAI listens.", textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp))
                    // startListening() clears the flag itself, only once the phone is really unlocked.
                    Button(onClick = { afterUnlock { startListening(); if (!needsPhoneUnlock) markUsed() } }) {
                        Text("Unlock to talk")
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_SOURCE = "source"
        /** Round 3: set by the wake listener when the lock screen is past the hands-free window (a hint only). */
        const val EXTRA_UNLOCK_TO_TALK = "unlock_to_talk"
    }
}

@Composable
private fun MicOrb(state: MicState, level: Float, busy: Boolean, onClick: () -> Unit) {
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "b")
    val voice by animateFloatAsState(1f + level * 0.35f, label = "v")
    val scale = when {
        state == MicState.Listening -> voice
        busy || state == MicState.Speaking || state == MicState.Preparing -> breathe
        else -> 1f
    }
    val colors = when {
        state == MicState.Listening -> listOf(Color(0xFF8FF3FF), BrandCyan, Brand)
        busy -> listOf(BrandCyan, Brand, BrandDeep)
        state == MicState.Speaking || state == MicState.Preparing -> listOf(Color(0xFFBFE3FF), Brand, BrandDeep)
        else -> listOf(BrandCyan, Brand, BrandDeep)
    }
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        // Blue glow around the orb (a radial fade, works on every Android version).
        Box(Modifier.size(112.dp).scale(scale).clip(CircleShape)
            .background(Brush.radialGradient(listOf(colors[1].copy(alpha = 0.45f), colors[1].copy(alpha = 0.12f), Color.Transparent))))
        Box(Modifier.size(72.dp).scale(scale).clip(CircleShape).background(Brush.linearGradient(colors)).clickable(onClick = onClick),
            contentAlignment = Alignment.Center) {
            Icon(if (busy) Icons.Filled.Stop else Icons.Filled.Mic, if (busy) "Stop" else "Talk",
                tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}
