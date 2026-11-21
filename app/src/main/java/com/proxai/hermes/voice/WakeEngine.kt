package com.proxai.hermes.voice

import com.proxai.hermes.data.LocalWakeClaim
import com.proxai.hermes.data.WakeClaim
import com.proxai.hermes.data.WakeHold
import com.proxai.hermes.data.WakeLockScreen
import com.proxai.hermes.data.WakeLog

/**
 * WAKE-PHONE: the keyword engine behind an interface, so the app builds and the tests run without it.
 *
 * The real one is sherpa-onnx keyword spotting (src/wake/java/.../SherpaWakeEngine.kt), compiled only when
 * Nathan has added the pinned AAR and model (README, "Hey Prox"); without them the build uses
 * src/nowake/java/.../WakeEngines.kt, which has no engine, and the Settings switch says so. Tests use a fake.
 */
interface WakeEngine : AutoCloseable {
    /** Samples per second it expects (mono floats in -1..1). */
    val sampleRate: Int

    /** Feeds [count] samples from [samples]; true when "Hey Prox" ended in them. Keeps no copy beyond its own
     *  short decoding window (a few seconds at most), which [reset] drops. */
    fun accept(samples: FloatArray, count: Int): Boolean

    /** Forgets everything heard so far. */
    fun reset()

    override fun close()
}

/** Where the sound comes from: the phone's mic in the app, a fake in tests. */
interface MicSource : AutoCloseable {
    /** Fills [into] with up to its size in samples; the number read, 0 for none yet, or <0 when the mic is gone. */
    fun read(into: FloatArray): Int
    override fun close()
}

/**
 * Pre-wake sound goes one way only: mic -> one reused chunk in memory -> the engine. Nothing else gets it: no
 * file, no network, no log, no copy. When the loop ends (the word, a stop rule, an error) the chunk is zeroed
 * and the engine is reset, so nothing from before the word is left.
 */
class WakeLoop(private val mic: MicSource, private val engine: WakeEngine, chunkSamples: Int = engine.sampleRate / 10) {
    private val chunk = FloatArray(chunkSamples)

    /** Runs until the word (true), [keepGoing] says stop or the mic is gone (false). */
    fun runUntilWord(keepGoing: () -> Boolean): Boolean {
        try {
            while (keepGoing()) {
                val n = mic.read(chunk)
                if (n < 0) return false
                if (n > 0 && engine.accept(chunk, n)) return true
            }
            return false
        } finally {
            chunk.fill(0f)
            engine.reset()
        }
    }

    /** For the tests: the chunk is all zeros once [runUntilWord] returns. */
    internal fun chunkIsClear(): Boolean = chunk.all { it == 0f }
}

/** What a wake may do on the phone. That's all of it: no send, no booking, nothing outward. */
interface WakeHands {
    /** A short buzz: the word was heard. */
    fun buzz()
    /** One beep: another device took this wake. */
    fun beep()
    /** Opens the power-button overlay with source [source]; false if it couldn't (it then offers a tap).
     *  [unlockToTalk]: on the lock screen past the hands-free window, it opens showing only "Unlock to talk". */
    fun openOverlay(source: String, unlockToTalk: Boolean = false): Boolean
    /** The one log line for this wake. */
    fun log(line: String)
    /** One more wake today (a number only). */
    fun countWake()
}

/**
 * After the word: claim it (the 3-second rule), buzz, open the same overlay as the power-button hold. The
 * overlay's own rules apply from there; the wake itself permits nothing.
 */
class WakeFlow(private val hands: WakeHands, private val claims: WakeClaim = LocalWakeClaim) {
    /**
     * Returns true when the overlay was opened. [hold] is [com.proxai.hermes.data.WakeRules.decide] asked again at
     * the moment the word arrived (round 2: a call, a mute or the desk word that came in while the engine was
     * still decoding drops it). [onLock] is what [WakeLockScreen] says a wake does on a locked phone.
     */
    fun onHeard(now: Long, hold: WakeHold = WakeHold.NONE,
                onLock: WakeLockScreen.Do = WakeLockScreen.Do.OPEN_OVERLAY): Boolean {
        if (hold != WakeHold.NONE) {
            hands.log(WakeLog.line(now, done = false, why = hold.why))   // no buzz, no overlay, not counted
            return false
        }
        hands.countWake()
        if (!claims.claim(now)) {
            hands.beep()
            hands.log(WakeLog.line(now, done = false, why = "another device answered"))
            return false
        }
        hands.buzz()
        val opened = hands.openOverlay(WakeLockScreen.overlaySource(onLock),
            unlockToTalk = onLock == WakeLockScreen.Do.UNLOCK_TO_TALK)
        val why = when {
            !opened -> "overlay not allowed here, offered a tap"
            onLock == WakeLockScreen.Do.UNLOCK_TO_TALK -> "unlock to talk"
            else -> ""
        }
        hands.log(WakeLog.line(now, done = opened, why = why))
        return opened
    }
}
