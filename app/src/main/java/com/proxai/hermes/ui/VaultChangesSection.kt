package com.proxai.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.VaultChange
import com.proxai.hermes.data.VaultYesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * VAULT-UNDO-APP (0.9.18): the vault changes Nathan approved in the last week, each with its Undo when the PC says
 * it can be undone. Entries sharing an id are listed and marked, with no Undo. 409 and 503 are said plainly.
 */
@Composable
fun VaultChangesSection() {
    val s = ProxApp.instance.settings
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<VaultChange>?>(null) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val results = remember { mutableStateMapOf<String, String>() }
    val done = remember { mutableStateMapOf<String, Boolean>() }

    Text("Approved vault changes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    Text("Every change to your notes you approved (chat, call or terminal), with Undo. Uses the Prox Dump sync and " +
        "Owner tokens above. New ones also come as notifications.", style = MaterialTheme.typography.bodySmall)
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val since = Instant.now().minus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS).toString()
            val a = withContext(Dispatchers.IO) { VaultYesClient.from(s).written(since) }
            items = a.written?.items?.reversed()       // newest first
            status = a.message
            busy = false
        }
    }) { Text(if (busy) "Checking…" else "Show last week's changes") }
    if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    items?.forEach { c ->
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(c.line, style = MaterialTheme.typography.bodyMedium)
            if (c.time.isNotEmpty()) Text(c.time, style = MaterialTheme.typography.labelSmall)
            if (c.mark.isNotEmpty()) Text(c.mark, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
            results[c.key]?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (c.undoable && done[c.key] != true) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    results[c.key] = "Undoing…"
                    scope.launch {
                        val a = withContext(Dispatchers.IO) { VaultYesClient.from(s).undo(c) }
                        results[c.key] = a.message
                        if (a.ok || a.status == 409) done[c.key] = true
                        if (a.ok) s.vaultYesPending = s.vaultYesPending.filterNot { it.key == c.key }
                    }
                }) { Text("Undo") }
            }
        }
    }
}
