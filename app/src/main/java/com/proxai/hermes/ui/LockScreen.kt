package com.proxai.hermes.ui

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.withStateAtLeast
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.PinAttempts
import com.proxai.hermes.data.PinCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * S1: shown instead of the app, the power-button assistant and in-app HTML apps while ProxAI is
 * locked. Asks for a fingerprint or face right away; the app PIN is the fallback.
 */
@Composable
fun LockScreen(onUnlocked: () -> Unit = {}, onPrompt: (Boolean) -> Unit = {}) {
    val app = ProxApp.instance
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val canBio = remember { Biometrics.available(ctx) }
    var usePin by remember { mutableStateOf(!canBio) }
    var pin by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }

    fun unlocked() {
        PinAttempts.reset(app.settings)
        app.lock.unlock()
        onUnlocked()
    }

    // onPrompt: the system's fingerprint sheet is up (true) or gone (false); the power-button overlay
    // mustn't close itself meanwhile (it ends when it stops being on screen).
    fun askBiometric() {
        onPrompt(true)
        Biometrics.prompt(ctx, onOk = { onPrompt(false); unlocked() }, onUsePin = { onPrompt(false); usePin = true },
            onError = { onPrompt(false); message = it; usePin = true })
    }

    fun submit() {
        if (checking || pin.length < 4) return
        checking = true
        val tried = pin
        scope.launch {
            val r = withContext(Dispatchers.Default) { PinAttempts.attempt(app.settings, tried) }
            checking = false
            pin = ""
            when (r) {
                PinAttempts.Result.Ok -> unlocked()
                is PinAttempts.Result.Wrong -> message =
                    if (r.waitMs > 0) "Wrong PIN. Too many tries: wait ${seconds(r.waitMs)}."
                    else "Wrong PIN. ${r.triesLeft} more ${if (r.triesLeft == 1) "try" else "tries"} before a wait."
                is PinAttempts.Result.Wait -> message = "Too many tries. Try again in ${seconds(r.ms)}."
            }
        }
    }

    // Ask for the fingerprint as soon as the screen is really up (the prompt needs a resumed screen).
    LaunchedEffect(Unit) {
        if (canBio) lifecycle.withStateAtLeast(Lifecycle.State.RESUMED) { askBiometric() }
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically)) {
            ProxLogo(72.dp)
            Text("ProxAI is locked", style = MaterialTheme.typography.headlineSmall)
            Text(if (canBio) "Unlock with your fingerprint or face, or your app PIN." else "Enter your app PIN to unlock.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center)
            if (canBio) Button(onClick = ::askBiometric) {
                Icon(Icons.Outlined.Fingerprint, null)
                Spacer(Modifier.size(8.dp))
                Text("Unlock")
            }
            if (usePin) {
                OutlinedTextField(pin, { v -> pin = v.filter(Char::isDigit).take(8) },
                    Modifier.widthIn(max = 280.dp).fillMaxWidth(),
                    label = { Text("App PIN") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }))
                TextButton(enabled = pin.length >= 4 && !checking, onClick = ::submit) { Text(if (checking) "Checking…" else "Unlock with PIN") }
            } else {
                TextButton(onClick = { usePin = true }) { Text("Use PIN") }
            }
            message?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

private fun seconds(ms: Long): String {
    val s = (ms + 999) / 1000
    return if (s < 120) "$s s" else "${(s + 59) / 60} min"
}

/** S1: choose (or change) the app PIN. [onSaved] runs once its hash is stored. */
@Composable
fun SetPinDialog(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val s = ProxApp.instance.settings
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val ok = PinCode.valid(first) && first == again
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text("Choose an app PIN") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("4 to 8 digits. It unlocks ProxAI when your fingerprint or face can't.",
                    style = MaterialTheme.typography.bodyMedium)
                listOf("PIN" to first, "Same PIN again" to again).forEachIndexed { i, (label, value) ->
                    OutlinedTextField(value, { v -> val d = v.filter(Char::isDigit).take(8); if (i == 0) first = d else again = d },
                        label = { Text(label) }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                }
                if (again.isNotEmpty() && first != again) {
                    Text("The two PINs are different.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = ok && !saving, onClick = {
                saving = true
                val pin = first
                scope.launch {
                    val hash = withContext(Dispatchers.Default) { PinCode.hash(pin) }
                    s.pinHash = hash
                    PinAttempts.reset(s)
                    saving = false
                    onSaved()
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

/** The phone's fingerprint/face prompt (Android's own BiometricPrompt; no extra library). */
object Biometrics {
    private fun authenticators() = BiometricManager.Authenticators.BIOMETRIC_WEAK

    fun available(ctx: Context): Boolean {
        val bm = ctx.getSystemService(BiometricManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= 30) bm.canAuthenticate(authenticators()) == BiometricManager.BIOMETRIC_SUCCESS
        else @Suppress("DEPRECATION") (bm.canAuthenticate() == BiometricManager.BIOMETRIC_SUCCESS)
    }

    fun prompt(ctx: Context, onOk: () -> Unit, onUsePin: () -> Unit, onError: (String) -> Unit) {
        val main = ctx.mainExecutor
        val builder = BiometricPrompt.Builder(ctx)
            .setTitle("Unlock ProxAI")
            .setSubtitle("Fingerprint or face")
            .setConfirmationRequired(false)
            .setNegativeButton("Use PIN", main) { _, _ -> onUsePin() }
        if (Build.VERSION.SDK_INT >= 30) builder.setAllowedAuthenticators(authenticators())
        runCatching {
            builder.build().authenticate(CancellationSignal(), main, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    when (code) {
                        // "Use PIN" itself goes to the negative button's listener above.
                        BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED, BiometricPrompt.BIOMETRIC_ERROR_CANCELED -> onUsePin()
                        else -> onError(msg.toString())
                    }
                }
            })
        }.onFailure { onError("Fingerprint isn't available right now. Use your PIN.") }
    }
}
