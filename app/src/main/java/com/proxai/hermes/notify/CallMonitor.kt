package com.proxai.hermes.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.CallWatcher
import com.proxai.hermes.data.LineClient
import com.proxai.hermes.data.PhoneCalls
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * ProxAI line (0.9.9): listens to the phone's call state while Settings > Security > "No PIN while unlocked"
 * is on, the line token is set and the Phone permission is granted (Android 12+). [CallWatcher] decides; this
 * only connects it to Android, and tells the PC off the main thread.
 * WAKE-PHONE: while it listens, the call state also goes to [PhoneCalls] ("Hey Prox" stops during calls; the
 * listener also reads the audio mode, which needs no permission).
 */
object CallMonitor {
    private var callback: Any? = null
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun canWatch(context: Context): Boolean = Build.VERSION.SDK_INT >= 31 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED

    /** Starts or stops listening to match Settings. Safe from anywhere, any number of times. */
    @Synchronized
    fun sync(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val app = ProxApp.instance
        val s = app.settings
        val tm = context.getSystemService(TelephonyManager::class.java) ?: return
        val lineOn = { s.lineNoPin && s.lineToken.isNotBlank() }
        // WAKE-PHONE: also while "Hey Prox" is on (only the call state goes there; the lock hold stays the line's).
        val want = (lineOn() || com.proxai.hermes.data.WakeSettings(context).enabled) && canWatch(context)
        if (want && callback == null) {
            val watcher = CallWatcher(app.lock) { event -> io.launch { LineClient.from(s).tell(event) } }
            callback = register(context, tm, watcher, lineOn)
        } else if (!want && callback != null) {
            unregister(tm, callback)
            callback = null
            PhoneCalls.clear()
        }
    }

    @RequiresApi(31)
    private fun register(context: Context, tm: TelephonyManager, watcher: CallWatcher, lineOn: () -> Boolean): Any? = try {
        val cb = CallState(watcher, lineOn)
        tm.registerTelephonyCallback(context.mainExecutor, cb)
        cb
    } catch (e: SecurityException) {
        null   // the permission was taken back: nothing is held, the PIN is asked as usual
    }

    @RequiresApi(31)
    private fun unregister(tm: TelephonyManager, cb: Any?) {
        (cb as? TelephonyCallback)?.let { runCatching { tm.unregisterTelephonyCallback(it) } }
    }

    @RequiresApi(31)
    private class CallState(private val watcher: CallWatcher, private val lineOn: () -> Boolean) :
        TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            PhoneCalls.onState(state)
            if (lineOn()) watcher.onState(state)
        }
    }
}
