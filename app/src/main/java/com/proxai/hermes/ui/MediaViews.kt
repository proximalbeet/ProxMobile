package com.proxai.hermes.ui

import android.content.Intent
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.ChatMedia
import kotlinx.coroutines.launch

/** C6: a picture in a message (from the PC's outbox, inline, or attached on the phone). Tap = full screen. */
@Composable
fun ChatImage(src: String) {
    var bitmap by remember(src) { mutableStateOf<Bitmap?>(null) }
    var error by remember(src) { mutableStateOf<String?>(null) }
    var full by remember { mutableStateOf(false) }
    LaunchedEffect(src) {
        runCatching { ProxApp.instance.media.bitmap(src) }
            .onSuccess { bitmap = it }.onFailure { error = it.message ?: "Can't show this picture" }
    }
    val shape = RoundedCornerShape(12.dp)
    when {
        bitmap != null -> Image(bitmap!!.asImageBitmap(), contentDescription = "Picture from ProxAI",
            modifier = Modifier.widthIn(max = 312.dp).heightIn(max = 360.dp).clip(shape).clickable { full = true },
            contentScale = ContentScale.Fit)
        error != null -> Text("🖼 ${error}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> Box(Modifier.size(width = 220.dp, height = 140.dp).clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        }
    }
    if (full && bitmap != null) {
        Dialog(onDismissRequest = { full = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { full = false }, contentAlignment = Alignment.Center) {
                Image(bitmap!!.asImageBitmap(), contentDescription = "Picture from ProxAI", modifier = Modifier.fillMaxWidth(),
                    contentScale = ContentScale.Fit)
            }
        }
    }
}

/** C6: a PDF ProxAI made (MEDIA:/output/<file>.pdf): downloaded once, opened in the phone's PDF viewer. */
@Composable
fun ChatFile(path: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(12.dp),
        modifier = Modifier.clickable(enabled = !loading) {
            loading = true
            scope.launch {
                runCatching {
                    val f = ProxApp.instance.media.file(path)
                    val uri = FileProvider.getUriForFile(ctx, "com.proxai.hermes.files", f)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure {
                    Toast.makeText(ctx, if (it is android.content.ActivityNotFoundException) "No PDF viewer on this phone"
                        else it.message ?: "Can't open it", Toast.LENGTH_LONG).show()
                }
                loading = false
            }
        }) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            else Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(10.dp))
            Text(ChatMedia.fileName(path), fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Text("Open", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** C5: a small preview of an attachment (a picture, a PDF's first page, or a file icon) with its name. */
@Composable
fun AttachmentThumb(a: com.proxai.hermes.data.Attachment, size: androidx.compose.ui.unit.Dp, onRemove: (() -> Unit)? = null) {
    var bitmap by remember(a) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(a) {
        if (a.kind != com.proxai.hermes.data.Attachment.TEXT) {
            runCatching { ProxApp.instance.media.bitmap("file:" + a.files.first(), 512) }.onSuccess { bitmap = it }
        }
    }
    val label = when (a.kind) {
        com.proxai.hermes.data.Attachment.PDF -> "${a.name} · ${a.pages} page${if (a.pages == 1) "" else "s"}"
        else -> a.name
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHighest, shape = RoundedCornerShape(10.dp)) {
        androidx.compose.foundation.layout.Column(Modifier.width(size).padding(4.dp)) {
            Box(Modifier.size(size - 8.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                if (bitmap != null) Image(bitmap!!.asImageBitmap(), contentDescription = a.name, contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize())
                else Icon(Icons.Outlined.Description, null, tint = MaterialTheme.colorScheme.primary)
                if (onRemove != null) {
                    Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(22.dp).clip(RoundedCornerShape(11.dp))
                        .background(Color(0xAA000000)).clickable(onClick = onRemove), contentAlignment = Alignment.Center) {
                        Text("✕", color = Color.White, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        }
    }
}
