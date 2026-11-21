package com.proxai.hermes.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * WAKE-PHONE (Nathan 11-14 / 11-18): "Hey Prox" on the phone. The rules here are plain Kotlin so the unit tests
 * can run them; voice/WakeWordService.kt connects them to Android.
 *
 * Ground rules (docs/design-pc-voice-talk-discord.md task 1, his picks):
 * - Off until he turns it on (Settings > Voice > Listen for "Hey Prox").
 * - While on it listens all the time, except: during a call, while the PC says "desk", on low battery, while
 *   muted from the Quick Settings tile (1 hour, or until he turns it back on), and while ProxAI itself is using
 *   the mic (the overlay, a dictation, a reply being read aloud).
 * - Android's green mic dot and a fixed notification show the whole time it listens.
 * - Sound before the word only passes through memory on its way to the engine and is dropped; nothing is saved
 *   or sent before the word. A wake opens the power-button overlay and permits nothing else.
 */

/** The wake word's own two settings. A file of its own ("wake"), so Settings.kt stays as it is. */
class WakeSettings(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences(FILE, Context.MODE_PRIVATE))

    /** Settings > Voice > Listen for "Hey Prox". Off until he turns it on. */
    var enabled: Boolean
        get() = prefs.getBoolean("enabled", false)
        set(v) = prefs.edit().putBoolean("enabled", v).apply()

    /** Muted from the tile until this time (ms); 0 = not muted, [WakeMute.UNTIL_ON] = until turned back on. */
    var muteUntil: Long
        get() = prefs.getLong("mute_until", 0L)
        set(v) = prefs.edit().putLong("mute_until", v).apply()

    /** Round 2: how many times the word opened (or tried to open) the overlay on [day] (epoch day). A number only:
     *  no times, no sound, nothing said. */
    fun wakesOn(day: Long): Int = if (prefs.getLong("count_day", -1L) == day) prefs.getInt("count", 0) else 0

    /** Counts one more wake on [day]; a new day starts again from 1. */
    fun countWake(day: Long): Int {
        val n = wakesOn(day) + 1
        prefs.edit().putLong("count_day", day).putInt("count", n).apply()
        return n
    }

    /** Round 3 (shape C, app lock off): how long after his last phone unlock a wake on the lock screen listens
     *  hands-free. One of [WakeLockScreen.WINDOWS]; 30 min unless he picked another. */
    var handsFreeWindowMs: Long
        get() = prefs.getLong("hands_free_window_ms", WakeLockScreen.DEFAULT_WINDOW_MS)
            .takeIf { it in WakeLockScreen.WINDOWS } ?: WakeLockScreen.DEFAULT_WINDOW_MS
        set(v) { if (v in WakeLockScreen.WINDOWS) prefs.edit().putLong("hands_free_window_ms", v).apply() }

    companion object {
        const val FILE = "wake"
    }
}

/** The tile's two mutes. */
object WakeMute {
    const val HOUR_MS = 3_600_000L
    /** "Until I turn it back on": never runs out by itself. */
    const val UNTIL_ON = Long.MAX_VALUE

    fun forAnHour(now: Long): Long = now + HOUR_MS
    fun muted(until: Long, now: Long): Boolean = until > now

    /** The tile's and the notification's words. */
    fun label(until: Long, now: Long): String = when {
        !muted(until, now) -> "Not muted"
        until == UNTIL_ON -> "Muted until you turn it back on"
        else -> "Muted for ${((until - now + 59_999) / 60_000).coerceAtLeast(1)} min"
    }
}

/** What the PC says about where he is (PROX-PC publishes it on the notices feed). Unknown counts as away. */
enum class PcPresence { DESK, AWAY, UNKNOWN }

/**
 * The PC's "desk" / "away" word. The PC posts a notice of the fixed kind "desk" or "away" on the feed the phone
 * already long-polls (/v1/pc/notices, PcListener); the newest one wins. These notices are never shown. When the
 * long poll fails (PC off, phone off the tailnet) the word is unknown again, which counts as away: the phone listens.
 */
