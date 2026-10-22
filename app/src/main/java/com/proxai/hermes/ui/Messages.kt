package com.proxai.hermes.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.AppStore
import com.proxai.hermes.data.ChatMessage

sealed interface Seg {
    data class Text(val s: String) : Seg
    data class Code(val lang: String, val body: String, val closed: Boolean) : Seg
}

/** Splits markdown into prose and fenced code blocks (handles a block still streaming in). */
fun segments(text: String): List<Seg> {
    val out = mutableListOf<Seg>()
    var i = 0
    while (i < text.length) {
        val start = text.indexOf("```", i)
        if (start < 0) { out += Seg.Text(text.substring(i)); break }
        if (start > i) out += Seg.Text(text.substring(i, start))
        val nl = text.indexOf('\n', start)
        if (nl < 0) { out += Seg.Code(text.substring(start + 3).trim(), "", false); break }
        val lang = text.substring(start + 3, nl).trim()
        val end = text.indexOf("```", nl + 1)
        if (end < 0) { out += Seg.Code(lang, text.substring(nl + 1), false); break }
        out += Seg.Code(lang, text.substring(nl + 1, end).trimEnd(), true)
        i = end + 3
    }
    return out.filterNot { it is Seg.Text && it.s.isBlank() }
}

/** Minimal inline markdown: **bold**, `code`, and # headings. */
@Composable
fun MarkdownText(s: String) {
    val styled = buildAnnotatedString {
        s.trim('\n').lines().forEachIndexed { li, rawLine ->
            if (li > 0) append('\n')
            var line = rawLine
            val heading = Regex("^#{1,6}\\s+").find(line)
            if (heading != null) line = line.substring(heading.range.last + 1)
            if (line.startsWith("- ") || line.startsWith("* ")) line = "•  " + line.substring(2)
            val tokens = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)").split(line).let { parts ->
                val matches = Regex("(\\*\\*[^*]+\\*\\*|`[^`]+`)").findAll(line).map { it.value }.toList()
                parts.flatMapIndexed { idx, p -> listOfNotNull(p, matches.getOrNull(idx)) }
            }
            if (heading != null) pushStyle(SpanStyle(fontWeight = FontWeight.Bold, fontSize = 17.sp))
            tokens.forEach { t ->
                when {
                    t.startsWith("**") && t.endsWith("**") && t.length > 4 ->
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(t.substring(2, t.length - 2)) }
                    t.startsWith("`") && t.endsWith("`") && t.length > 2 ->
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = androidx.compose.ui.graphics.Color(0x33FFFFFF))) {
                            append(t.substring(1, t.length - 1))
                        }
                    else -> append(t)
                }
            }
            if (heading != null) pop()
        }
    }
    Text(styled, style = MaterialTheme.typography.bodyLarge)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    msg: ChatMessage,
    onOpenApp: (String) -> Unit,
    onRemixApp: (String) -> Unit,
) {
    if (msg.role == "note") { NoteCard(msg); return }
    val isUser = msg.role == "user"
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(
                topStart = 20.dp, topEnd = 20.dp,
                bottomStart = if (isUser) 20.dp else 6.dp, bottomEnd = if (isUser) 6.dp else 20.dp),
            modifier = Modifier.widthIn(max = 340.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (msg.tools.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        msg.tools.takeLast(8).forEach { ToolPill(it.emoji, it.label, it.running) }
                    }
                }
                val segs = segments(msg.text)
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        segs.forEach { seg ->
                            when (seg) {
                                is Seg.Text -> MarkdownText(seg.s)
                                is Seg.Code -> {
                                    val isApp = seg.lang.startsWith("htm", true) ||
                                        seg.body.take(200).contains("<!doctype html", true)
                                    if (isApp) AppCard(msg, seg, onOpenApp, onRemixApp) else CodeBlock(seg)
                                }
                            }
                        }
                    }
                }
                if (msg.streaming && (msg.text.isEmpty() || msg.thinking)) WorkingLine(msg)
                msg.error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                msg.reminders.forEach { r ->
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(12.dp)) {
                        Text("⏰ $r", Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onTertiaryContainer)
                    }
                }
                if (!isUser && !msg.streaming && msg.model != null) {
                    val tokens = if (msg.promptTokens > 0) " · ${formatTokens(msg.promptTokens)} in" else ""
                    Text("${msg.model}$tokens", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
                }
            }
        }
    }
}

