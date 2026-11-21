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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.UnfoldLess
import com.proxai.hermes.data.PcMode
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

/** "local qwen3.5:35b-a3b (server mode)" / "cloud (personal mode)" for the headers. */
fun routeLine(mode: PcMode?, channel: String = ProxApp.instance.chat.active.value): String {
    val route = ProxApp.instance.chat.currentRoute(channel)
    val m = when (mode?.mode) {
        null -> ""
        "server", "personal" -> " (${mode.mode} mode)"
        "offline" -> " (PC unreachable)"
        else -> ""
    }
    return (if (route.local) "local ${route.model}" else "cloud") + m
}

/**
 * The header's size-and-cost line: one short line, cost first ("$0.005 so far · ~33k tokens").
 * 0.8.1 put a third button in the bar, so the old "context ~33k tokens · this session $0.005" wrapped and
 * the bar's fixed 64 dp cut off its second line, the one with the cost (Nathan, 10-29). Null = nothing yet.
 */
fun statsLine(stats: com.proxai.hermes.data.SessionStats): String? {
    val size = if (stats.lastPromptTokens > 0) formatTokens(stats.lastPromptTokens) else null
    if (stats.localManaged) return if (size == null) "PC compacts at ~56k" else "$size · compacts at ~56k"
    if (size == null) return null
    val cost = when {
        stats.costUsd <= 0 -> return "context ~$size"
        stats.costExact -> "$" + (if (stats.costUsd < 0.01) "%.3f" else "%.2f").format(java.util.Locale.US, stats.costUsd)
        else -> "≤ $" + "%.2f".format(java.util.Locale.US, stats.costUsd)
    }
    return "$cost so far · ~$size"
}

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
    // C4: the channel on screen, and where ProxAI is answering (it can be another channel).
    val activeId by repo.active.collectAsState()
    val channels by repo.channelList.collectAsState()
    val busyIn by repo.busyChannel.collectAsState()
    val channel = channels.firstOrNull { it.id == activeId } ?: channels.first()
    val busyHere = busy && busyIn == activeId
    var showChannels by remember { mutableStateOf(false) }
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

    // C5: files attached to the next message (pictures, PDFs, text files), ready to send.
    val pending = remember { androidx.compose.runtime.mutableStateListOf<com.proxai.hermes.data.Attachment>() }
    var preparing by remember { mutableStateOf(0) }
    var attachMenu by remember { mutableStateOf(false) }
    var appPicker by remember { mutableStateOf(false) }   // P3: someone else's app on this phone, sent to the PC
    // 0.9.6: an app or file going to the PC runs in the background (ResearchSendService); its progress shows in the
    // tray, and once it's on the PC it waits in ResearchSends.ready (which outlives this screen) for the next message.
    val researchSend by com.proxai.hermes.data.ResearchSends.current.collectAsState()
    val researchReady by com.proxai.hermes.data.ResearchSends.ready.collectAsState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    fun attach(uris: List<android.net.Uri>) {
        uris.forEach { uri ->
            preparing++
            scope.launch {
                runCatching { com.proxai.hermes.data.Attachments.prepare(ctx, uri) }
                    .onSuccess { a ->
                        if (com.proxai.hermes.data.Attachments.imageCount(pending + a) > com.proxai.hermes.data.Attachments.MAX_IMAGES) {
                            com.proxai.hermes.data.Attachments.delete(listOf(a))
                            android.widget.Toast.makeText(ctx, "Up to ${com.proxai.hermes.data.Attachments.MAX_IMAGES} pictures " +
                                "(PDF pages count) per message", android.widget.Toast.LENGTH_LONG).show()
                        } else pending += a
                    }
                    .onFailure { android.widget.Toast.makeText(ctx, it.message ?: "Can't attach that", android.widget.Toast.LENGTH_LONG).show() }
                preparing--
            }
        }
    }
    val pickPhotos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { attach(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attach(it) }
    val pickPcFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val why = if (!com.proxai.hermes.ProxApp.instance.settings.isConfigured) "Connect ProxAI to your PC first (Settings)"
                else com.proxai.hermes.notify.ResearchSendService.startFile(ctx, uri)
            if (why != null) android.widget.Toast.makeText(ctx, why, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun send(text: String = input) {
        if (text.isBlank() && pending.isEmpty() && researchReady.isEmpty()) return
        repo.send(text, fromVoice = false, targetAppId = remixAppId,
            attachments = pending.toList() + com.proxai.hermes.data.ResearchSends.takeReady())
        pending.clear()
        onInput("")
        onClearRemix()
    }

    val mode by app.pcMode.state.collectAsState()
    LaunchedEffect(Unit) { app.pcMode.current() }
    val stats = remember(messages) { com.proxai.hermes.data.ChatRepository.stats(messages) }

    // C10: a day divider above the first message of each day (rows = dividers + messages).
    val dayStarts = remember(messages) { com.proxai.hermes.data.ChatTimes.dayStarts(messages) }
    val last = messages.lastOrNull()
    LaunchedEffect(messages.size, last?.text?.length, last?.tools?.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size + dayStarts.size - 1, Int.MAX_VALUE / 2)
    }

    // C1: while the chat is open, new PC notices (brief, nudges...) land in it within a minute.
    DisposableEffect(Unit) {
        repo.chatOnScreen = true
        onDispose { repo.chatOnScreen = false }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (app.inForeground) com.proxai.hermes.data.NoticeSync.pull(ctx.applicationContext)
            kotlinx.coroutines.delay(60_000)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ProxLogo(34.dp)
                    Spacer(Modifier.size(10.dp))
                    Column {
                        if (channel.isDm) BrandTitle("ProxAI") else BrandTitle("#${channel.name}")
                        val state = when {
                            busyHere -> "working…"
                            busy -> "answering in ${channels.firstOrNull { it.id == busyIn }?.let { if (it.isDm) "the DM" else "#${it.name}" } ?: "another chat"}…"
                            else -> "ready"
                        }
                        Text(if (app.settings.isConfigured) "$state · ${routeLine(mode, channel.id)}"
                            else "not connected — open Settings",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1)
                        // One line, cost first (statsLine): a wrapped line fell outside the 64 dp bar.
                        statsLine(stats)?.let {
                            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        }
                    }
                }
            },
            actions = {
                IconButton(onClick = { repo.compact() }, enabled = !busy && (stats.lastPromptTokens > 0 || stats.localManaged)) {
                    Icon(Icons.Outlined.UnfoldLess, "Compact the conversation")
                }
                IconButton(onClick = { showChannels = true }) { Icon(Icons.Outlined.Tag, "Channels and trash") }
                IconButton(onClick = { repo.newChat() }) { Icon(Icons.Outlined.AddComment, "New chat (this one goes to the trash)") }
            },
        )

        Box(Modifier.weight(1f)) {
            if (messages.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    ProxLogo(76.dp)
                    Spacer(Modifier.height(18.dp))
                    Text(if (channel.isDm) "Ask ProxAI anything" else "#${channel.name}", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(6.dp))
                    Text(if (channel.isDm) "…or ask for an app and it lands in your Apps tab.\nHold the power button to talk hands-free."
                        else "A separate chat with its own memory on the PC · ${com.proxai.hermes.data.Channel.modelLabel(channel.model)}.\n" +
                            "Briefs and notices stay in the ProxAI DM.",
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
                    messages.forEachIndexed { i, m ->
                        if (i in dayStarts) item(key = "day-${m.id}") { DayDivider(m.time) }
                        item(key = m.id) { MessageBubble(m, onOpenApp, onRemixApp) }
                    }
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

        val showSend = researchSend?.let { !it.finished || it.error != null } == true
        if (pending.isNotEmpty() || researchReady.isNotEmpty() || preparing > 0 || showSend) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showSend) researchSend?.let { ResearchSendChip(it) }
                researchReady.forEach { a ->
                    AttachmentThumb(a, 72.dp) {
                        if (com.proxai.hermes.data.ResearchSends.remove(a)) com.proxai.hermes.data.Attachments.delete(listOf(a))
                    }
                }
                pending.forEach { a -> AttachmentThumb(a, 72.dp) { pending.remove(a); com.proxai.hermes.data.Attachments.delete(listOf(a)) } }
                if (preparing > 0) androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                IconButton(onClick = { attachMenu = true }, modifier = Modifier.padding(bottom = 4.dp)) {
                    Icon(Icons.Outlined.AttachFile, "Attach a picture, file or app")
                }
                androidx.compose.material3.DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text("Photos") }, onClick = {
                        attachMenu = false
                        pickPhotos.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("File (PDF or text)") }, onClick = {
                        attachMenu = false
                        pickFiles.launch(arrayOf("application/pdf", "text/*", "application/json", "application/xml", "image/*"))
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("An app on this phone") }, onClick = {
                        attachMenu = false
                        appPicker = true
                    })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("A file to look inside (firmware…)") }, onClick = {
                        attachMenu = false
                        pickPcFile.launch(arrayOf("*/*"))
                    })
                }
            }
            OutlinedTextField(
                value = if (mic == MicState.Listening && partial.isNotEmpty()) partial else input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (mic == MicState.Listening) "Listening…" else "Message ProxAI") },
                shape = RoundedCornerShape(24.dp),
                maxLines = 6,
            )
            if (busyHere) {
                FilledIconButton(onClick = { repo.cancel() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.Stop, "Stop") }
            } else if (input.isBlank() && pending.isEmpty() && researchReady.isEmpty()) {
                FilledIconButton(
                    onClick = {
                        if (mic == MicState.Listening) speech.stopListening()
                        else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) speech.listen()
                        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    modifier = Modifier.size(52.dp),
                    colors = if (mic == MicState.Listening) IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    else IconButtonDefaults.filledIconButtonColors(),
                ) {
                    if (mic == MicState.Listening) Icon(Icons.Filled.Check, "Done talking")
                    else Icon(Icons.Filled.Mic, "Speak")
                }
            } else {
                FilledIconButton(onClick = { send() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        }
    }
    if (showChannels) ChannelsSheet(onDismiss = { showChannels = false })
    if (appPicker) AppPickerDialog(onDismiss = { appPicker = false })
}