object PcPresenceFeed {
    const val DESK = "desk"
    const val AWAY = "away"
    val KINDS = setOf(DESK, AWAY)

    private val _state = MutableStateFlow(PcPresence.UNKNOWN)
    val state: StateFlow<PcPresence> = _state

    /** The newest presence word in [notices], or [current] when there is none. Pure. */
    fun from(notices: List<PcNotice>, current: PcPresence): PcPresence =
        when (notices.filter { it.kind in KINDS }.maxByOrNull { it.id }?.kind) {
            DESK -> PcPresence.DESK
            AWAY -> PcPresence.AWAY
            else -> current
        }

    fun seen(notices: List<PcNotice>) { _state.value = from(notices, _state.value) }

    /** The long poll failed or stopped: nobody says "desk" any more. */
    fun lost() { _state.value = PcPresence.UNKNOWN }

    /** Presence words are for the phone, not for him: they never become a notification or a chat line. */
    fun shown(notices: List<PcNotice>): List<PcNotice> = notices.filterNot { it.kind in KINDS }
}

/** Why the phone isn't listening right now ([NONE] = it is). The order is the order [WakeRules.decide] checks. */
enum class WakeHold(val why: String) {
    NONE(""),
    OFF("off in Settings"),
    NO_MIC("the microphone isn't allowed"),
    /** Round 2: its notification can't show (notifications or its channel are off), so it mustn't listen. */
    NOTICE_OFF("notifications are off"),
    NO_ENGINE("the wake engine isn't in this build"),
    MUTED("muted"),
    CALL("on a call"),
    DESK("you're at your PC"),
    BATTERY("battery low"),
    BUSY("ProxAI is using the mic"),
}

/** Everything the decision needs, read fresh about once a second. */
data class WakeInputs(
    val enabled: Boolean,
    val micAllowed: Boolean,
    /** The fixed notification can show ([WakeRules.noticeVisible]). */
    val noticeVisible: Boolean,
    val engineReady: Boolean,
    val muteUntil: Long,
    val now: Long,
    val inCall: Boolean,
    val presence: PcPresence,
    /** 0-100, or -1 when unknown. */
    val batteryPct: Int,
    val charging: Boolean,
    /** ProxAI's own overlay, dictation or voice holds the mic, or a wake just happened. */
    val micBusy: Boolean,
)

object WakeRules {
    /** At or below this (and not charging) it stops listening. */
    const val LOW_BATTERY_PCT = 15

    fun batteryLow(pct: Int, charging: Boolean): Boolean = !charging && pct in 0..LOW_BATTERY_PCT

    /** AudioManager modes that mean a call is ringing or going (phone or an app's call): 1..6. */
    fun callMode(mode: Int): Boolean = mode in 1..6

    /** The fixed notification can show: notifications are on and its channel isn't blocked (importance NONE = 0;
     *  null = the channel is missing). */
    fun noticeVisible(notificationsOn: Boolean, channelImportance: Int?): Boolean =
        notificationsOn && channelImportance != null && channelImportance != 0

    fun decide(i: WakeInputs): WakeHold = when {
        !i.enabled -> WakeHold.OFF
        !i.micAllowed -> WakeHold.NO_MIC
        !i.noticeVisible -> WakeHold.NOTICE_OFF
        !i.engineReady -> WakeHold.NO_ENGINE
        WakeMute.muted(i.muteUntil, i.now) -> WakeHold.MUTED
        i.inCall -> WakeHold.CALL
        i.presence == PcPresence.DESK -> WakeHold.DESK
        batteryLow(i.batteryPct, i.charging) -> WakeHold.BATTERY
        i.micBusy -> WakeHold.BUSY
        else -> WakeHold.NONE
    }

