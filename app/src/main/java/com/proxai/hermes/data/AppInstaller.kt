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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
    const val EXTRA_PKG = "com.proxai.hermes.INSTALL_PKG"

    sealed class Result {
        object NeedsPermission : Result()
        object Started : Result()
        data class Refused(val why: String) : Result()
    }

    fun canInstall(ctx: Context) = ctx.packageManager.canRequestPackageInstalls()

    // B2 (10-26): the button freed up as soon as the APK was handed over and the installed version was
    // only re-read on resume, so it still said Install/Update and needed a second tap. Now a package
    // stays "installing" until Android reports back, and every result bumps [results].
    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installing: StateFlow<Set<String>> = _installing
    private val _results = MutableStateFlow(0)
    val results: StateFlow<Int> = _results
    /** Android's "Install this app?" screen, for the visible Apps screen to open (null = none waiting). */
    val confirm = MutableStateFlow<Intent?>(null)
    @Volatile var screenOpen = 0

    internal fun finished(pkg: String?) {
        _installing.value = if (pkg == null) emptySet() else _installing.value - pkg
        _results.value++
    }

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

    const val OWNER_KEY_ONLY = "Prox Hub and BoardRoom must be signed with your owner key (Settings → Owner apps); " +
        "this APK isn't, so it's not installed"
    const val OWNER_KEY_IS_THEIRS = "Only Prox Hub and BoardRoom are signed with your owner key; not installing this one"
    const val PIN_OWNER_KEY_FIRST = "Pin your owner key's fingerprint first (Settings → PC links → Owner apps), then " +
        "install Prox Hub and BoardRoom"

    /** Pure check, unit-tested: may an APK with [cert] and [pkg] be installed, given the trusted cert (the
     *  generated-apps key) and [ownerPin] (OWNER-SIGN, 0.9.16: Nathan's owner key, null = not pinned)? Prox Hub and
     *  BoardRoom install only when a pin is set and they're signed with it (no pin: not at all); nothing else may be
     *  signed with it. Android itself still checks the signature on install, and the bridge decides from the
     *  installed package, never from this file. */
    fun check(pkg: String?, cert: String?, trusted: String?, ownerPin: String? = null): String? = when {
        pkg == null || !pkg.startsWith("com.proxai.apps.") -> "That isn't a ProxAI-built app"
        cert == null -> "Couldn't read the app's signature"
        pkg in DumpsyncClient.OWNER_APPS -> when {
            ownerPin == null -> PIN_OWNER_KEY_FIRST
            ownerPin.equals(cert, ignoreCase = true) -> null
            else -> OWNER_KEY_ONLY
        }
        ownerPin != null && ownerPin.equals(cert, ignoreCase = true) -> OWNER_KEY_IS_THEIRS
        trusted != null && !trusted.equals(cert, ignoreCase = true) ->
            "This APK is signed with a different key than your other ProxAI apps; not installing it"
        else -> null
    }

    /** The generated-apps key is pinned from the first install, but never from an owner app's (OWNER-SIGN): that
     *  would make the owner key the key every PC-built app is checked against. Unit-tested. */
    fun pinsAppsKey(pkg: String?, cert: String?, trusted: String?): Boolean =
        trusted == null && cert != null && pkg != null && pkg !in DumpsyncClient.OWNER_APPS

    /** The package the install session is bound to (SessionParams.setAppPackageName): an owner app's, else none.
     *  Unit-tested. */
    fun sessionPackage(pkg: String?): String? = pkg?.takeIf { it in DumpsyncClient.OWNER_APPS }

    fun install(ctx: Context, apk: File): Result {
        if (!canInstall(ctx)) return Result.NeedsPermission
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cert = certOf(ctx, apk)
        val pkg = packageOf(ctx, apk)
        val ownerPin = com.proxai.hermes.ProxApp.instance.settings.ownerCertPin.ifBlank { null }
        check(pkg, cert, prefs.getString(KEY_CERT, null), ownerPin)?.let { return Result.Refused(it) }

        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Review round: for an owner app the session itself is bound to that package, so Android fails the install
        // if the staged APK turns out to be any other package.
        sessionPackage(pkg)?.let { params.setAppPackageName(it) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("app.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val done = Intent(ctx, InstallResultReceiver::class.java).putExtra(EXTRA_PKG, packageOf(ctx, apk))
            val pi = PendingIntent.getBroadcast(ctx, id, done,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            session.commit(pi.intentSender)
        }
        if (pinsAppsKey(pkg, cert, prefs.getString(KEY_CERT, null))) prefs.edit().putString(KEY_CERT, cert).apply()
        packageOf(ctx, apk)?.let { _installing.value = _installing.value + it }
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
                // Opened from the visible screen when there is one: a start from a background receiver
                // can be dropped by Android, which left the first tap doing nothing.
                if (AppInstaller.screenOpen > 0) AppInstaller.confirm.value = confirm
                else context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            }
            PackageInstaller.STATUS_SUCCESS -> Toast.makeText(context, "Installed", Toast.LENGTH_SHORT).show()
            PackageInstaller.STATUS_FAILURE_ABORTED -> Unit
            else -> Toast.makeText(context, "Install failed: " +
                (intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "unknown error"), Toast.LENGTH_LONG).show()
        }
        AppInstaller.finished(intent.getStringExtra(AppInstaller.EXTRA_PKG))
    }
}
