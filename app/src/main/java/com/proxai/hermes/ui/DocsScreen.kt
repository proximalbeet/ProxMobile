package com.proxai.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.DocsAnswer
import com.proxai.hermes.data.DocsStatus
import com.proxai.hermes.data.DocsUi
import com.proxai.hermes.data.ProxDocs
import kotlinx.coroutines.delay

private const val HASH_COMMAND = "proxdocs hash > ~/Documents/ProxVault/07-Security/proxdocs-password.hash"
private val EXAMPLES = listOf(
    "How does the app lock decide when to lock again?",
    "What changed in ProxMobile this week?",
    "What does the Vitals app show?",
)

/**
 * P4 (0.9.7): proxDocs, its own tab. Answers about ProxAI itself from the PC's local model, behind its own
 * password. Questions and answers stay on this screen (in memory) and never go into Chat or ProxAI's memory;
 * MainActivity keeps screenshots off while it shows. It locks with the app lock (ProxDocs).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocsScreen() {
    val ui by ProxDocs.ui.collectAsState()
    val configured = ProxApp.instance.settings.isConfigured
    LaunchedEffect(configured) { if (configured) ProxDocs.refresh() }
    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                title = {
                    Column {
                        Text("proxDocs", fontWeight = FontWeight.SemiBold, maxLines = 1)
                        Text(subtitle(ui), style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                },
                actions = {
                    if (ui.unlocked) {
                        IconButton(onClick = { ProxDocs.lock("Locked.") }) { Icon(Icons.Outlined.Lock, "Lock proxDocs") }
                    } else if (configured) {
                        IconButton(onClick = { ProxDocs.refresh() }) { Icon(Icons.Outlined.Refresh, "Check the PC again") }
                    }
                },
            )
        },
    ) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            when {
                !configured -> Notice("Connect ProxAI to your PC first", "Settings > Server. proxDocs asks the PC.")
                ui.unlocked -> Unlocked(ui)
                else -> DocsLocked(ui)
            }
        }
    }
}

private fun subtitle(ui: DocsUi): String {
    val st = ui.status ?: return if (ui.checking) "Checking the PC…" else "ProxAI's own docs"
    return when {
        !st.server -> "Locked: the PC is in personal mode"
        ui.unlocked && st.ready -> "Open · ${st.model}"
        ui.unlocked -> "Open · the model isn't loaded"
        else -> "Locked"
    }
}

@Composable
private fun Notice(title: String, text: String, action: (@Composable () -> Unit)? = null) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.Lock, null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        action?.invoke()
    }
}

/** Seconds left on a wrong-password wait, ticking down. */
@Composable
private fun secondsUntil(until: Long): Long {
    val left by produceState(((until - ProxDocs.now()) / 1000).coerceAtLeast(0), until) {
        while (true) {
            value = ((until - ProxDocs.now()) / 1000 + 1).coerceAtLeast(0)
            if (value <= 0) break
            delay(1_000)
        }
        value = 0
    }
    return left
}