    /** The fixed notification's title and text. It always says what it's doing; it never hides. */
    fun notice(hold: WakeHold, muteUntil: Long, now: Long, wakesToday: Int = 0): Pair<String, String> = when (hold) {
        WakeHold.NONE -> "Listening for “Hey Prox”" to "On this phone only. Mute it from the Hey Prox tile." +
            if (wakesToday > 0) " Woken $wakesToday× today." else ""
        WakeHold.MUTED -> "“Hey Prox” is muted" to WakeMute.label(muteUntil, now) + ". Tap the Hey Prox tile to listen again."
        else -> "“Hey Prox” paused" to "Not listening: ${hold.why}."
    }
}

/**
 * ProxAI's own mic use. The overlay holds it while it's open, a SpeechController while it listens or speaks;
 * the wake listener lets go of the mic while anyone holds it (two can't record at once, and its own voice
 * mustn't wake it).
 */
object MicGate {
    private val _holders = MutableStateFlow(0)
    val holders: StateFlow<Int> = _holders
    val held: Boolean get() = _holders.value > 0

    @Synchronized fun hold() { _holders.value = _holders.value + 1 }
    @Synchronized fun release() { _holders.value = maxOf(0, _holders.value - 1) }
}

/**
 * Round 3 (shape C): his last real phone unlock (Android's ACTION_USER_PRESENT), seen by the running wake
 * listener. In memory only, never stored. With no unlock seen since the listener started it's 0, which counts as
 * "not recently unlocked" ("Unlock to talk").
 */
object PhoneUnlocks {
    @Volatile var last = 0L
        private set

    fun seen(at: Long) { last = at }

    /** The listener started or stopped: nothing seen since. */
    fun reset() { last = 0L }
}

/** Phone calls as CallMonitor sees them (CallWatcher's states); the wake listener also reads the audio mode. */
object PhoneCalls {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active
    fun onState(state: Int) { _active.value = CallWatcher.inCall(state) }
    fun clear() { _active.value = false }
}

/**
 * The safety net when both the phone and the PC hear him: the first device to claim a wake wins for 3 s; the
 * other beeps once and stays quiet. The PC side waits for PROX-PC, so [LocalWakeClaim] always wins today.
 */
interface WakeClaim {
    /** True when this phone answers the wake heard at [at] (ms). */
    fun claim(at: Long): Boolean
}

object LocalWakeClaim : WakeClaim {
    override fun claim(at: Long): Boolean = true
}

/** The 3-second rule itself, for whichever device keeps the claims. Pure. */
class ClaimWindow(private val windowMs: Long = WINDOW_MS) {
    private var winner: String? = null
    private var at = Long.MIN_VALUE

    /** True when [device] wins the wake it heard at [time]. */
    @Synchronized fun claim(device: String, time: Long): Boolean {
        val open = winner != null && time - at in 0 until windowMs
        if (open) return winner == device
        winner = device
        at = time
        return true
    }

    companion object {
        const val WINDOW_MS = 3_000L
    }
}

/** One log line per wake: when, from=wake, done or refused (why). Never audio, never what was said. */
object WakeLog {
    fun line(time: Long, done: Boolean, why: String = ""): String {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(time))
        val clean = why.replace(Regex("[^A-Za-z0-9 .,:'-]"), "").take(60).trim()
        return "$at from=wake " + (if (done) "done" else "refused") + (if (clean.isNotEmpty()) " ($clean)" else "")
    }
}

/** Round 2: what /v1/approvals said right before a wake overlay sends: nothing waits, a gate waits, or no answer. */
enum class GateCheck { NONE, WAITING, UNKNOWN }

/** What a wake overlay does with what it heard. */
enum class VoiceSend {
    /** Sent, as from the power button. */
    SEND,
    /** A yes-gate waits: voice never answers it; the Accept / Reject sheet shows with "that needs your tap". */
    NEEDS_TAP,
    /** The PC couldn't be asked: nothing is sent; the words wait in the text box for his tap on Send. */
    HOLD,
}

/** How long one dictation may run, and whether silence gets a second listen. The defaults are the power button's. */
data class DictationRules(
    val capMs: Long = 3 * 60_000L,
    /** Ends the dictation at [capMs] even while speech goes on (the power button only checks at a pause). */
    val hardCap: Boolean = false,
    val listenAgainWhenSilent: Boolean = true,
)

