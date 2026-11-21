package com.proxai.hermes

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.proxai.hermes.assist.WakeTile
import com.proxai.hermes.data.AppLock
import com.proxai.hermes.data.DictationRules
import com.proxai.hermes.data.GateCheck
import com.proxai.hermes.data.KeywordLine
import com.proxai.hermes.data.OnScreen
import com.proxai.hermes.data.PcPresence
import com.proxai.hermes.data.PcStatsClient
import com.proxai.hermes.data.Settings
import com.proxai.hermes.data.VoicePolicy
import com.proxai.hermes.data.VoiceSend
import com.proxai.hermes.data.WakeHold
import com.proxai.hermes.data.WakeInputs
import com.proxai.hermes.data.WakeLockScreen
import com.proxai.hermes.data.WakeMute
import com.proxai.hermes.data.WakeRules
import com.proxai.hermes.data.WakeSettings
import com.proxai.hermes.voice.WakeFlow
import com.proxai.hermes.voice.WakeHands
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * WAKE-PHONE round 2 (the security review, 11-18): yes-gates, the app lock's timer, false wakes, the tile on a
 * locked phone, hidden listening, the install pins and the race at the word. One test (or more) each.
 */
class WakeRound2Test {
    private val now = 1_800_000_000_000L
    private val listening = WakeInputs(enabled = true, micAllowed = true, noticeVisible = true, engineReady = true,
        muteUntil = 0L, now = now, inCall = false, presence = PcPresence.UNKNOWN, batteryPct = 80, charging = false,
        micBusy = false)

    private class Hands : WakeHands {
        val did = mutableListOf<String>()
        val lines = mutableListOf<String>()
        var wakes = 0
        override fun buzz() { did += "buzz" }
        override fun beep() { did += "beep" }
        override fun openOverlay(source: String, unlockToTalk: Boolean): Boolean {
            did += "open:$source" + if (unlockToTalk) "+unlock" else ""
            return true
        }
        override fun log(line: String) { lines += line }
        override fun countWake() { wakes++ }
    }

