package com.proxai.hermes.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.proxai.hermes.ProxApp
import com.proxai.hermes.notify.Notifier
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit

/**
 * The PC's own stats (checklist Phase 14 "Thermal guard"): the thermal watch on the PC
 * (server/proxai_thermal_watch.py) samples GPU/CPU/RAM every second and logs alerts; the apps
 * server publishes them at <server>/apps/v1/pc/... This shows the alerts as notifications and
 * hands the stats to ProxAI-built apps that use the "pc-stats" feature.
 */
data class PcAlert(val id: Int, val time: String, val level: String, val kind: String, val msg: String) {
    val critical get() = level == "CRIT"

    companion object {
        fun parseList(json: String): List<PcAlert> {
            val arr = JSONObject(json).optJSONArray("alerts") ?: return emptyList()
            return (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let {
                    PcAlert(it.optInt("id"), it.optString("time"), it.optString("level"), it.optString("kind"), it.optString("msg"))
                }
            }
        }

        /**
         * Which alerts to show. First check ever (lastSeen 0): only the last 30 minutes, so an
         * install doesn't replay old alerts. Returns (to show, new lastSeen).
         */
        fun toNotify(alerts: List<PcAlert>, lastSeen: Int, now: LocalDateTime): Pair<List<PcAlert>, Int> {
            val newest = alerts.maxOfOrNull { it.id } ?: lastSeen
            val fresh = alerts.filter { it.id > lastSeen }
            val show = if (lastSeen > 0) fresh else fresh.filter {
                try { LocalDateTime.parse(it.time).isAfter(now.minusMinutes(30)) } catch (e: DateTimeParseException) { false }
            }
            return show to maxOf(newest, lastSeen)
        }
    }
}

class PcStatsClient(private val config: HermesConfig) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private fun get(path: String): String {
        if (!config.isConfigured) throw IOException("ProxAI isn't connected to the PC")
        val req = Request.Builder().url(config.baseUrl + "/apps" + path)
            .header("Authorization", "Bearer ${config.apiKey}").build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException(
                if (r.code == 404 || r.code == 502) "The PC's stats aren't set up yet" else "PC stats error (HTTP ${r.code})")
            return r.body!!.string()
        }
    }

    /** {"latest": {...}, "age_s", "watching", "history": [per-minute peaks], "alerts": [...]} */
    fun stats(): String = get("/v1/pc/stats")

    fun alertsAfter(id: Int): List<PcAlert> = PcAlert.parseList(get("/v1/pc/alerts?after=$id"))
}

/** Checks the PC for new alerts about every 15 minutes (Android's minimum while the app is closed). */
class PcAlertWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val settings = ProxApp.instance.settings
        if (!settings.pcAlerts || !settings.isConfigured) return Result.success()
        return try {
            val (show, last) = PcAlert.toNotify(
                PcStatsClient(settings).alertsAfter(settings.lastPcAlertId), settings.lastPcAlertId, LocalDateTime.now())
            show.takeLast(5).forEach { Notifier.pcAlert(applicationContext, it) }
            settings.lastPcAlertId = last
            Result.success()
        } catch (e: IOException) {
            Result.success()   // PC off or away from the tailnet: try again next round
        }
    }

    companion object {
        private const val NAME = "pc-alerts"

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            if (!ProxApp.instance.settings.pcAlerts) {
                wm.cancelUniqueWork(NAME)
                return
            }
            val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<PcAlertWorker>(15, TimeUnit.MINUTES).setConstraints(net).build())
        }

        /** An extra check right away (app opened). */
        fun checkNow(ctx: Context) {
            if (ProxApp.instance.settings.pcAlerts) {
                WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<PcAlertWorker>().build())
            }
        }
    }
}

/**
 * The only door from ProxAI-built apps into ProxMobile: content://com.proxai.hermes.pcstats,
 * method "stats" -> Bundle("json"). Answers only apps named com.proxai.apps.* that are signed
 * with the generated-apps key ProxMobile pinned on the first install (AppInstaller). The API key
 * never leaves ProxMobile; the app only gets the stats JSON.
 */
class PcStatsProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        val caller = callingPackage
        val trusted = ctx.getSharedPreferences("pc_apps", Context.MODE_PRIVATE).getString("trusted_cert_sha256", null)
        val why = refuse(caller, caller?.let { certOf(ctx, it) }, trusted)
        if (why != null) return Bundle().apply { putString("error", why) }
        if (method != "stats") return Bundle().apply { putString("error", "unknown method") }
        return try {
            Bundle().apply { putString("json", PcStatsClient(ProxApp.instance.settings).stats()) }
        } catch (e: Exception) {
            Bundle().apply { putString("error", e.message ?: "Can't reach the PC") }
        }
    }

    private fun certOf(ctx: Context, pkg: String): String? = try {
        val info = ctx.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners
        if (signers == null || signers.size != 1) null
        else MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        /** Why [caller] may not read the stats, or null if it may. Unit-tested. */
        fun refuse(caller: String?, cert: String?, trusted: String?): String? = when {
            caller == null || !caller.startsWith("com.proxai.apps.") -> "Only ProxAI-built apps can read the PC's stats"
            trusted == null -> "Install a ProxAI app from the Apps tab first"
            cert == null || !cert.equals(trusted, ignoreCase = true) -> "This app isn't signed with the ProxAI apps key"
            else -> null
        }
    }
}
