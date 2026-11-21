package com.proxai.hermes.data

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * S1 (Phase 3): the app lock, for the stolen-phone case. Fingerprint or face, with an app PIN as
 * the fallback. While locked, nothing reaches the AI or the PC: the app, the power-button
 * assistant, replies typed into notifications and the PC-built apps' bridge all wait for an unlock.
 *
 * It locks again once ProxMobile has been out of sight for [timeoutMs] (1 minute by default,
 * Nathan 10-30; a setting). PC-built apps keep working while they're being used: a bridge call
 * within that time keeps their window open. Those calls never unlock ProxMobile itself.
 * A fresh start (the process was killed) is locked. Pure (clock passed in), tested in AppLockTest.
 *
 * ProxAI line (0.9.9, Nathan 11-02: "Keep it unlocked as the phone conversation goes on and then let it
 * relock after the call is over back to the timer set"): a phone call that begins while ProxMobile is
 * unlocked holds it open ([holdForCall]); when the call ends the normal timer starts ([endCallHold]).
 */
class AppLock(
    private val enabled: () -> Boolean,
    private val timeoutMs: () -> Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _locked = MutableStateFlow(enabled())
    /** True while ProxMobile shows the lock screen and refuses AI and data. */
    val locked: StateFlow<Boolean> = _locked

    /** When the last ProxMobile screen went away; 0 = one is on screen. */
    @Volatile private var awaySince = 0L
    /** The last bridge call a PC-built app was allowed to make; 0 = none since the last unlock. */
    @Volatile private var appsSeen = 0L
    /** A phone call that began while unlocked: no locking until it ends. */
    @Volatile private var callHold = false

    /** Runs after each unlock (sends a notification reply that waited for it). */
    var onUnlocked: (() -> Unit)? = null

    /** Applies the timer. True if locked. */
    fun check(): Boolean {
        if (!enabled()) {
            _locked.value = false
            return false
        }
        if (!_locked.value && !callHold && awaySince > 0 && clock() - awaySince >= timeoutMs()) _locked.value = true
        return _locked.value
    }

    /** The first ProxMobile screen came up after being away. */
    fun onScreen() {
        check()
        awaySince = 0
    }

    /** The last ProxMobile screen went away. */
    fun offScreen() {
        awaySince = clock()
    }

    /** Fingerprint, face or PIN accepted (always on a ProxMobile screen). */
    fun unlock() {
        _locked.value = false
        awaySince = 0
        appsSeen = clock()
        onUnlocked?.invoke()
    }

    /** Runs on "Lock now" (proxDocs locks with it, 0.9.8). */
    var onLockedNow: (() -> Unit)? = null

    /** "Lock now" in Settings. */
    fun lockNow() {
        if (!enabled()) return
        _locked.value = true
        appsSeen = 0
        callHold = false
        onLockedNow?.invoke()
    }

    /** A phone call began. True if it holds the lock open: only when ProxMobile is unlocked right now (a call
     *  that starts while it's locked changes nothing, so the PIN is still asked). */
    fun holdForCall(): Boolean {
        if (!enabled() || check()) return false
        callHold = true
        return true
    }

    /** The held call ended: away from ProxMobile, the normal timer starts now (on screen, nothing changes). */
    fun endCallHold() {
        if (!callHold) return
        callHold = false
        if (awaySince > 0) awaySince = clock()
    }

    /** A call is holding the lock open. */
    val heldForCall: Boolean
        get() = callHold

    /** The lock was just switched on from Settings: stays open until the next time away. */
    fun turnedOn() {
        _locked.value = false
        awaySince = 0
        appsSeen = clock()
    }

    /**
     * The PC-built apps' bridge: allowed while ProxMobile is unlocked, or while those apps keep
     * being used (each allowed call within the lock time keeps their window open).
     */
    fun appsAllowed(): Boolean {
        if (!enabled()) return true
        val now = clock()
        val ok = !check() || (appsSeen > 0 && now - appsSeen < timeoutMs())
        if (ok) appsSeen = now
        return ok
    }
}

/** The app PIN: 4 to 8 digits, stored only as a salted PBKDF2 hash. */
object PinCode {
    private const val ITERATIONS = 120_000
    const val FREE_TRIES = 5

    fun valid(pin: String) = pin.length in 4..8 && pin.all { it in '0'..'9' }

    /** "pbkdf2:<iterations>:<salt>:<hash>" (Base64). */
    fun hash(pin: String, salt: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }): String =
        "pbkdf2:$ITERATIONS:${b64(salt)}:${b64(derive(pin, salt, ITERATIONS))}"

    fun matches(pin: String, stored: String): Boolean {
        val parts = stored.split(':')
        if (parts.size != 4 || parts[0] != "pbkdf2") return false
        val iterations = parts[1].toIntOrNull()?.takeIf { it > 0 } ?: return false
        val salt = runCatching { unb64(parts[2]) }.getOrNull() ?: return false
        val want = runCatching { unb64(parts[3]) }.getOrNull() ?: return false
        return MessageDigest.isEqual(derive(pin, salt, iterations), want)
    }

    /** The wait after [fails] wrong PINs in a row: none for the first 4, then 30 s, doubling every 5 more, at most 15 min. */
    fun waitAfter(fails: Int): Long {
        if (fails < FREE_TRIES) return 0
        val doublings = minOf((fails - FREE_TRIES) / FREE_TRIES, 10)
        return minOf(30_000L shl doublings, 15 * 60_000L)
    }

    private fun derive(pin: String, salt: ByteArray, iterations: Int): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, iterations, 256)).encoded

    private fun b64(b: ByteArray) = Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String) = Base64.getDecoder().decode(s)
}

/** One PIN try against the stored hash, counting wrong ones (they survive a restart). */
object PinAttempts {
    sealed interface Result {
        data object Ok : Result
        /** Wrong PIN. [triesLeft] before a wait; [waitMs] > 0 = wait that long before the next try. */
        data class Wrong(val triesLeft: Int, val waitMs: Long) : Result
        /** Still waiting after too many wrong tries; nothing was checked. */
        data class Wait(val ms: Long) : Result
    }

    fun attempt(s: Settings, pin: String, now: Long = System.currentTimeMillis()): Result {
        if (now < s.pinWaitUntil) return Result.Wait(s.pinWaitUntil - now)
        if (PinCode.matches(pin, s.pinHash)) {
            reset(s)
            return Result.Ok
        }
        val fails = s.pinFails + 1
        s.pinFails = fails
        val wait = PinCode.waitAfter(fails)
        if (wait > 0) s.pinWaitUntil = now + wait
        return Result.Wrong(maxOf(0, PinCode.FREE_TRIES - fails), wait)
    }

    /** After any unlock (a fingerprint counts too). */
    fun reset(s: Settings) {
        s.pinFails = 0
        s.pinWaitUntil = 0
    }
}
