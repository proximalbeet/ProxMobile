package com.proxai.hermes.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.proxai.hermes.ProxApp
import com.proxai.hermes.data.VaultYes
import com.proxai.hermes.data.VaultYesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * VAULT-UNDO-APP (0.9.18): checks the PC for newly approved vault changes (every minute while ProxMobile is open,
 * from ProxApp, and about every 15 minutes otherwise, [VaultYesWorker]) and posts one notification per new entry,
 * with an Undo button when the PC says it can be undone and no other entry shares its id.
 */
object VaultYesSync {
    private val lock = Mutex()
    /** The first check ever looks back this far, so an old log doesn't buzz all at once. */
    private const val FIRST_LOOK_MINUTES = 30L

    suspend fun pull(ctx: Context) = lock.withLock {
        val s = ProxApp.instance.settings
        val client = VaultYesClient.from(s)
        if (!client.isConfigured) return@withLock
        val since = s.vaultYesSince.ifBlank {
            Instant.now().minus(FIRST_LOOK_MINUTES, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS).toString()
        }
        val w = withContext(Dispatchers.IO) { client.written(since) }.written ?: return@withLock  // PC away: next round
        val (new, seen) = VaultYes.fresh(w.items, s.vaultYesSeen)
        s.vaultYesPending = s.vaultYesPending + new.filter { it.undoable }
        s.vaultYesSeen = seen
        if (w.serverTime.isNotEmpty()) s.vaultYesSince = w.serverTime
        new.takeLast(8).forEach { Notifier.vaultChange(ctx, it) }
    }

    /** Undo one change by its key (from a notification). Looked up in what the phone kept, never from the intent. */
    suspend fun undo(ctx: Context, key: String) {
        val s = ProxApp.instance.settings
        val change = s.vaultYesPending.firstOrNull { it.key == key } ?: return
        if (VaultYes.needsUnlock(s.appLock, ProxApp.instance.lock.check())) {   // locked since the tap: send nothing
            Notifier.vaultNeedsUnlock(ctx, change)
            return
        }
        val a = withContext(Dispatchers.IO) { VaultYesClient.from(s).undo(change) }
        if (a.ok) s.vaultYesPending = s.vaultYesPending.filterNot { it.key == key }
        Notifier.vaultUndone(ctx, change, a.message, retry = !a.ok && a.status != 409)
    }
}

class VaultYesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        runCatching { VaultYesSync.pull(applicationContext) }
        return Result.success()
    }

    companion object {
        private const val NAME = "vault-yes"

        fun schedule(ctx: Context) {
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<VaultYesWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        }
    }
}

/** Runs one notification's Undo in the background. */
class VaultUndoWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val key = inputData.getString(KEY) ?: return Result.success()
        VaultYesSync.undo(applicationContext, key)
        return Result.success()
    }

    companion object {
        const val KEY = "key"
        fun enqueue(ctx: Context, key: String) {
            WorkManager.getInstance(ctx).enqueueUniqueWork("vault-undo-" + key.hashCode(), ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<VaultUndoWorker>().setInputData(workDataOf(KEY to key)).build())
        }
    }
}

/** A notification's Undo: only ProxMobile's own explicit, immutable PendingIntent reaches it (not exported). */
class VaultUndoReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val app = ProxApp.instance
        if (VaultYes.undoStep(app.settings.appLock, app.lock.check()) == VaultYes.UndoStep.RepostUnlock) {   // #47: nothing sent while locked
            app.settings.vaultYesPending.firstOrNull { it.key == key }?.let { Notifier.vaultNeedsUnlock(context, it) }
            return
        }
        Notifier.vaultUndoing(context, key)
        VaultUndoWorker.enqueue(context, key)
    }

    companion object {
        const val ACTION = "com.proxai.hermes.VAULT_UNDO"
        const val EXTRA_KEY = "key"
    }
}
