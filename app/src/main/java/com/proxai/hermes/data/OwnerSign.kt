package com.proxai.hermes.data

import android.content.Context
import android.content.pm.PackageManager
import com.proxai.hermes.ProxApp

/**
 * OWNER-SIGN (0.9.16): Nathan's own apps (Prox Hub, BoardRoom) are signed on the PC with his owner key, not the
 * generated-apps key every PC-built app shares. ProxMobile treats a caller as an owner app, and hands it the owner
 * token and every Pipeline and Prox Hub op, only when BOTH hold:
 *
 *  - Android names it (Binder's callingPackage) com.proxai.apps.prox_hub or com.proxai.apps.boardroom, and
 *  - Android reports that the INSTALLED package is signed with the certificate whose SHA-256 Nathan pinned in
 *    Settings → Owner apps: PackageManager.hasSigningCertificate(pkg, pin, CERT_INPUT_SHA256) says yes AND
 *    GET_SIGNING_CERTIFICATES shows exactly one current signer, equal to the pin, with no past (rotated-from)
 *    certificates at all. A pin that only matches a past certificate, more than one signer, or any rotation history
 *    is rejected.
 *
 * No pin, no owner. A pin equal to the generated-apps key is never accepted (every PC-built app is signed with that
 * key, so it would prove nothing), and while that key isn't known yet nothing can be pinned and nobody is an owner.
 * An app that shares its Linux UID with another package (android:sharedUserId) is never an owner. Nothing here ever reads an APK file: only what Android says about the installed
 * package. The pin lives in ProxMobile's own preferences; no app can read or set it through the bridge.
 */
object OwnerSign {
    /** Settings key of the pin (lower-case hex, no colons). */
    const val KEY = "owner_cert_sha256"

    /** What the bridge says when the pin is missing or doesn't match. Unit-tested. */
    const val NO_PIN = "Your owner key isn't pinned in ProxMobile (Settings → Owner apps → Owner key fingerprint)."
    const val NOT_OWNER_SIGNED = "This app isn't signed with your owner key, so it gets what other apps get."

    /** What Android reports about one installed package's signature and UID. */
    data class Facts(
        /** hasSigningCertificate(pkg, pin, CERT_INPUT_SHA256). */
        val hasPinnedCert: Boolean,
        /** SHA-256 (hex) of each current signer (apkContentsSigners). */
        val current: List<String>,
        /** signingInfo.hasPastSigningCertificates(). */
        val hasPast: Boolean,
        /** signingInfo.hasMultipleSigners(). */
        val multipleSigners: Boolean,
        /** getPackagesForUid(getPackageUid(pkg)): every package sharing its Linux UID. More than one (a shared UID,
         *  android:sharedUserId) means any of them may name itself as any other to ProxMobile's door, so none of them
         *  is ever an owner app. */
        val uidPackages: List<String>,
    )

    /** Review round (3): the package ProxMobile's door treats as the caller. [named] is ContentProvider
     *  .getCallingPackage(); the framework already checks it belongs to Binder.getCallingUid() (AppOpsManager
     *  .checkPackage, a SecurityException otherwise). Here it's checked again against [uidPackages] =
     *  PackageManager.getPackagesForUid(Binder.getCallingUid()): a name that isn't one of the calling UID's packages
     *  is no caller at all (null), and is refused like an unknown app. Unit-tested. */
    fun verifiedCaller(named: String?, uidPackages: List<String>?): String? =
        named?.takeIf { uidPackages != null && it in uidPackages }

    const val NO_APPS_KEY = "Install one of your other ProxAI apps from the Apps tab first, so ProxMobile knows the " +
        "key every PC-built app shares; then pin your owner key."

    /** A typed fingerprint as keytool or apksigner prints it ("AB:CD:…", spaces, either case) as 64 lower-case hex
     *  characters, or null when it isn't one. Unit-tested. */
    fun normalize(typed: String): String? {
        val h = typed.filterNot { it == ':' || it.isWhitespace() }.lowercase()
        return h.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
    }

