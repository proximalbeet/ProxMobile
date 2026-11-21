package com.proxai.hermes.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.proxai.hermes.data.Approval

/**
 * F1 (0.9.9): what the PC is waiting on, with Reject and Accept, in Chat and in the power-button assistant.
 * Accept sends "yes" and Reject sends "no" as his next message (with a note that it came from here), so the PC
 * decides exactly as when he types it. Back closes it without an answer; he can still type one.
 */
@Composable
fun ApprovalDialog(a: Approval, onAnswer: (accept: Boolean) -> Unit, onClose: () -> Unit, note: String? = null) {
    AlertDialog(
        onDismissRequest = onClose,
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
        title = { Text("${a.title}: your OK?") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // WAKE-PHONE round 2: after "Hey Prox", voice met this question: it needs his tap.
                if (note != null) Text(note, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                Text(a.question, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                if (a.detail.isNotBlank()) {
                    Text(a.detail, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("Nothing happens until you choose.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { Button(onClick = { onAnswer(true) }) { Text("Accept") } },
        dismissButton = { OutlinedButton(onClick = { onAnswer(false) }) { Text("Reject") } },
    )
}