    private fun settings(): Settings {
        val prefs = MemPrefs()
        return Settings(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        }).apply { apiKey = "k".repeat(40) }
    }

    // ---- 1. yes-gates ------------------------------------------------------------------------------------

    @Test fun aWakeOverlayNeverAnswersAWaitingGateByVoice() {
        assertEquals(VoiceSend.SEND, VoicePolicy.voiceSend(VoicePolicy.WAKE, GateCheck.NONE))
        assertEquals(VoiceSend.NEEDS_TAP, VoicePolicy.voiceSend(VoicePolicy.WAKE, GateCheck.WAITING))
        // The PC couldn't be asked: nothing goes out by voice either.
        assertEquals(VoiceSend.HOLD, VoicePolicy.voiceSend(VoicePolicy.WAKE, GateCheck.UNKNOWN))
        // Power-button and tile overlays stay exactly as they are.
        for (g in GateCheck.values()) {
            assertEquals(VoiceSend.SEND, VoicePolicy.voiceSend("power", g))
            assertEquals(VoiceSend.SEND, VoicePolicy.voiceSend("tile", g))
        }
    }

    @Test fun theGateCheckTellsNoneFromNoAnswer() {
        val pc = MockWebServer()
        pc.enqueue(MockResponse().setBody("""{"approval": null}"""))
        pc.enqueue(MockResponse().setBody("""{"approval": {"id": "a1", "session": "android-x", "plugin": "proxai-apps",
            "question": "Install Habit Tracker?", "detail": "", "expires": 1900000000}}"""))
        pc.enqueue(MockResponse().setResponseCode(503))
        pc.enqueue(MockResponse().setBody("<html>proxy error</html>"))
        pc.enqueue(MockResponse().setBody("""{"ok": true}"""))
        pc.start()
        try {
            val s = settings()
            s.serverUrl = pc.url("/").toString()
            val client = PcStatsClient(s)
            assertNull(client.approvalOrThrow(listOf("android-x")))                       // none waiting
            assertEquals("a1", client.approvalOrThrow(listOf("android-x"))?.id)            // one waiting
            assertEquals("/apps/v1/approvals?session=android-x", pc.takeRequest(1, TimeUnit.SECONDS)?.path)
            for (what in listOf("HTTP 503", "not JSON", "JSON without \"approval\"")) {
                try {
                    client.approvalOrThrow(listOf("android-x"))
                    fail("$what must not look like \"none waiting\"")
                } catch (e: IOException) { /* no answer: the wake overlay sends nothing */ }
            }
        } finally {
            pc.shutdown()
        }
    }

    // ---- 2. the app lock's timer ---------------------------------------------------------------------------

    @Test fun anUntouchedWakeOverlayDoesntStopTheAppLocksTimer() {
        var clock = now
        val lock = AppLock({ true }, { 60_000L }, { clock }).apply { onScreen(); unlock(); offScreen() }
        // Screens: "main", an untouched wake overlay, and a power-button overlay.
        var wakeUsed = false
        val screens = OnScreen<String> { name ->
            when (name) {
                "wake" -> VoicePolicy.countsOnScreen(VoicePolicy.WAKE, wakeUsed)
                else -> VoicePolicy.countsOnScreen("power", used = false)
            }
        }
        clock += 30_000
        assertFalse("an untouched wake overlay isn't on screen for the lock", screens.start("wake"))
        assertTrue("but a reply needs no notification", screens.anyVisible)
        clock += 31_000                                    // past the lock time, the overlay still open
        assertTrue("the word alone never keeps ProxAI unlocked", lock.check())
        // A touch makes it count: the lock's onScreen() runs (and applies the timer first, so it stays locked).
        wakeUsed = true
        assertTrue(screens.promote("wake"))
        lock.onScreen()
        assertTrue(lock.check())
        lock.unlock()                                      // the PIN or a fingerprint in the overlay
        assertFalse(lock.check())
        assertTrue("the last counted screen going away starts the timer", screens.stop("wake"))
        lock.offScreen()
        clock += 61_000
        assertTrue(lock.check())
        // A power-button overlay counts at once, as before.
        assertTrue(screens.start("power"))
        assertFalse(VoicePolicy.countsOnScreen(VoicePolicy.WAKE, used = false))
        assertTrue(VoicePolicy.countsOnScreen("power", used = false))
    }

    // ---- 3. false wakes ------------------------------------------------------------------------------------

    @Test fun aWakeDictationHasACapAndNoSecondListen() {
        val wake = VoicePolicy.dictation(VoicePolicy.WAKE)
        assertTrue(wake.capMs in 30_000L..45_000L)
        assertTrue(wake.hardCap)
        assertFalse(wake.listenAgainWhenSilent)
        assertEquals(DictationRules(), VoicePolicy.dictation("power"))   // the power button as before
        assertTrue(DictationRules().listenAgainWhenSilent)
    }

    @Test fun anUntouchedIdleWakeOverlayClosesByItself() {
        assertFalse(VoicePolicy.closeIdle(VoicePolicy.WAKE, used = false, idleMs = 59_999))
        assertTrue(VoicePolicy.closeIdle(VoicePolicy.WAKE, used = false, idleMs = 60_000))
        assertFalse("he's using it", VoicePolicy.closeIdle(VoicePolicy.WAKE, used = true, idleMs = 600_000))
        assertFalse("the power button as before", VoicePolicy.closeIdle("power", used = false, idleMs = 600_000))
    }

    @Test fun handsFreeFollowUpsOnlyAfterAReplyToARealRequest() {
        assertFalse(VoicePolicy.mayFollowUp(VoicePolicy.WAKE, answeredRequestPending = false))
        assertTrue(VoicePolicy.mayFollowUp(VoicePolicy.WAKE, answeredRequestPending = true))
        assertTrue(VoicePolicy.mayFollowUp("power", answeredRequestPending = false))
    }

    @Test fun theKeywordsLineCarriesItsOwnThreshold() {
        val good = "▁HE Y ▁P RO X :1.5 #0.35 @HEY_PROX"   // made-up tokens: the real ones come from text2token
        assertTrue(KeywordLine.valid(good))
        assertEquals(0.35f, KeywordLine.threshold(good))
        assertEquals(listOf("▁HE", "Y", "▁P", "RO", "X"), KeywordLine.tokens(good))
        assertFalse("no threshold", KeywordLine.valid("▁HE Y ▁P RO X :1.5 @HEY_PROX"))
        assertFalse("too low", KeywordLine.valid("▁HE Y ▁P RO X #0.05 @HEY_PROX"))
        assertFalse("too high", KeywordLine.valid("▁HE Y ▁P RO X #0.95 @HEY_PROX"))
        assertTrue(KeywordLine.valid("▁HE Y ▁P RO X #0.9 @HEY_PROX"))
        assertTrue(KeywordLine.valid("▁HE Y ▁P RO X #0.1 @HEY_PROX"))
        assertFalse("another word", KeywordLine.valid("▁HE Y #0.35 @HEY"))
        assertFalse("two lines", KeywordLine.valid("$good\n▁OK #0.3 @OK"))
        assertFalse("no tokens", KeywordLine.valid("#0.35 @HEY_PROX"))
    }

    @Test fun wakesAreCountedPerDayAsANumberOnly() {
        val w = WakeSettings(MemPrefs())
        val day = 20_410L
        assertEquals(0, w.wakesOn(day))
        assertEquals(1, w.countWake(day))
        assertEquals(2, w.countWake(day))
        assertEquals(2, w.wakesOn(day))
        assertEquals(0, w.wakesOn(day + 1))                // a new day starts at 0
        assertEquals(1, w.countWake(day + 1))
        assertTrue(WakeRules.notice(WakeHold.NONE, 0L, now, wakesToday = 3).second.endsWith("Woken 3× today."))
        // A wake the rules drop isn't counted; a lost claim is (the word did fire).
        val hands = Hands()
        WakeFlow(hands).onHeard(now, WakeHold.CALL)
        assertEquals(0, hands.wakes)
        WakeFlow(hands).onHeard(now)
        assertEquals(1, hands.wakes)
    }

    // ---- 4. the tile on a locked phone ----------------------------------------------------------------------

    @Test fun onALockedPhoneUnmutingNeedsTheUnlockAndMutingStaysFree() {
        val muted = WakeMute.forAnHour(now)
        assertEquals(WakeTile.Tap.UNLOCK_THEN_UNMUTE, WakeTile.tap(true, muted, now, phoneLocked = true, serviceUp = true))
        assertEquals(WakeTile.Tap.UNLOCK_THEN_UNMUTE, WakeTile.tap(true, WakeMute.UNTIL_ON, now, phoneLocked = true, serviceUp = false))
        assertEquals(WakeTile.Tap.UNMUTE, WakeTile.tap(true, muted, now, phoneLocked = false, serviceUp = true))
        // Muting: one tap, no dialog over the lock screen, no unlock.
        assertEquals(WakeTile.Tap.MUTE_UNTIL_ON, WakeTile.tap(true, 0L, now, phoneLocked = true, serviceUp = true))
        assertEquals(WakeTile.Tap.MUTE_UNTIL_ON, WakeTile.tap(true, 0L, now, phoneLocked = true, serviceUp = false))
        assertEquals(WakeTile.Tap.ASK_MUTE, WakeTile.tap(true, 0L, now, phoneLocked = false, serviceUp = true))
    }

    @Test fun withTheServiceDeadTheTileSaysSoAndOpensProxAI() {
        assertEquals(WakeTile.Look.STOPPED to "Open ProxAI to start", WakeTile.look(true, 0L, now, serviceUp = false))
        assertEquals(WakeTile.Tap.OPEN_APP, WakeTile.tap(true, 0L, now, phoneLocked = false, serviceUp = false))
        // Muted stays muted-looking either way (the unmute goes through the unlock when locked).
        assertEquals(WakeTile.Look.MUTED, WakeTile.look(true, WakeMute.UNTIL_ON, now, serviceUp = false).first)
    }

    // ---- 5. hidden listening -------------------------------------------------------------------------------

    @Test fun withItsNotificationHiddenItDoesntListen() {
        assertTrue(WakeRules.noticeVisible(notificationsOn = true, channelImportance = 2))     // IMPORTANCE_LOW
        assertFalse(WakeRules.noticeVisible(notificationsOn = false, channelImportance = 2))   // app notifications off
        assertFalse(WakeRules.noticeVisible(notificationsOn = true, channelImportance = 0))    // channel blocked
        assertFalse(WakeRules.noticeVisible(notificationsOn = true, channelImportance = null)) // channel missing
        assertEquals(WakeHold.NOTICE_OFF, WakeRules.decide(listening.copy(noticeVisible = false)))
        assertEquals("“Hey Prox” paused" to "Not listening: notifications are off.",
            WakeRules.notice(WakeHold.NOTICE_OFF, 0L, now))
    }

    // ---- 6. the install pins ---------------------------------------------------------------------------------

    private val script: File =
        listOf(File("../scripts/wake-engine.sh"), File("scripts/wake-engine.sh")).first { it.isFile }

    private fun install(pins: String, keywords: String?): Pair<Int, String> {
        val dir = java.nio.file.Files.createTempDirectory("wake-pins").toFile()
        try {
            val p = File(dir, "pins").apply { writeText(pins) }
            val k = File(dir, "keywords.txt").apply { if (keywords != null) writeText(keywords) }
            val proc = ProcessBuilder("bash", script.absolutePath, "install")
                .redirectErrorStream(true)
                .apply {
                    environment()["WAKE_PINS"] = p.absolutePath
                    environment()["WAKE_KEYWORDS"] = k.absolutePath
                    environment()["WAKE_MODEL_SUMS"] = File(dir, "sums").absolutePath
                }.start()
            val out = proc.inputStream.bufferedReader().readText()
            assertTrue("the refusal comes before any download", proc.waitFor(20, TimeUnit.SECONDS))
            return proc.exitValue() to out
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun sha(text: String): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun installRefusesUnpinnedOrUnlicensedOrUnmatchedKeywordsWithoutTheNetwork() {
        val line = "▁HE Y ▁P RO X :1.5 #0.35 @HEY_PROX\n"   // made-up tokens, only for the refusals
        val base = "aar_sha256=${"1".repeat(64)}\nengine_licence=Apache-2.0\nmodel_sha256=${"2".repeat(64)}\n"
        val (empty, emptyOut) = install(base + "model_licence=MIT\nkeywords_sha256=\n", line)
        assertEquals(1, empty); assertTrue(emptyOut, emptyOut.contains("still has an empty value"))
        val (gpl, gplOut) = install(base + "model_licence=GPL-3.0\nkeywords_sha256=${sha(line)}\n", line)
        assertEquals(1, gpl); assertTrue(gplOut, gplOut.contains("only Apache-2.0 MIT"))
        val (missing, missingOut) = install(base + "model_licence=MIT\nkeywords_sha256=${sha(line)}\n", null)
        assertEquals(1, missing); assertTrue(missingOut, missingOut.contains("is missing"))
        val (changed, changedOut) = install(base + "model_licence=MIT\nkeywords_sha256=${sha(line)}\n",
            line.replace("#0.35", "#0.15"))
        assertEquals(1, changed); assertTrue(changedOut, changedOut.contains("doesn't match keywords_sha256"))
        // The script's threshold rule is the app's (0.1 .. 0.9): a line the phone would refuse never installs.
        val high = "▁HE Y ▁P RO X #0.95 @HEY_PROX\n"
        val (tooHigh, tooHighOut) = install(base + "model_licence=MIT\nkeywords_sha256=${sha(high)}\n", high)
        assertEquals(1, tooHigh); assertTrue(tooHighOut, tooHighOut.contains("#threshold"))
        val bare = "▁HE Y ▁P RO X @HEY_PROX\n"
        val (noThreshold, noThresholdOut) = install(base + "model_licence=MIT\nkeywords_sha256=${sha(bare)}\n", bare)
        assertEquals(1, noThreshold); assertTrue(noThresholdOut, noThresholdOut.contains("#threshold"))
    }

    @Test fun noPyPiCodeRunsAtInstallAndItWritesTheFiveModelSums() {
        val text = script.readText()
        val body = text.substringAfter("\ninstall() {").substringBefore("\n}\n")
        assertFalse("install runs no uvx", body.contains("uvx"))
        assertFalse("install runs no pip", Regex("""\bpip3?\b""").containsMatchIn(body))
        assertFalse("install runs no text2token", body.contains("text2token"))
        for (f in listOf("encoder.onnx", "decoder.onnx", "joiner.onnx", "tokens.txt", "keywords.txt")) {
            assertTrue(f, text.contains(f))
        }
        assertTrue(body.contains("MODEL_SUMS"))
        // The pins file names the keywords line's sha256, and the builder filled in no value.
        val pins = listOf(File("wake-engine.sha256"), File("app/wake-engine.sha256")).first { it.isFile }.readText()
        assertTrue(pins.contains("\nkeywords_sha256="))
    }

    // ---- 7. the race at the word ------------------------------------------------------------------------------

    @Test fun aWordThatArrivesAfterTheRulesChangedIsDropped() {
        for (hold in listOf(WakeHold.CALL, WakeHold.MUTED, WakeHold.DESK, WakeHold.BUSY, WakeHold.NOTICE_OFF)) {
            val hands = Hands()
            assertFalse(WakeFlow(hands).onHeard(now, hold))
            assertTrue("$hold: no buzz, no overlay", hands.did.isEmpty())
            assertTrue(hands.lines.single().contains("from=wake refused ("))
        }
        val hands = Hands()
        assertTrue(WakeFlow(hands).onHeard(now, WakeRules.decide(listening)))
        assertEquals(listOf("buzz", "open:wake"), hands.did)
    }

    // ---- the seam left for his 9 am pick ---------------------------------------------------------------------

    @Test fun anUnlockedPhoneAlwaysOpensAndListens() {
        // Round 3 filled the seam (shape C: WakeRound3Test); an unlocked phone is unchanged.
        val d = WakeLockScreen.decide(keyguardLocked = false, now = now, appLockOn = false, lastPhoneUnlock = 0L,
            windowMs = WakeLockScreen.DEFAULT_WINDOW_MS)
        assertEquals(WakeLockScreen.Do.OPEN_OVERLAY, d)
        val hands = Hands()
        assertTrue(WakeFlow(hands).onHeard(now, WakeHold.NONE, d))
        assertNotNull(hands.did.firstOrNull { it == "open:wake" })
    }
}
