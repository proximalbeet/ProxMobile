package com.proxai.hermes.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.ListenerCursor
import com.proxai.hermes.data.NoticeSync
import com.proxai.hermes.data.PcPresenceFeed
import com.proxai.hermes.data.PcStatsClient
import com.proxai.hermes.data.Settings
import com.proxai.hermes.data.WorkbenchClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * One quiet connection to the PC (0.9.9): PC notices and Workbench logins pop up within a second or two.
 *
 * B19 (11-02): PC notices used to come only from Android's background check, at most every 15 minutes, so a
 * "class in 9 min" heads-up posted at 12:50 reached the phone at 13:03. Now a long poll waits on the apps
 * server (`/v1/pc/notices?wait=`), which answers the moment a notice lands; the 15-minute check stays as a
 * fallback. Workbench logins (0.9.4, this service was WorkbenchListener) keep their own long poll here.
 *
 * A foreground service, because Android only lets an app keep a connection open in the background that way;
 * its notification sits in the quiet "Connected to your PC" channel. It runs while Settings > Notifications >
 * Live PC notices is on, or Workbench logins are paired and on.
 */
class PcListener : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workbench: Job? = null
    private var notices: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val s = ProxApp.instance.settings
        val wb = wantsWorkbench(s)
        val pc = wantsNotices(s)
        // Android needs startForeground() after startForegroundService(), even when there's nothing to do.
        ServiceCompat.startForeground(this, Notifier.LISTEN_ID, Notifier.pcListening(this, wb, pc),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0)
        if (!wb && !pc) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (wb && workbench?.isActive != true) workbench = scope.launch { listenWorkbench(); stopIfIdle() }
        if (pc && notices?.isActive != true) notices = scope.launch { listenNotices(); stopIfIdle() }
        return START_STICKY
    }

    override fun onDestroy() {
        PcPresenceFeed.lost()
        scope.cancel()
        super.onDestroy()
    }

    private fun stopIfIdle() {
        if (workbench?.isActive != true && notices?.isActive != true) stopSelf()
    }

    private suspend fun listenNotices() {
        var backoff = MIN_BACKOFF
        while (currentCoroutineContext().isActive) {
            val settings = ProxApp.instance.settings
            if (!wantsNotices(settings)) { PcPresenceFeed.lost(); return }
            val began = System.currentTimeMillis()
            try {
                val fresh = PcStatsClient(settings).waitForNotices(settings.lastPcNoticeId, WAIT_SECONDS)
                // WAKE-PHONE: the PC's "desk" / "away" word ("Hey Prox" stands down at the desk).
                PcPresenceFeed.seen(fresh)
                // One path for every notice (dedupe, the B5 switches, Chat): the same pull the 15-minute check does.
                if (fresh.isNotEmpty()) NoticeSync.pull(applicationContext)
                backoff = MIN_BACKOFF
                val took = System.currentTimeMillis() - began
                when {
                    // An older PC answers at once instead of waiting: look every 30 s instead of spinning.
                    fresh.isEmpty() && took < 2_000 -> delay(OLD_SERVER_PAUSE)
                    took < 1_000 -> delay(1_000)   // never spin
                }
            } catch (e: IOException) {
                PcPresenceFeed.lost()   // nobody says "desk" now: unknown counts as away
                delay(backoff)   // the PC is off, or the phone is off the tailnet: try again a bit later
                backoff = minOf(backoff * 2, MAX_BACKOFF)
            }
        }
    }

    private suspend fun listenWorkbench() {
        var cursor = ListenerCursor()
        var backoff = MIN_BACKOFF
        while (currentCoroutineContext().isActive) {
            val settings = ProxApp.instance.settings
            val client = WorkbenchClient.from(settings)
            if (!wantsWorkbench(settings)) return
            val began = System.currentTimeMillis()
            try {
                val answer = client.waitForLogins(cursor.after, cursor.epoch)
                val (fresh, next) = cursor.next(answer)
                // On screen, the prompt shows by itself (MainActivity checks every few seconds): no buzz on top.
                if (!ProxApp.instance.inForeground) fresh.forEach { Notifier.workbenchLogin(this, it) }
                Notifier.keepWorkbenchLogins(this, answer.logins.map { it.id })
                cursor = next
                backoff = MIN_BACKOFF
                if (fresh.isEmpty() && System.currentTimeMillis() - began < 1_000) delay(1_000)   // never spin
            } catch (e: IOException) {
                delay(backoff)
                backoff = minOf(backoff * 2, MAX_BACKOFF)
            }
        }
    }

    companion object {
        private const val MIN_BACKOFF = 5_000L
        private const val MAX_BACKOFF = 60_000L
        private const val OLD_SERVER_PAUSE = 30_000L
        const val WAIT_SECONDS = 45

        fun wantsWorkbench(s: Settings) = WorkbenchClient.from(s).configured && s.workbenchListen
        fun wantsNotices(s: Settings) = s.isConfigured && s.pcNotices && s.pcLive

        /**
         * Starts or stops the listener to match Settings. Safe from anywhere: a start Android refuses (in the
         * background, Android 12+) happens the next time ProxAI is opened, or after a reboot or an update.
         */
        fun sync(context: Context) {
            val settings = ProxApp.instance.settings
            val intent = Intent(context, PcListener::class.java)
            if (wantsWorkbench(settings) || wantsNotices(settings)) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException or IllegalStateException: not allowed right now
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
