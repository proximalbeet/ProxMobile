package com.proxai.hermes.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.Channel
import com.proxai.hermes.data.ChatTimes
import com.proxai.hermes.data.TrashItem
import com.proxai.hermes.data.ModelRoute

/** What a channel can run on (C4): Settings' choice, a cloud model, or the PC's local model. */
private val MODEL_OPTIONS: List<Pair<String, String?>> =
    listOf<Pair<String, String?>>("Same as Settings" to null) + ModelRoute.CLOUD_CHOICES + ("Local model" to "local")

/** C4/C7: the DM and the channels; new channel; rename / model / delete; the trash. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelsSheet(onDismiss: () -> Unit) {
    val repo = ProxApp.instance.chat
    val channels by repo.channelList.collectAsState()
    val active by repo.active.collectAsState()
    val busyIn by repo.busyChannel.collectAsState()
    var creating by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Channel?>(null) }
    var deleting by remember { mutableStateOf<Channel?>(null) }
    var showTrash by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Chats", style = MaterialTheme.typography.titleLarge)
            Text("The ProxAI DM gets your briefs and PC notices. Each channel is a separate chat with its own " +
                "memory on the PC and its own model.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(8.dp))
            channels.forEach { c ->
                ChannelRow(c, selected = c.id == active, busy = busyIn == c.id,
                    onOpen = { repo.switchTo(c.id); onDismiss() },
                    onEdit = { editing = c }, onDelete = { deleting = c })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                OutlinedButton(onClick = { creating = true }) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("New channel")
                }
                TextButton(onClick = { showTrash = true }) {
                    Icon(Icons.Outlined.DeleteOutline, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Trash")
                }
            }
            Spacer(Modifier.size(12.dp))
        }
    }

    if (creating) ChannelDialog(null, onDismiss = { creating = false }) { name, model ->
        repo.createChannel(name, model)
        creating = false
        onDismiss()
    }
    editing?.let { c ->
        ChannelDialog(c, onDismiss = { editing = null }) { name, model ->
            repo.renameChannel(c.id, name)
            repo.setChannelModel(c.id, model)
            editing = null
        }
    }
    deleting?.let { c ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Delete #${c.name}?") },
            text = { Text("Its conversation goes to the Trash, so you can bring the channel back (with its memory) " +
                "until the Trash empties itself.") },
            confirmButton = { TextButton(onClick = { repo.deleteChannel(c.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
    if (showTrash) TrashSheet(onDismiss = { showTrash = false }, onRestored = { showTrash = false; onDismiss() })
}

@Composable
private fun ChannelRow(c: Channel, selected: Boolean, busy: Boolean, onOpen: () -> Unit, onEdit: () -> Unit,
                       onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (c.isDm) Icons.Outlined.ChatBubbleOutline else Icons.Outlined.Tag, null,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(if (c.isDm) "ProxAI (DM)" else c.name, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
            val sub = listOfNotNull(Channel.modelLabel(c.model), if (busy) "answering…" else null).joinToString(" · ")
            Text(sub, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!c.isDm) {   // the DM's model is Settings > Model
            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Channel options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename / model") }, onClick = { menu = false; onEdit() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

/** New channel ([existing] null) or edit one: name (not for the DM) and model. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelDialog(existing: Channel?, onDismiss: () -> Unit, onSave: (String, String?) -> Unit) {
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var model by remember { mutableStateOf(existing?.model) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New channel" else "#${existing.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it.take(Channel.MAX_NAME) },
                    label = { Text("Name, e.g. Homework or One Touch") }, singleLine = true)
                Text("Model", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MODEL_OPTIONS.forEach { (label, value) ->
                        FilterChip(selected = model == value, onClick = { model = value }, label = { Text(label) })
                    }
                }
                Text("\"Same as Settings\" follows Settings > Model (the cloud model, Sonnet 5.5 unless you picked another).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, model) }) {
                Text(if (existing == null) "Create" else "Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** C7: conversations from "New chat" and deleted channels. Restore brings back the chat and its memory. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TrashSheet(onDismiss: () -> Unit, onRestored: () -> Unit) {
    val repo = ProxApp.instance.chat
    val days = ProxApp.instance.settings.trashDays
    var version by remember { mutableIntStateOf(0) }
    val items = remember(version) { repo.trashItems() }
    var confirmEmpty by remember { mutableStateOf(false) }
    // B12 (10-29): "Delete for good" and a Restore that moves a chat away both ask first.
    var confirmDelete by remember { mutableStateOf<TrashItem?>(null) }
    var confirmRestore by remember { mutableStateOf<Pair<TrashItem, Int>?>(null) }
    val busy by repo.busy.collectAsState()
    fun restore(t: TrashItem) { if (repo.restore(t.id)) onRestored() else version++ }
    fun whereOf(t: TrashItem) = if (t.channel.isDm) "ProxAI DM" else "#${t.channel.name}"
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Trash", style = MaterialTheme.typography.titleLarge)
            Text(if (days > 0) "Deleted for good after $days days (Settings > Context and cost)." else "Kept until you delete them.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (items.isEmpty()) Text("Nothing here.", Modifier.padding(vertical = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            items.forEach { t ->
                HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(t.title, fontWeight = FontWeight.SemiBold, maxLines = 2)
                    val where = whereOf(t) + (if (!t.channel.isDm && t.channelDeleted) " (deleted channel)" else "")
                    Text("$where · ${t.count} messages · deleted ${ChatTimes.day(t.deletedAt)} ${ChatTimes.clock(t.deletedAt)}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(enabled = !busy, onClick = {
                            val moved = repo.restoreDisplaces(t)
                            if (moved > 0) confirmRestore = t to moved else restore(t)
                        }) { Text("Restore") }
                        TextButton(onClick = { confirmDelete = t }) { Text("Delete for good") }
                    }
                }
            }
            if (busy && items.isNotEmpty()) Text("Restore works once ProxAI finishes answering.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (items.isNotEmpty()) TextButton(onClick = { confirmEmpty = true }) { Text("Empty trash") }
            Spacer(Modifier.size(12.dp))
        }
    }
    if (confirmEmpty) AlertDialog(onDismissRequest = { confirmEmpty = false },
        title = { Text("Empty the trash?") },
        text = { Text("${items.size} conversations are deleted for good. This can't be undone.") },
        confirmButton = { TextButton(onClick = { repo.emptyTrash(); confirmEmpty = false; version++ }) { Text("Empty") } },
        dismissButton = { TextButton(onClick = { confirmEmpty = false }) { Text("Cancel") } })
    confirmDelete?.let { t ->
        AlertDialog(onDismissRequest = { confirmDelete = null },
            title = { Text("Delete for good?") },
            text = { Text("\"${t.title}\" (${whereOf(t)}, ${t.count} messages) is deleted for good. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = { repo.deleteFromTrash(t.id); confirmDelete = null; version++ }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } })
    }
    confirmRestore?.let { (t, moved) ->
        AlertDialog(onDismissRequest = { confirmRestore = null },
            title = { Text("Restore \"${t.title}\"?") },
            text = { Text("The chat in ${whereOf(t)} now ($moved messages) moves to the Trash first, so you can bring it back too.") },
            confirmButton = { TextButton(onClick = { confirmRestore = null; restore(t) }) { Text("Restore") } },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } })
    }
}