/** Dots plus what Hermes is doing, so a pause doesn't look like a freeze. */
@Composable
fun WorkingLine(msg: ChatMessage) {
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(msg.id) {
        while (true) { kotlinx.coroutines.delay(2_000); now = System.currentTimeMillis() }
    }
    val quiet = if (msg.lastEventAt > 0) (now - msg.lastEventAt) / 1000 else 0
    val label = when {
        quiet >= 20 -> "still working… (${quiet}s, the PC may be busy)"
        msg.thinking -> "thinking…"
        msg.tools.any { it.running } -> "using tools…"
        else -> ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        TypingDots()
        if (label.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** App notices in the chat, e.g. "conversation compacted" with the summary. */
@Composable
fun NoteCard(msg: ChatMessage) {
    var open by remember { mutableStateOf(false) }
    val first = msg.text.substringBefore("\n\n")
    val rest = msg.text.substringAfter("\n\n", "")
    Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f), shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(first, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (msg.streaming) TypingDots()
            if (rest.isNotBlank()) {
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide summary" else "Show summary") }
                if (open) SelectionContainer { Text(rest, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
}

fun formatTokens(n: Number): String {
    val v = n.toLong()
    return if (v >= 1000) "${(v + 500) / 1000}k tokens" else "$v tokens"
}

@Composable
fun ToolPill(emoji: String, label: String, running: Boolean) {
    val alpha = if (running) {
        val t = rememberInfiniteTransition(label = "tool")
        t.animateFloat(0.45f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value
    } else 1f
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(50)) {
        Text("${emoji.ifBlank { "🛠" }} ${label.take(48)}",
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp).alpha(alpha),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
fun TypingDots() {
    val t = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(vertical = 6.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse), label = "d$i")
            Box(Modifier.size(8.dp).alpha(a).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
        }
    }
}

@Composable
fun CodeBlock(seg: Seg.Code) {
    val ctx = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.background, shape = RoundedCornerShape(12.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(seg.lang.ifBlank { "code" }, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                IconButton(onClick = { copy(ctx, seg.body) }) {
                    Icon(Icons.Outlined.ContentCopy, "Copy", Modifier.size(16.dp))
                }
            }
            Text(seg.body, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp,
                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp))
        }
    }
}

/** Shown instead of a wall of HTML: the generated app with Open / Remix buttons. */
@Composable
fun AppCard(msg: ChatMessage, seg: Seg.Code, onOpenApp: (String) -> Unit, onRemixApp: (String) -> Unit) {
    var showCode by remember { mutableStateOf(false) }
    val app = msg.appId?.let { ProxApp.instance.apps.get(it) }
    val title = app?.title ?: AppStore.extractTitle(seg.body) ?: "New app"
    val lines = seg.body.count { it == '\n' } + 1
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIconBadge(title, 44.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(
                        when {
                            !seg.closed -> "Building… $lines lines"
                            app != null -> "Saved to Apps · v${app.version}"
                            else -> "$lines lines of HTML"
                        },
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (app != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { onOpenApp(app.id) }) {
                        Icon(Icons.Outlined.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Open")
                    }
                    TextButton(onClick = { onRemixApp(app.id) }) {
                        Icon(Icons.Outlined.AutoFixHigh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Remix")
                    }
                    TextButton(onClick = { showCode = !showCode }) { Text(if (showCode) "Hide code" else "Code") }
                }
            }
            if (showCode) CodeBlock(seg)
        }
    }
}

/** Colored letter tile used for generated apps (also used to draw their launcher icons). */
@Composable
fun AppIconBadge(title: String, size: androidx.compose.ui.unit.Dp) {
    val color = androidx.compose.ui.graphics.Color(appColor(title))
    Box(Modifier.size(size).clip(RoundedCornerShape(size / 3.6f)).background(color), contentAlignment = Alignment.Center) {
        val letter = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•"
        if (letter == "•") Icon(Icons.Outlined.Widgets, null)
        else Text(letter, fontWeight = FontWeight.Bold, fontSize = (size.value * 0.45f).sp,
            color = androidx.compose.ui.graphics.Color.White)
    }
}

private val palette = longArrayOf(0xFF7C5CFF, 0xFF00A88F, 0xFFE0567A, 0xFFF29A2E, 0xFF3B82F6, 0xFF9B59B6, 0xFF14B8A6, 0xFFEF4444)
fun appColor(seed: String): Long = palette[Math.floorMod(seed.hashCode(), palette.size)]

fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(ClipboardManager::class.java)
    cm.setPrimaryClip(ClipData.newPlainText("code", text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}
