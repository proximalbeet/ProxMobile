package com.proxai.hermes.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.PcStatsProvider
import com.proxai.hermes.data.ProxDocs

/**
 * P4 bridge (0.9.8): ProxAI.docs.unlock() in a PC-built app opens this. It's ProxAI's own prompt, so the
 * proxDocs password (and the session's key) never reach the app's code: the app lock first if ProxAI is
 * locked, then the proxDocs password, the same view as the Docs tab. The app only hears back "unlocked"
 * (RESULT_OK) or not. It opens only for a ProxAI-built app, signed with the pinned key, that declared the
 * "docs" feature (the same check as PcStatsProvider); anything else gets RESULT_CANCELED at once.
 */
class DocsUnlockActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        val caller = callingPackage
        if (!ProxApp.instance.settings.isConfigured || PcStatsProvider.callerRefusal(this, caller, "docs") != null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        if (ProxDocs.unlocked) {
            setResult(RESULT_OK)
            finish()
            return
        }
        val appName = caller?.let { pkg ->
            runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
        } ?: "this app"
        ProxDocs.refresh()
        setContent {
            ProxTheme {
                val locked by ProxApp.instance.lock.locked.collectAsState()
                val ui by ProxDocs.ui.collectAsState()
                LaunchedEffect(ui.unlocked) {
                    if (ui.unlocked) {
                        setResult(RESULT_OK)
                        finish()
                    }
                }
                if (locked) {
                    LockScreen()
                } else {
                    Box(Modifier.fillMaxSize().padding(16.dp).imePadding(), contentAlignment = Alignment.Center) {
                        Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(0.8f)) {
                            Column {
                                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically) {
                                    Text("Unlock proxDocs for $appName", style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 2)
                                    TextButton(onClick = {
                                        setResult(RESULT_CANCELED)
                                        finish()
                                    }) { Text("Cancel") }
                                }
                                Box(Modifier.weight(1f)) { DocsLocked(ui) }
                            }
                        }
                    }
                }
            }
        }
    }
}