/** What each way of opening the overlay may do. */
object VoicePolicy {
    const val WAKE = "wake"
    /** Round 2: one dictation after "Hey Prox" ends here (30-45 s asked; a TV that keeps talking stops here). */
    const val WAKE_DICTATION_CAP_MS = 40_000L
    /** Round 2: an untouched wake overlay with nothing going on (no listening, reply or voice) closes after this. */
    const val WAKE_IDLE_CLOSE_MS = 60_000L

    /** After "Hey Prox", speech becomes text on the phone only (never Google's online recognizer). */
    fun onDeviceOnly(source: String?): Boolean = source == WAKE

    /** Only a power-button press can offer "Look at my screen" (and only when he taps it). */
    fun mayLookAtScreen(source: String?): Boolean = source == "power"

    /** Round 2 (yes-gates): the power button sends as it always has; a wake overlay asks the PC first. */
    fun voiceSend(source: String?, gate: GateCheck): VoiceSend = when {
        source != WAKE -> VoiceSend.SEND
        gate == GateCheck.NONE -> VoiceSend.SEND
        gate == GateCheck.WAITING -> VoiceSend.NEEDS_TAP
        else -> VoiceSend.HOLD
    }

    /** Round 2 (false wakes): after "Hey Prox", a 40 s cap and no second listen when nothing was heard. */
    fun dictation(source: String?): DictationRules =
        if (source == WAKE) DictationRules(capMs = WAKE_DICTATION_CAP_MS, hardCap = true, listenAgainWhenSilent = false)
        else DictationRules()

    /** Round 3 (#49): an untouched wake overlay listens again by itself at most this many times. */
    const val WAKE_MAX_FOLLOW_UPS = 3

    /** Round 2 (false wakes): a wake overlay listens again by itself only after a reply to a request it sent.
     *  Round 3 (#49): and an untouched one at most [WAKE_MAX_FOLLOW_UPS] times, then not until a touch or a new
     *  "Hey Prox" (a new overlay). */
    fun mayFollowUp(source: String?, answeredRequestPending: Boolean, used: Boolean = false, followUpsSoFar: Int = 0): Boolean =
        source != WAKE || (answeredRequestPending && (used || followUpsSoFar < WAKE_MAX_FOLLOW_UPS))

    /** Round 3: a wake overlay listens at once when it opens (if it may) and after ProxAI's own unlock (shape C,
     *  path b: fingerprint / PIN); the power button keeps its "start listening" setting for both. */
    fun listenAfterAppUnlock(source: String?, autoListen: Boolean): Boolean = source == WAKE || autoListen

    /** Round 2: an untouched wake overlay idle this long closes (while open it keeps the wake listener off). */
    fun closeIdle(source: String?, used: Boolean, idleMs: Long): Boolean =
        source == WAKE && !used && idleMs >= WAKE_IDLE_CLOSE_MS

    /** Round 2 (app lock): a wake overlay counts as "on screen" for the lock's timer only once he touches it or
     *  unlocks in it (a fingerprint, the PIN). A word alone never keeps ProxAI unlocked. */
    fun countsOnScreen(source: String?, used: Boolean): Boolean = source != WAKE || used
}

/**
 * What a wake does while the phone is locked: the one place that decides it. Nathan's pick (11-18 morning): C.
 * - (a) A wake never restarts ProxAI's app-lock timer ([VoicePolicy.countsOnScreen]).
 * - (b) ProxAI's app lock ON: the overlay opens as today; once the app lock's time is up it shows ProxAI's own
 *   fingerprint prompt (the app lock decides, as for the power button).
 * - (c) App lock OFF: hands-free for [WakeSettings.handsFreeWindowMs] (10 min / 30 min / 2 h, 30 min unless he
 *   picks another) after his last phone unlock; after that "Unlock to talk" on the lock screen: one touch asks for
 *   the phone's unlock, and then it listens.
 * - (d) Whatever it does here, the overlay is a wake overlay: a waiting yes-gate always needs his tap.
 * Both the listener and the overlay ask this; the overlay's answer is the one that counts.
 */
