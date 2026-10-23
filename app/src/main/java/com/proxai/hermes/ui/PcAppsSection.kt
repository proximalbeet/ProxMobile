package com.proxai.hermes.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.AppInstaller
import com.proxai.hermes.data.PcApp
import com.proxai.hermes.data.PcBuild
import kotlinx.coroutines.launch

private val STAGE = mapOf(
    "queued" to "Starting", "planning" to "Planning", "writing" to "Writing",
    "testing" to "Testing", "building" to "Packing the app",
)

/** "Built on your PC": apps ProxAI made as real APKs (docs/PROXAI-APPS.md). */
@Composable
fun PcAppsSection(onAskProxAI: (String) -> Unit) {
    val repo = ProxApp.instance.pcApps
    val apps by repo.apps.collectAsState()
    val error by repo.error.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var resumed by remember { mutableIntStateOf(0) }   // re-read installed versions on return
    var versionsOf by remember { mutableStateOf<PcApp?>(null) }
    var removing by remember { mutableStateOf<PcApp?>(null) }
    var working by remember { mutableStateOf<String?>(null) }

    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { resumed++; repo.watch(true) }
            if (e == Lifecycle.Event.ON_PAUSE) repo.watch(false)
        }
        owner.lifecycle.addObserver(obs)
        repo.watch(true)
        onDispose { owner.lifecycle.removeObserver(obs); repo.watch(false) }
    }

    fun install(app: PcApp, build: PcBuild) {
        working = app.id
        scope.launch {
            try {
                val file = repo.download(app, build)
                when (val r = AppInstaller.install(ctx, file)) {
                    AppInstaller.Result.NeedsPermission -> {
                        Toast.makeText(ctx, "Allow ProxAI to install apps, then tap Install again", Toast.LENGTH_LONG).show()
                        AppInstaller.openInstallSetting(ctx)
                    }
                    is AppInstaller.Result.Refused -> Toast.makeText(ctx, r.why, Toast.LENGTH_LONG).show()
                    AppInstaller.Result.Started -> Unit
                }
            } catch (e: Exception) {
                Toast.makeText(ctx, e.message ?: "Download failed", Toast.LENGTH_LONG).show()
            } finally {
                working = null
            }
        }
    }

    Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Explicit colour: this list isn't inside a Surface, so text would default to black.
        Text("Built on your PC", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        if (apps.isEmpty()) {
            Text(error ?: "Ask ProxAI for an app: it builds a real app on your PC with Claude Code, and it shows up here to install.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
        apps.forEach { app ->
            val installed = remember(app.latest?.packageName, resumed) { app.latest?.let { repo.installedCode(it.packageName) } }
            PcAppCard(app, installed, working == app.id,
                onMain = {
                    when (PcApp.action(app, installed)) {
                        "install", "update" -> install(app, app.latest!!)
                        "open" -> if (!AppInstaller.launch(ctx, app.latest!!.packageName))
                            Toast.makeText(ctx, "Couldn't open it", Toast.LENGTH_SHORT).show()
                    }
                },
                onChange = { onAskProxAI("Change my ${app.name} app (${app.id}): ") },
                onStop = { onAskProxAI("Stop building my ${app.name} app (${app.id}).") },
                onVersions = { versionsOf = app },
                onRemove = { removing = app })
        }
        if (apps.isNotEmpty() && error != null) {
            Text(error!!, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 4.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text("On this phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(start = 4.dp))
    }

    removing?.let { app ->
        val installed = app.latest?.packageName?.let { repo.installedCode(it) } != null
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Remove ${app.name}?") },
            text = {
                Text((if (installed) "Uninstall it from this phone, and/or delete it from the PC. " else "") +
                    "Deleting from the PC removes it from this list; ProxAI asks you to confirm, and its files go to " +
                    "a trash folder on the PC, so it can be restored.")
            },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    onAskProxAI("Delete my ${app.name} app (${app.id}) from the PC.")
                }) { Text("Delete from PC") }
            },
            dismissButton = {
                Row {
                    if (installed) TextButton(onClick = {
                        removing = null
                        AppInstaller.uninstall(ctx, app.latest!!.packageName)
                    }) { Text("Uninstall") }
                    TextButton(onClick = { removing = null }) { Text("Cancel") }
                }
            })
    }

    versionsOf?.let { app -> VersionsDialog(app, onDismiss = { versionsOf = null }, onRollback = { b ->
        versionsOf = null
        onAskProxAI("Roll back my ${app.name} app (${app.id}) to build ${b.code} (version ${b.version}).")
    }) }
}

@Composable
private fun PcAppCard(app: PcApp, installed: Long?, working: Boolean, onMain: () -> Unit, onChange: () -> Unit,
                      onStop: () -> Unit, onVersions: () -> Unit, onRemove: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(app.name, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (app.description.isNotBlank()) {
                    Text(app.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                when {
                    app.busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(6.dp))
                        Text("${STAGE[app.state] ?: app.state}…" + (app.message.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    app.failed -> Text("Build failed: ${app.message}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    app.latest != null -> Text(
                        "v${app.latest.version} · build ${app.latest.code}" +
                            (installed?.let { if (it < app.latest.code) " · installed: build $it" else " · installed" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            val action = PcApp.action(app, installed)
            if (working) CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp))
            else if (app.busy) OutlinedButton(onClick = onStop,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) { Text("Stop") }
            else if (action != "none") Button(onClick = onMain, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
                Text(when (action) { "install" -> "Install"; "update" -> "Update"; else -> "Open" })
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    if (app.busy) DropdownMenuItem(text = { Text("Stop the build") }, onClick = { menu = false; onStop() })
                    DropdownMenuItem(text = { Text("Change with ProxAI") }, onClick = { menu = false; onChange() })
                    DropdownMenuItem(text = { Text("Versions") }, onClick = { menu = false; onVersions() })
                    DropdownMenuItem(text = { Text("Remove…") }, onClick = { menu = false; onRemove() })
                }
            }
        }
    }
}

@Composable
private fun VersionsDialog(app: PcApp, onDismiss: () -> Unit, onRollback: (PcBuild) -> Unit) {
    val repo = ProxApp.instance.pcApps
    var builds by remember { mutableStateOf<List<PcBuild>?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(app.id) {
        runCatching { repo.builds(app.id) }.onSuccess { builds = it.reversed() }.onFailure { failed = it.message }
    }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("${app.name}: versions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when {
                    failed != null -> Text("Couldn't load: $failed")
                    builds == null -> CircularProgressIndicator(Modifier.size(24.dp))
                    else -> builds!!.forEach { b ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Build ${b.code} · v${b.version}", fontWeight = FontWeight.Medium)
                                Text(b.note.ifBlank { b.time.replace('T', ' ') }, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (b.code != app.latest?.code) TextButton(onClick = { onRollback(b) }) { Text("Roll back") }
                            else Text("latest", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                Text("Rolling back makes a new build with the old version's files, so it installs as an update.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } })
}

