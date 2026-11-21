package com.proxai.hermes.notify

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.AppResearch
import com.proxai.hermes.data.PcFiles
import com.proxai.hermes.data.PhoneApp
import com.proxai.hermes.data.PhoneApps
import com.proxai.hermes.data.ResearchSends
import com.proxai.hermes.data.ResearchTarget
import com.proxai.hermes.data.baseUrl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * P3 fix (0.9.6): sends someone else's app, or a picked file, to the PC as a foreground service, so it keeps
 * going when ProxAI is left, locked or turned (0.9.5 sent from inside a dialog, so any of those cut it off).
 * Android shows its progress as a notification. When it's done, the attachment waits in ResearchSends for
 * the chat's tray, and a notification says so if ProxAI isn't on screen.
 */
class ResearchSendService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val job = waiting.getAndSet(null)
        ServiceCompat.startForeground(this, Notifier.SEND_ID, Notifier.researchSending(this, job?.title ?: "it", 0, 0),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        if (job == null) {
            stopSelfResult(startId)
            return START_NOT_STICKY
        }
        scope.launch {
            val ctx = this@ResearchSendService
            val settings = ProxApp.instance.settings
            var shown = -1
            runCatching {
                val target = job.resolve(ctx)
                ResearchSends.run(target, AppResearch(settings.baseUrl, settings.apiKey),
                    icon = { pkg -> PhoneApps.saveIcon(ctx, pkg) }) { s ->
                    if (!s.finished && s.percent != shown) {
                        shown = s.percent
                        Notifier.showResearchSending(ctx, s.title, s.done, s.total)
                    }
                }
            }.onFailure { e -> ResearchSends.fail(job.title, e.message) }
            ServiceCompat.stopForeground(ctx, ServiceCompat.STOP_FOREGROUND_REMOVE)
            val end = ResearchSends.current.value
            if (end != null && end.finished && !ProxApp.instance.inForeground) Notifier.researchDone(ctx, end)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (ResearchSends.busy) ResearchSends.cancel()
        scope.cancel()
        super.onDestroy()
    }

    /** A send handed to the service: an app, or a picked file still to be copied out of its provider. */
    private sealed interface Job {
        val title: String
        fun resolve(ctx: Context): ResearchTarget
    }

    private class AppJob(val app: PhoneApp) : Job {
        override val title: String get() = app.label
        override fun resolve(ctx: Context): ResearchTarget = ResearchTarget.App(app)
    }

    private class FileJob(val uri: Uri, override val title: String) : Job {
        override fun resolve(ctx: Context): ResearchTarget {
            val (name, copy) = try {
                PcFiles.copyForSending(ctx, uri)
            } finally {
                PcFiles.releaseGrant(ctx, uri)
            }
            return ResearchTarget.PickedFile(name, copy)
        }
    }

    companion object {
        private val waiting = AtomicReference<Job?>(null)

        /** Starts sending [app] to the PC: null when it started, else why not (another send is going...). */
        fun startApp(ctx: Context, app: PhoneApp): String? = start(ctx, AppJob(app))

        /** Starts sending a picked file (it keeps its read grant until it's copied): null when it started, else why not. */
        fun startFile(ctx: Context, uri: Uri): String? = start(ctx, FileJob(uri, PcFiles.displayName(ctx, uri)))

        private fun start(ctx: Context, job: Job): String? {
            if (!ResearchSends.claim(job.title)) {
                return "Wait for ${ResearchSends.current.value?.title ?: "the last one"} to finish sending"
            }
            if (job is FileJob) PcFiles.keepGrant(ctx, job.uri)
            waiting.set(job)
            return runCatching {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ResearchSendService::class.java))
                null
            }.getOrElse { e ->
                waiting.set(null)
                ResearchSends.fail(job.title, e.message)
                e.message ?: "Couldn't start sending"
            }
        }
    }
}
