package com.proxai.hermes.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.GeneratedApp
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppsScreen(onOpenApp: (String) -> Unit, onRemixApp: (String) -> Unit, onBuildNew: (String) -> Unit) {
    val store = ProxApp.instance.apps
    val apps by store.apps.collectAsState()
    val ctx = LocalContext.current
    var renaming by remember { mutableStateOf<GeneratedApp?>(null) }
    var deleting by remember { mutableStateOf<GeneratedApp?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Your apps", fontWeight = FontWeight.SemiBold) })
            if (apps.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No apps yet", style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Ask Hermes to build one — e.g. “make me a habit tracker”. It's saved here automatically, and you can pin it to your home screen.",
                        textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(16.dp))
                    APP_IDEAS.drop(2).take(3).forEach { idea ->
                        TextButton(onClick = { onBuildNew(idea) }) { Text(idea) }
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(apps, key = { it.id }) { a ->
                        AppRow(a,
                            onOpen = { onOpenApp(a.id) },
                            onRemix = { onRemixApp(a.id) },
                            onPin = { pinToHome(ctx, a) },
                            onRename = { renaming = a },
                            onDelete = { deleting = a })
                    }
                }
            }
        }
        ExtendedFloatingActionButton(
            onClick = { onBuildNew("Build me an app that ") },
            icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("New app") },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
    }

    renaming?.let { a ->
        var name by remember(a.id) { mutableStateOf(a.title) }
        AlertDialog(onDismissRequest = { renaming = null },
            title = { Text("Rename app") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { store.rename(a.id, name.trim().ifEmpty { a.title }); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    deleting?.let { a ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text("Delete “${a.title}”?") },
            text = { Text("This removes the app and its saved versions from this phone.") },
            confirmButton = { TextButton(onClick = { store.delete(a.id); deleting = null }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } })
    }
}

@Composable
private fun AppRow(a: GeneratedApp, onOpen: () -> Unit, onRemix: () -> Unit, onPin: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AppIconBadge(a.title, 52.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(a.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (a.description.isNotBlank()) {
                    Text(a.description, style = MaterialTheme.typography.bodyMedium, maxLines = 2,
                        overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("v${a.version} · " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.updated)),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menu = false; onOpen() })
                    DropdownMenuItem(text = { Text("Remix with Hermes") }, onClick = { menu = false; onRemix() })
                    DropdownMenuItem(text = { Text("Add to home screen") }, onClick = { menu = false; onPin() })
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

/** Pins a generated app to the launcher as its own icon. */
fun pinToHome(ctx: Context, a: GeneratedApp) {
    if (!ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) {
        Toast.makeText(ctx, "Your launcher doesn't support pinned shortcuts", Toast.LENGTH_LONG).show()
        return
    }
    val intent = AppRunnerActivity.intent(ctx, a.id).setAction(Intent.ACTION_VIEW)
    val info = ShortcutInfoCompat.Builder(ctx, "app-${a.id}")
        .setShortLabel(a.title.take(24))
        .setLongLabel(a.title)
        .setIcon(IconCompat.createWithAdaptiveBitmap(iconBitmap(a.title)))
        .setIntent(intent)
        .build()
    ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
}

private fun iconBitmap(title: String): Bitmap {
    val size = 432 // adaptive icon: 108dp @ 4x; content lives in the middle 66%
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(appColor(title).toInt())
    val letter = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "★"
    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        textSize = size * 0.34f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    val y = size / 2f - (p.descent() + p.ascent()) / 2f
    c.drawText(letter, size / 2f, y, p)
    return bmp
}