/** The PC's state and the password box: the Docs tab and DocsUnlockActivity (apps, 0.9.8) both show it. */
@Composable
internal fun DocsLocked(ui: DocsUi) {
    val st = ui.status
    when {
        st == null && ui.checking -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        st == null -> Notice("Couldn't reach the PC", ui.error ?: "Check that the PC is on.") {
            TextButton(onClick = { ProxDocs.refresh() }) { Text("Try again") }
        }
        !st.server -> Notice("proxDocs is locked",
            (st.why.ifBlank { "proxDocs only runs in server mode." }) + " It opens in server mode, while the local model is loaded.") {
            TextButton(onClick = { ProxDocs.refresh() }) { Text("Check again") }
        }
        !st.passwordSet -> Notice("Set a proxDocs password on the PC", "On the PC, run this once (it asks for the new password twice):") {
            SelectionContainer {
                Text(HASH_COMMAND, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = { ProxDocs.refresh() }) { Text("Check again") }
        }
        else -> PasswordForm(ui)
    }
}

@Composable
private fun PasswordForm(ui: DocsUi) {
    var password by remember { mutableStateOf("") }
    val wait = secondsUntil(ui.waitUntil)
    val relock = lockTimeText(ProxApp.instance.settings.lockAfterMs)
    fun go() {
        if (password.isNotEmpty() && wait <= 0 && !ui.unlocking) {
            ProxDocs.unlock(password)
            password = ""                  // never kept on screen after a try
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
        Text("Ask about ProxAI itself", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("How each plugin, app and tool works, and what changed when, from ProxAI's docs, code docs, git history " +
            "and your apps' plans, answered by the local model on the PC.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(password, { password = it }, singleLine = true, label = { Text("proxDocs password") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { go() }), enabled = !ui.unlocking, modifier = Modifier.fillMaxWidth())
        Button(onClick = { go() }, enabled = password.isNotEmpty() && wait <= 0 && !ui.unlocking, modifier = Modifier.fillMaxWidth()) {
            Text(when {
                ui.unlocking -> "Checking…"
                wait > 0 -> "Wait $wait s"
                else -> "Unlock"
            })
        }
        (ui.error ?: ui.note)?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium,
                color = if (ui.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("It stays open until ProxAI's app lock locks again ($relock out of sight). What you ask stays on this " +
            "screen: nothing goes into Chat or ProxAI's memory.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun lockTimeText(ms: Long): String = when {
    ms < 60_000 -> "${ms / 1000} seconds"
    ms == 60_000L -> "1 minute"
    else -> "${ms / 60_000} minutes"
}

@Composable
private fun Unlocked(ui: DocsUi) {
    val st = ui.status ?: DocsStatus()
    var question by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(ui.answers.size, ui.asking) {
        val n = ui.answers.size + (if (ui.asking != null) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }
    fun send() {
        if (question.trim().length >= 3 && ui.asking == null && st.ready) {
            ProxDocs.ask(question)
            question = ""
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (st.server && !st.loaded) ModelBanner(st)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (ui.answers.isEmpty() && ui.asking == null) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Ask how a plugin, app or tool works, or what changed when.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        EXAMPLES.forEach { ex -> SuggestionChip(onClick = { question = ex }, label = { Text(ex) }) }
                    }
                }
            }
            items(ui.answers) { a -> AnswerCard(a) }
            ui.asking?.let { q -> item { Thinking(q, ui.askingSince) } }
        }
        ui.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(question, { question = it.take(1000) }, modifier = Modifier.weight(1f), maxLines = 4,
                placeholder = { Text("Ask about ProxAI…") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { send() }))
            IconButton(onClick = { send() }, enabled = question.trim().length >= 3 && ui.asking == null && st.ready) {
                Icon(Icons.AutoMirrored.Filled.Send, "Ask")
            }
        }
        if (st.passages > 0) {
            Text("Knows ${st.passages} passages: ProxAI's docs and code docs, ${st.commits} commits, ${st.apps} app plans",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp))
        }
    }
}

@Composable
private fun ModelBanner(st: DocsStatus) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (st.loading) "Loading ${st.model}…" else "${st.model} isn't loaded",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (st.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("About a minute. The PC is in server mode, so the GPU is ProxAI's.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(st.why.ifBlank { "proxDocs answers only while it's loaded." },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = { ProxDocs.load() }) { Text("Load the model") }
            }
        }
    }
}

@Composable
private fun Thinking(question: String, since: Long) {
    val seconds by produceState(0L, since) {
        while (true) {
            value = (ProxDocs.now() - since) / 1000
            delay(1_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QuestionBubble(question)
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("Reading ProxAI's docs… $seconds s (the local model can take a minute)",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun QuestionBubble(q: String) {
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp)) {
        Text(q, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun AnswerCard(a: DocsAnswer) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        QuestionBubble(a.question)
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectionContainer { MarkdownText(a.answer, MaterialTheme.typography.bodyMedium) }
                if (a.sources.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(if (a.sources.any { it.cited }) "Sources" else "Closest matches",
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a.sources.forEach { s ->
                        Text("[${s.n}] ${s.where}", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                if (a.seconds > 0) {
                    Text("${"%.0f".format(a.seconds)} s", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
