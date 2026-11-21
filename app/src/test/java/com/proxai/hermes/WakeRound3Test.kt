package com.proxai.hermes

import com.proxai.hermes.data.AppLock
import com.proxai.hermes.data.GateCheck
import com.proxai.hermes.data.OnScreen
import com.proxai.hermes.data.PhoneUnlocks
import com.proxai.hermes.data.VoicePolicy
import com.proxai.hermes.data.VoiceSend
import com.proxai.hermes.data.WakeHold
import com.proxai.hermes.data.WakeLockScreen
import com.proxai.hermes.data.WakeLockScreen.Do
import com.proxai.hermes.data.WakeSettings
import com.proxai.hermes.voice.WakeFlow
import com.proxai.hermes.voice.WakeHands
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * WAKE-PHONE round 3 (Nathan's picks, 11-18 morning): the lock screen is shape C, and the keyword model's licence
 * is "unstated-own-phone". Plus #49 (at most 3 hands-free follow-ups in an untouched wake overlay).
 */
class WakeRound3Test {
    private val now = 1_800_000_000_000L
    private val min = 60_000L
    private val window = WakeLockScreen.DEFAULT_WINDOW_MS

    @After fun clean() = PhoneUnlocks.reset()

    private class Hands : WakeHands {
        val did = mutableListOf<String>()
        val lines = mutableListOf<String>()
        override fun buzz() { did += "buzz" }
        override fun beep() { did += "beep" }
        override fun openOverlay(source: String, unlockToTalk: Boolean): Boolean {
            did += "open:$source" + if (unlockToTalk) "+unlock" else ""
            return true
        }
        override fun log(line: String) { lines += line }
        override fun countWake() {}
    }

    private fun locked(appLockOn: Boolean, lastUnlock: Long, windowMs: Long = window) =
        WakeLockScreen.decide(keyguardLocked = true, now = now, appLockOn = appLockOn, lastPhoneUnlock = lastUnlock,
            windowMs = windowMs)

    // ---- (a) a wake never restarts the app lock's timer -----------------------------------------------------

    @Test fun noLockScreenOutcomeCountsAsOnScreenUntilHeUsesIt() {
        for (d in Do.values()) {
            val source = WakeLockScreen.overlaySource(d)
            assertFalse("$d", VoicePolicy.countsOnScreen(source, used = false))
            assertTrue("$d", VoicePolicy.countsOnScreen(source, used = true))
        }
        var clock = now
        val lock = AppLock({ true }, { min }, { clock }).apply { onScreen(); unlock(); offScreen() }
        val screens = OnScreen<String> { VoicePolicy.countsOnScreen(VoicePolicy.WAKE, used = false) }
        clock += 30_000
        assertFalse(screens.start("wake overlay"))       // lock.onScreen() isn't run: the timer keeps going
        clock += 31_000
        assertTrue(lock.check())
    }

    // ---- C's clock: a real unlock event, in memory only -------------------------------------------------------

    @Test fun theClockIsARealUnlockSeenSinceTheListenerStartedAndNeverStored() {
        PhoneUnlocks.reset()                              // the listener (re)started
        assertEquals(0L, PhoneUnlocks.last)
        assertEquals("nothing seen since the start: not recently unlocked", Do.UNLOCK_TO_TALK,
            locked(appLockOn = false, lastUnlock = PhoneUnlocks.last))
        PhoneUnlocks.seen(now - 5 * min)                  // ACTION_USER_PRESENT
        assertEquals(Do.OPEN_OVERLAY, locked(appLockOn = false, lastUnlock = PhoneUnlocks.last))
        // Nothing about unlocks goes to storage.
        val prefs = MemPrefs()
        val w = WakeSettings(prefs)
        w.enabled = true
        w.handsFreeWindowMs = 10 * min
        assertTrue(prefs.all.keys.none { "unlock" in it })
        val source = listOf(File("src/main/java/com/proxai/hermes/data/Wake.kt"),
            File("app/src/main/java/com/proxai/hermes/data/Wake.kt")).first { it.isFile }.readText()
        val unlocksObject = source.substringAfter("object PhoneUnlocks {").substringBefore("\n}\n")
        assertFalse("PhoneUnlocks keeps no prefs", unlocksObject.contains("prefs") || unlocksObject.contains("SharedPreferences"))
    }

    // ---- (b) app lock ON -------------------------------------------------------------------------------------

    @Test fun withTheAppLockOnItOpensAndTheAppsOwnPromptDecides() {
        // The overlay opens over the lock screen as today, however long ago the phone was unlocked...
        assertEquals(Do.OPEN_OVERLAY, locked(appLockOn = true, lastUnlock = 0L))
        assertEquals(Do.OPEN_OVERLAY, locked(appLockOn = true, lastUnlock = now - 5 * 60 * min))
        // ...and once ProxAI's lock time is up, its own fingerprint prompt shows (the app lock is locked).
        var clock = now
        val lock = AppLock({ true }, { min }, { clock }).apply { onScreen(); unlock(); offScreen() }
        clock += min
        assertTrue(lock.check())
        // After that unlock a wake overlay listens at once (whatever "start listening" says) and counts as used.
        assertTrue(VoicePolicy.listenAfterAppUnlock(VoicePolicy.WAKE, autoListen = false))
        assertFalse(VoicePolicy.listenAfterAppUnlock("power", autoListen = false))   // the power button as before
        assertTrue(VoicePolicy.countsOnScreen(VoicePolicy.WAKE, used = true))
    }

    // ---- (c) app lock OFF ------------------------------------------------------------------------------------

    @Test fun withTheAppLockOffItIsHandsFreeForTheWindowThenUnlockToTalk() {
        assertEquals(Do.OPEN_OVERLAY, locked(appLockOn = false, lastUnlock = now - 29 * min))
        assertEquals(Do.OPEN_OVERLAY, locked(appLockOn = false, lastUnlock = now - 30 * min))
        assertEquals(Do.UNLOCK_TO_TALK, locked(appLockOn = false, lastUnlock = now - 31 * min))
        for (w in WakeLockScreen.WINDOWS) {
            assertEquals(Do.OPEN_OVERLAY, locked(false, now - w + 1, w))
            assertEquals(Do.UNLOCK_TO_TALK, locked(false, now - w - 1, w))
        }
        assertEquals("a clock that went backwards isn't recent", Do.UNLOCK_TO_TALK, locked(false, now + 5 * min))
        // The phone in use: always open and listen.
        assertEquals(Do.OPEN_OVERLAY, WakeLockScreen.decide(false, now, appLockOn = false, lastPhoneUnlock = 0L, windowMs = window))
        // "Unlock to talk" never listens by itself; the overlay listens after the touch and the phone's unlock.
        assertFalse(WakeLockScreen.autoListen(Do.UNLOCK_TO_TALK))
        assertTrue(WakeLockScreen.autoListen(Do.OPEN_OVERLAY))
        // The listener opens it marked "unlock to talk", and the wake's one log line says so.
        val hands = Hands()
        assertTrue(WakeFlow(hands).onHeard(now, WakeHold.NONE, Do.UNLOCK_TO_TALK))
        assertEquals(listOf("buzz", "open:wake+unlock"), hands.did)
        assertTrue(hands.lines.single(), hands.lines.single().endsWith(" from=wake done (unlock to talk)"))
    }

    @Test fun theHandsFreeWindowIsASettingOf10Or30MinutesOr2Hours() {
        val w = WakeSettings(MemPrefs())
        assertEquals(30 * min, w.handsFreeWindowMs)
        w.handsFreeWindowMs = 10 * min
        assertEquals(10 * min, w.handsFreeWindowMs)
        w.handsFreeWindowMs = 120 * min
        assertEquals(120 * min, w.handsFreeWindowMs)
        w.handsFreeWindowMs = 7 * min                     // not one of the three: ignored
        assertEquals(120 * min, w.handsFreeWindowMs)
        assertEquals(listOf("10 min", "30 min", "2 h"), WakeLockScreen.WINDOWS.map { WakeLockScreen.label(it) })
    }

    // ---- (d) the yes-gate rule at every level ----------------------------------------------------------------

    @Test fun theYesGateRuleHoldsWhateverTheLockScreenDid() {
        for (d in Do.values()) {
            val source = WakeLockScreen.overlaySource(d)
            assertEquals(VoicePolicy.WAKE, source)
            assertEquals(VoiceSend.NEEDS_TAP, VoicePolicy.voiceSend(source, GateCheck.WAITING))
            assertEquals(VoiceSend.HOLD, VoicePolicy.voiceSend(source, GateCheck.UNKNOWN))
            assertEquals(VoiceSend.SEND, VoicePolicy.voiceSend(source, GateCheck.NONE))
        }
    }

    // ---- #49: at most 3 hands-free follow-ups in an untouched wake overlay -------------------------------------

    @Test fun anUntouchedWakeOverlayGetsAtMostThreeFollowUps() {
        for (n in 0 until VoicePolicy.WAKE_MAX_FOLLOW_UPS) {
            assertTrue("follow-up ${n + 1}", VoicePolicy.mayFollowUp(VoicePolicy.WAKE, true, used = false, followUpsSoFar = n))
        }
        assertEquals(3, VoicePolicy.WAKE_MAX_FOLLOW_UPS)
        assertFalse(VoicePolicy.mayFollowUp(VoicePolicy.WAKE, true, used = false, followUpsSoFar = 3))
        assertTrue("a touch lifts it", VoicePolicy.mayFollowUp(VoicePolicy.WAKE, true, used = true, followUpsSoFar = 10))
        assertFalse("still only after a reply", VoicePolicy.mayFollowUp(VoicePolicy.WAKE, false, used = true, followUpsSoFar = 0))
        assertTrue("the power button as before", VoicePolicy.mayFollowUp("power", false, used = false, followUpsSoFar = 99))
    }

    // ---- the licence: unstated-own-phone, for the model only ---------------------------------------------------

    private val script: File =
        listOf(File("../scripts/wake-engine.sh"), File("scripts/wake-engine.sh")).first { it.isFile }

    private fun install(engine: String, model: String): String {
        val dir = java.nio.file.Files.createTempDirectory("wake-lic").toFile()
        try {
            val k = File(dir, "keywords.txt").apply { writeText("▁HE Y #0.35 @HEY_PROX\n") }   // made-up tokens
            val p = File(dir, "pins").apply {
                writeText("aar_sha256=${"1".repeat(64)}\nengine_licence=$engine\nmodel_sha256=${"2".repeat(64)}\n" +
                    "model_licence=$model\nkeywords_sha256=${"9".repeat(64)}\n")   // a keywords pin that won't match
            }
            val proc = ProcessBuilder("bash", script.absolutePath, "install").redirectErrorStream(true).apply {
                environment()["WAKE_PINS"] = p.absolutePath
                environment()["WAKE_KEYWORDS"] = k.absolutePath
                environment()["WAKE_MODEL_SUMS"] = File(dir, "sums").absolutePath
            }.start()
            val out = proc.inputStream.bufferedReader().readText()
            assertTrue(proc.waitFor(20, TimeUnit.SECONDS))
            assertEquals(out, 1, proc.exitValue())
            return out
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test fun unstatedOwnPhoneIsAcceptedForTheModelOnly() {
        // The model's licence passes (the refusal comes later, at the made-up keywords pin).
        assertTrue(install("Apache-2.0", "unstated-own-phone").contains("doesn't match keywords_sha256"))
        assertTrue(install("MIT", "unstated-own-phone").contains("doesn't match keywords_sha256"))
        // Never for the engine AAR.
        assertTrue(install("unstated-own-phone", "MIT").contains("engine_licence is 'unstated-own-phone' (only Apache-2.0 MIT)"))
        assertTrue(install("Apache-2.0", "GPL-3.0").contains("model_licence is 'GPL-3.0'"))
        // The NOTICE repeats it with the own-phone line, and the build checks both.
        val text = script.readText()
        assertTrue(text.contains("This APK is for Nathan's own phone only and never goes to anyone else."))
        assertTrue(text.contains("""if [ "${'$'}ml" = "unstated-own-phone" ]"""))
        val gradle = listOf(File("build.gradle.kts"), File("app/build.gradle.kts")).first { it.isFile }.readText()
        assertTrue(gradle.contains("""engineLicence !in setOf("Apache-2.0", "MIT"))"""))
        assertTrue(gradle.contains("""modelLicence !in setOf("Apache-2.0", "MIT", "unstated-own-phone"))"""))
        assertTrue(gradle.contains(""""own phone only" !in says"""))
        // His pick is in the pins: the model only.
        val pins = listOf(File("wake-engine.sha256"), File("app/wake-engine.sha256")).first { it.isFile }.readText()
        assertTrue(pins.contains("\nmodel_licence=unstated-own-phone\n"))
        // The engine's pin is empty until he fills it, then Apache-2.0 or MIT; never unstated-own-phone.
        assertTrue(Regex("(?m)^engine_licence=(Apache-2\\.0|MIT)?$").containsMatchIn(pins))
        assertFalse(pins.contains("\nengine_licence=unstated-own-phone"))
    }
}