object WakeLockScreen {
    enum class Do {
        /** Open and listen (phone unlocked, app lock on, or within the hands-free window). */
        OPEN_OVERLAY,
        /** Open with only "Unlock to talk": nothing listens until the phone is unlocked. */
        UNLOCK_TO_TALK,
    }

    const val MIN_MS = 60_000L
    val WINDOWS = listOf(10 * MIN_MS, 30 * MIN_MS, 120 * MIN_MS)
    const val DEFAULT_WINDOW_MS = 30 * MIN_MS

    fun decide(keyguardLocked: Boolean, now: Long, appLockOn: Boolean, lastPhoneUnlock: Long, windowMs: Long): Do = when {
        !keyguardLocked -> Do.OPEN_OVERLAY
        appLockOn -> Do.OPEN_OVERLAY                                   // (b): ProxAI's own lock decides
        lastPhoneUnlock > 0 && now - lastPhoneUnlock in 0..windowMs -> Do.OPEN_OVERLAY   // (c): hands-free
        else -> Do.UNLOCK_TO_TALK                                       // (c): after the window (or never seen)
    }

    /** The overlay starts listening by itself only when it may. */
    fun autoListen(d: Do): Boolean = d == Do.OPEN_OVERLAY

    /** (d): every lock-screen outcome opens a wake overlay, so the wake's send rules (the yes-gate) apply. */
    fun overlaySource(@Suppress("UNUSED_PARAMETER") d: Do): String = VoicePolicy.WAKE

    /** The Settings words for a window. */
    fun label(windowMs: Long): String = if (windowMs >= 60 * MIN_MS) "${windowMs / (60 * MIN_MS)} h" else "${windowMs / MIN_MS} min"
}

/**
 * Round 2 (app lock): which of ProxMobile's screens are visible, and which of those count as "on screen" for the
 * app lock. A wake overlay nobody has touched is visible but doesn't count. Pure; ProxApp feeds it.
 */
class OnScreen<T>(private val counts: (T) -> Boolean) {
    private val visible = LinkedHashSet<T>()
    private val counted = LinkedHashSet<T>()

    /** Some ProxMobile screen is up (replies don't need a notification). */
    val anyVisible: Boolean get() = visible.isNotEmpty()
    /** A screen that counts is up (the app lock's timer is stopped). */
    val anyCounted: Boolean get() = counted.isNotEmpty()

    /** True when [t] is the first counted screen (the lock's onScreen() runs). */
    fun start(t: T): Boolean {
        visible += t
        if (!counts(t)) return false
        val first = counted.isEmpty()
        return counted.add(t) && first
    }

    /** True when the last counted screen went away (the lock's timer starts). */
    fun stop(t: T): Boolean {
        visible -= t
        return counted.remove(t) && counted.isEmpty()
    }

    /** A visible screen that didn't count now does (he touched the wake overlay). True when it's the first. */
    fun promote(t: T): Boolean {
        if (t !in visible || t in counted) return false
        val first = counted.isEmpty()
        counted += t
        return first
    }
}

/**
 * Round 2 (false wakes): the keywords line carries its own threshold. Format (sherpa-onnx): the model's tokens,
 * then ":boost", "#threshold" (0..1, higher wakes less) and "@NAME". One line, "@HEY_PROX", a threshold in
 * 0.1..0.9.
 */
object KeywordLine {
    fun tokens(line: String): List<String> = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() && it[0] !in ":#@" }

    fun threshold(line: String): Float? =
        line.trim().split(Regex("\\s+")).firstOrNull { it.startsWith("#") }?.drop(1)?.toFloatOrNull()

    fun valid(text: String): Boolean {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (lines.size != 1) return false
        val line = lines[0]
        val t = threshold(line) ?: return false
        return t in 0.1f..0.9f && tokens(line).isNotEmpty() && line.split(Regex("\\s+")).contains("@HEY_PROX")
    }
}
