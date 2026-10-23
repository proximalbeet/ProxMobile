package com.proxai.hermes.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.widget.Toast
import java.io.File
import java.security.MessageDigest

/**
 * Installs APKs that ProxAI built on the PC. Android always shows its own install prompt; this
 * only hands the file over. Before that it checks the APK is signed with the generated-apps key:
 * the first install remembers that key, and later APKs signed with anything else are refused.
 */
object AppInstaller {
    private const val PREFS = "pc_apps"
    private const val KEY_CERT = "trusted_cert_sha256"

    sealed class Result {
        object NeedsPermission : Result()
        object Started : Result()
        data class Refused(val why: String) : Result()
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    /** Opens "Install unknown apps" for ProxAI (one-time switch). */
    fun openInstallSetting(ctx: Context) {
        ctx.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** SHA-256 of the APK's signing certificate, or null if it can't be read. */
    fun certOf(ctx: Context, apk: File): String? {
        @Suppress("DEPRECATION")
        val info = ctx.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info?.signingInfo?.apkContentsSigners ?: return null
        if (signers.size != 1) return null
        return MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun packageOf(ctx: Context, apk: File): String? =
        ctx.packageManager.getPackageArchiveInfo(apk.path, 0)?.packageName

    /** Pure check, unit-tested: may an APK with [cert] and [pkg] be installed, given the trusted cert? */
    fun check(pkg: String?, cert: String?, trusted: String?): String? = when {
        pkg == null || !pkg.startsWith("com.proxai.apps.") -> "That isn't a ProxAI-built app"
        cert == null -> "Couldn't read the app's signature"
        trusted != null && !trusted.equals(cert, ignoreCase = true) ->
            "This APK is signed with a different key than your other ProxAI apps; not installing it"
        else -> null
    }

    fun install(ctx: Context, apk: File): Result {
        if (!canInstall(ctx)) return Result.NeedsPermission
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cert = certOf(ctx, apk)
        check(packageOf(ctx, apk), cert, prefs.getString(KEY_CERT, null))?.let { return Result.Refused(it) }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val pi = PendingIntent.getBroadcast(ctx, id, Intent(ctx, InstallResultReceiver::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
        if (prefs.getString(KEY_CERT, null) == null && cert != null) prefs.edit().putString(KEY_CERT, cert).apply()
        return Result.Started
    }

    /** Android's own "Uninstall this app?" prompt. */
    fun uninstall(ctx: Context, packageName: String) {
        ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun launch(ctx: Context, packageName: String): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/** Shows Android's install confirmation, then a short result toast. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> Toast.makeText(context, "Install failed: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
    }
}