    /** Why [typed] can't be saved as the owner pin, or null. [appsKey] = the generated-apps key ProxMobile pinned
     *  on the first app install. Review round (2): while that key is unknown nothing can be pinned, so the "not the
     *  shared key" check never rests on a record that isn't there yet. Unit-tested. */
    fun pinRefusal(typed: String, appsKey: String?): String? {
        val pin = normalize(typed) ?: return "That isn't a SHA-256 fingerprint (64 hex characters, colons allowed)."
        val shared = appsKey?.let { normalize(it) } ?: return NO_APPS_KEY
        if (pin == shared) return "That's the key every PC-built app is signed with, not your owner key."
        return null
    }

    /** Pure decision, unit-tested: is [caller] an owner app signed with [pin]? No apps key known: never (round 2). */
    fun isOwner(caller: String?, pin: String?, appsKey: String?, facts: Facts?): Boolean {
        if (caller == null || caller !in DumpsyncClient.OWNER_APPS) return false
        val p = pin?.let { normalize(it) } ?: return false
        val shared = appsKey?.let { normalize(it) } ?: return false
        if (p == shared) return false
        val f = facts ?: return false
        return f.uidPackages == listOf(caller) && f.hasPinnedCert && !f.hasPast && !f.multipleSigners &&
            f.current.size == 1 && f.current[0].equals(p, ignoreCase = true)
    }

    /** Why [caller] isn't treated as an owner app, or null when it is. Unit-tested. */
    fun refusal(caller: String?, pin: String?, appsKey: String?, facts: Facts?): String? = when {
        isOwner(caller, pin, appsKey, facts) -> null
        pin.isNullOrBlank() -> NO_PIN
        else -> NOT_OWNER_SIGNED
    }

    fun pin(): String? = ProxApp.instance.settings.ownerCertPin.ifBlank { null }

    fun appsKey(ctx: Context): String? =
        ctx.getSharedPreferences("pc_apps", Context.MODE_PRIVATE).getString("trusted_cert_sha256", null)

    /** What Android says about installed [pkg] against [pin] (hex), or null when it can't say (not installed, not
     *  visible, no signing info). */
    fun facts(ctx: Context, pkg: String, pin: String): Facts? = try {
        val pm = ctx.packageManager
        val bytes = ByteArray(32) { i -> pin.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
        val has = pm.hasSigningCertificate(pkg, bytes, PackageManager.CERT_INPUT_SHA256)
        val si = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
        val uidPackages = pm.getPackagesForUid(pm.getPackageUid(pkg, 0))?.toList()
        if (si == null || uidPackages == null) null
        else Facts(
            hasPinnedCert = has,
            current = si.apkContentsSigners.orEmpty().map { sha256Hex(it.toByteArray()) },
            hasPast = si.hasPastSigningCertificates(),
            multipleSigners = si.hasMultipleSigners(),
            uidPackages = uidPackages,
        )
    } catch (e: Exception) {
        null
    }

    /** Is the app Android names [caller] an owner app signed with the pinned owner key? Never throws. The door
     *  (PcStatsProvider.answer) calls this once per call and passes the answer on. */
    fun isOwner(ctx: Context, caller: String?): Boolean {
        if (caller == null || caller !in DumpsyncClient.OWNER_APPS) return false
        val pin = pin()?.let { normalize(it) } ?: return false
        return isOwner(caller, pin, appsKey(ctx), facts(ctx, caller, pin))
    }

    /** For Settings: each owner app that's installed, and whether it's signed with the pinned key. */
    fun status(ctx: Context): List<Pair<String, Boolean>> = DumpsyncClient.OWNER_APPS.sorted().mapNotNull { pkg ->
        val installed = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
        if (!installed) null else pkg to isOwner(ctx, pkg)
    }

    private fun sha256Hex(b: ByteArray) =
        java.security.MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
