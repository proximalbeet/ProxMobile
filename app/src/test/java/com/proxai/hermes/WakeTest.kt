package com.proxai.hermes

import com.proxai.hermes.assist.WakeTile
import com.proxai.hermes.data.ClaimWindow
import com.proxai.hermes.data.MicGate
import com.proxai.hermes.data.PcNotice
import com.proxai.hermes.data.PcPresence
import com.proxai.hermes.data.PcPresenceFeed
import com.proxai.hermes.data.PhoneCalls
import com.proxai.hermes.data.VoicePolicy
import com.proxai.hermes.data.WakeClaim
import com.proxai.hermes.data.WakeHold
import com.proxai.hermes.data.WakeInputs
import com.proxai.hermes.data.WakeLog
import com.proxai.hermes.data.WakeMute
import com.proxai.hermes.data.WakeRules
import com.proxai.hermes.data.WakeSettings
import com.proxai.hermes.data.CallWatcher
import com.proxai.hermes.voice.MicSource
import com.proxai.hermes.voice.WakeEngine
import com.proxai.hermes.voice.WakeFlow
import com.proxai.hermes.voice.WakeHands
import com.proxai.hermes.voice.WakeLoop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WAKE-PHONE (Nathan 11-14 / 11-18): "Hey Prox" on the phone. The stop rules, the tile's mutes, the switch's
 * default, the pre-wake sound that never leaves memory, a fake wake opening the overlay, and the log line.
 */
class WakeTest {
    private val now = 1_800_000_000_000L
    private val listening = WakeInputs(enabled = true, micAllowed = true, noticeVisible = true, engineReady = true,
        muteUntil = 0L, now = now, inCall = false, presence = PcPresence.UNKNOWN, batteryPct = 80, charging = false,
        micBusy = false)

    // ---- fakes -------------------------------------------------------------------------------------------

    /** Hands out loud non-zero "sound"; counts reads. */
    private class FakeMic(private val stopAfter: Int = Int.MAX_VALUE) : MicSource {
        var reads = 0
        var closed = false
        override fun read(into: FloatArray): Int {
            if (reads >= stopAfter) return -1
            reads++
            into.fill(0.5f)
            return into.size
        }
        override fun close() { closed = true }
    }

    /** "Hears" the word on its [fireOn]th chunk (0 = never); keeps what it was fed only to prove where it went. */
    private class FakeEngine(private val fireOn: Int = 0) : WakeEngine {
        override val sampleRate = 16_000
        var fed = 0
        var resets = 0
        override fun accept(samples: FloatArray, count: Int): Boolean {
            fed++
            return fireOn > 0 && fed == fireOn
        }
        override fun reset() { resets++ }
        override fun close() {}
    }

    private class FakeHands(private val opens: Boolean = true) : WakeHands {
        val did = mutableListOf<String>()
        val lines = mutableListOf<String>()
        override fun buzz() { did += "buzz" }
        override fun beep() { did += "beep" }
        override fun openOverlay(source: String, unlockToTalk: Boolean): Boolean {
            did += "open:$source" + if (unlockToTalk) "+unlock" else ""
            return opens
        }
        override fun log(line: String) { lines += line }
        var wakes = 0
        override fun countWake() { wakes++ }
    }

    // ---- the switch ----------------------------------------------------------------------------------------

    @Test fun theSwitchIsOffByDefaultAndNothingIsMuted() {
        val w = WakeSettings(MemPrefs())
        assertFalse(w.enabled)
        assertEquals(0L, w.muteUntil)
        assertEquals(WakeHold.OFF, WakeRules.decide(listening.copy(enabled = w.enabled)))
        w.enabled = true
        assertTrue(w.enabled)
    }

    // ---- the stop rules ------------------------------------------------------------------------------------

    @Test fun itListensWhenNothingStopsIt() {
        assertEquals(WakeHold.NONE, WakeRules.decide(listening))
        assertEquals(WakeHold.NONE, WakeRules.decide(listening.copy(presence = PcPresence.AWAY)))
        // Unknown counts as away.
        assertEquals(WakeHold.NONE, WakeRules.decide(listening.copy(presence = PcPresence.UNKNOWN)))
    }

    @Test fun eachStopRuleStopsTheListening() {
        val stops = mapOf(
            WakeHold.CALL to listening.copy(inCall = true),
            WakeHold.DESK to listening.copy(presence = PcPresence.DESK),
            WakeHold.BATTERY to listening.copy(batteryPct = 12),
            WakeHold.MUTED to listening.copy(muteUntil = WakeMute.forAnHour(now)),
            WakeHold.BUSY to listening.copy(micBusy = true),
            WakeHold.OFF to listening.copy(enabled = false),
            WakeHold.NO_MIC to listening.copy(micAllowed = false),
            WakeHold.NOTICE_OFF to listening.copy(noticeVisible = false),
            WakeHold.NO_ENGINE to listening.copy(engineReady = false),
        )
        for ((hold, inputs) in stops) {
            assertEquals(hold, WakeRules.decide(inputs))
            // The loop runs while the rules say listen; flip the input after 3 chunks and it stops, mic and all.
            val mic = FakeMic()
            val engine = FakeEngine()
            var current = listening
            val heard = WakeLoop(mic, engine).runUntilWord {
                if (mic.reads == 3) current = inputs
                WakeRules.decide(current) == WakeHold.NONE
            }
            assertFalse("$hold: no wake", heard)
            assertEquals("$hold: stopped right after the change", 3, mic.reads)
            assertEquals(1, engine.resets)
        }
    }

    @Test fun lowBatteryIsAtOrBelow15PercentAndNotCharging() {
        assertTrue(WakeRules.batteryLow(15, charging = false))
        assertFalse(WakeRules.batteryLow(16, charging = false))
        assertFalse(WakeRules.batteryLow(5, charging = true))
        assertFalse(WakeRules.batteryLow(-1, charging = false))   // unknown isn't low
    }

    @Test fun callsComeFromCallWatchersStatesAndTheAudioMode() {
        assertTrue(CallWatcher.inCall(CallWatcher.RINGING))
        assertTrue(CallWatcher.inCall(CallWatcher.OFFHOOK))
        assertFalse(CallWatcher.inCall(CallWatcher.IDLE))
        PhoneCalls.onState(CallWatcher.OFFHOOK)
        assertTrue(PhoneCalls.active.value)
        PhoneCalls.onState(CallWatcher.IDLE)
        assertFalse(PhoneCalls.active.value)
        assertFalse(WakeRules.callMode(0))                         // MODE_NORMAL
        for (m in 1..3) assertTrue(WakeRules.callMode(m))           // ringtone, in call, in communication
    }

    @Test fun thePcsDeskWordComesFromTheNoticesFeedAndIsNeverShown() {
        fun n(id: Int, kind: String) = PcNotice(id, "2026-11-18T01:00:00", kind, kind, "")
        assertEquals(PcPresence.DESK, PcPresenceFeed.from(listOf(n(1, "away"), n(2, "desk")), PcPresence.UNKNOWN))
        assertEquals(PcPresence.AWAY, PcPresenceFeed.from(listOf(n(3, "desk"), n(4, "away")), PcPresence.DESK))
        assertEquals(PcPresence.DESK, PcPresenceFeed.from(listOf(n(5, "brief")), PcPresence.DESK))   // no word: unchanged
        assertEquals(listOf(5), PcPresenceFeed.shown(listOf(n(4, "away"), n(5, "brief"), n(6, "desk"))).map { it.id })
        PcPresenceFeed.seen(listOf(n(7, "desk")))
        assertEquals(PcPresence.DESK, PcPresenceFeed.state.value)
        PcPresenceFeed.lost()                                        // the long poll failed: unknown = away
        assertEquals(PcPresence.UNKNOWN, PcPresenceFeed.state.value)
        assertEquals(WakeHold.NONE, WakeRules.decide(listening.copy(presence = PcPresenceFeed.state.value)))
    }

    @Test fun proxaisOwnMicUseHoldsTheListenerOff() {
        val before = MicGate.holders.value
        MicGate.hold()
        assertTrue(MicGate.held)
        MicGate.release()
        assertEquals(before, MicGate.holders.value)
        MicGate.release(); MicGate.release()                        // never below zero
        assertTrue(MicGate.holders.value >= 0)
    }

    // ---- the tile ------------------------------------------------------------------------------------------

    @Test fun theTileMutesForOneHour() {
        assertEquals(WakeTile.Tap.ASK_MUTE, WakeTile.tap(enabled = true, muteUntil = 0L, now = now, phoneLocked = false, serviceUp = true))
        val until = WakeTile.muteChoice(0, now)
        assertEquals(now + 3_600_000L, until)
        assertEquals(WakeHold.MUTED, WakeRules.decide(listening.copy(muteUntil = until, now = now + 59 * 60_000L)))
        assertEquals(WakeTile.Look.MUTED to "60 min", WakeTile.look(true, until, now, serviceUp = true))
        assertEquals(WakeTile.Tap.UNMUTE, WakeTile.tap(true, until, now + 1, phoneLocked = false, serviceUp = true))
        // An hour later it listens again by itself.
        assertEquals(WakeHold.NONE, WakeRules.decide(listening.copy(muteUntil = until, now = until)))
        assertEquals(WakeTile.Look.ON to "On", WakeTile.look(true, until, until, serviceUp = true))
    }

    @Test fun theTileMutesUntilTurnedBackOn() {
        val until = WakeTile.muteChoice(1, now)
        assertEquals(WakeMute.UNTIL_ON, until)
        val aYearLater = now + 365L * 24 * 3_600_000L
        assertEquals(WakeHold.MUTED, WakeRules.decide(listening.copy(muteUntil = until, now = aYearLater)))
        assertEquals("Muted until you turn it back on", WakeMute.label(until, aYearLater))
        assertEquals(WakeTile.Look.MUTED to "Muted", WakeTile.look(true, until, aYearLater, serviceUp = true))
        assertEquals(WakeTile.Tap.UNMUTE, WakeTile.tap(true, until, aYearLater, phoneLocked = false, serviceUp = true))   // a tap turns it back on
        assertEquals(WakeHold.NONE, WakeRules.decide(listening.copy(muteUntil = 0L, now = aYearLater)))
    }

    @Test fun withTheSwitchOffTheTileOnlyOpensProxAI() {
        assertEquals(WakeTile.Tap.OPEN_APP, WakeTile.tap(enabled = false, muteUntil = 0L, now = now, phoneLocked = false, serviceUp = true))
        assertEquals(WakeTile.Look.OFF, WakeTile.look(false, 0L, now, serviceUp = true).first)
    }

    // ---- the sound before the word ---------------------------------------------------------------------------

    @Test fun preWakeSoundOnlyGoesToTheEngineAndIsDroppedAfterTheWord() {
        val mic = FakeMic()
        val engine = FakeEngine(fireOn = 5)
        val loop = WakeLoop(mic, engine)
        assertTrue(loop.runUntilWord { true })
        assertEquals(5, mic.reads)
        assertEquals(5, engine.fed)                     // every chunk went to the engine, and only there
        assertTrue(loop.chunkIsClear())                 // the in-memory chunk is zeroed
        assertEquals(1, engine.resets)                  // and the engine's own window dropped
    }

    @Test fun preWakeSoundIsDroppedWhenTheMicGoesAwayToo() {
        val mic = FakeMic(stopAfter = 2)
        val engine = FakeEngine()
        val loop = WakeLoop(mic, engine)
        assertFalse(loop.runUntilWord { true })
        assertTrue(loop.chunkIsClear())
        assertEquals(1, engine.resets)
    }

    @Test fun nothingAfterTheEngineCanCarrySound() {
        // The wake's hands (overlay, buzz, beep, log) and the claim take no sound: no array of samples anywhere.
        val soundTypes: Set<Class<*>> = setOf(FloatArray::class.java, ShortArray::class.java, ByteArray::class.java)
        for (type in listOf(WakeHands::class.java, WakeClaim::class.java, WakeFlow::class.java)) {
            for (m in type.declaredMethods) {
                assertTrue("${type.simpleName}.${m.name} takes sound", m.parameterTypes.none { it in soundTypes })
                assertFalse("${type.simpleName}.${m.name} returns sound", m.returnType in soundTypes)
            }
        }
        // WakeLoop hands its chunk to nobody but the engine.
        for (m in WakeLoop::class.java.methods) assertFalse(m.name, m.returnType in soundTypes)
    }

    // ---- a wake --------------------------------------------------------------------------------------------

    @Test fun aFakeWakeBuzzesThenOpensTheSameOverlayMarkedAsAWake() {
        val hands = FakeHands()
        val heard = WakeLoop(FakeMic(), FakeEngine(fireOn = 2)).runUntilWord { true }
        assertTrue(heard)
        assertTrue(WakeFlow(hands).onHeard(now))
        assertEquals(listOf("buzz", "open:wake"), hands.did)
        assertEquals(1, hands.wakes)
        assertEquals(1, hands.lines.size)
        assertTrue(hands.lines[0].endsWith(" from=wake done"))
        // That overlay uses the on-device recognizer only and never offers the screen.
        assertTrue(VoicePolicy.onDeviceOnly(VoicePolicy.WAKE))
        assertFalse(VoicePolicy.mayLookAtScreen(VoicePolicy.WAKE))
        assertFalse(VoicePolicy.onDeviceOnly("power"))
        assertTrue(VoicePolicy.mayLookAtScreen("power"))
    }

    @Test fun whenAndroidWontOpenTheOverlayTheWakeIsLoggedAsRefused() {
        val hands = FakeHands(opens = false)
        assertFalse(WakeFlow(hands).onHeard(now))
        assertTrue(hands.lines.single().contains(" from=wake refused ("))
    }

    @Test fun theFirstDeviceToClaimAWakeWinsForThreeSeconds() {
        val w = ClaimWindow()
        assertTrue(w.claim("phone", now))
        assertFalse(w.claim("pc", now + 2_999))
        assertTrue(w.claim("phone", now + 1_000))
        assertTrue(w.claim("pc", now + 3_000))          // the window is over
        // A lost claim: one beep, no overlay, one refused line.
        val hands = FakeHands()
        val lost = object : WakeClaim { override fun claim(at: Long) = false }
        assertFalse(WakeFlow(hands, lost).onHeard(now))
        assertEquals(listOf("beep"), hands.did)
        assertTrue(hands.lines.single().contains("refused (another device answered)"))
    }

    @Test fun theLogLineIsTimeSourceAndOutcomeOnly() {
        val done = WakeLog.line(now, done = true)
        assertTrue(done, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} from=wake done""").matches(done))
        val refused = WakeLog.line(now, done = false, why = "another device answered")
        assertTrue(refused, Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} from=wake refused \(another device answered\)""").matches(refused))
        // Whatever is passed as the reason, it can't smuggle sound or a long text into the log.
        val sneaky = WakeLog.line(now, done = false, why = FloatArray(400) { 0.5f }.joinToString(",") + "\n[0.1, 0.2]")
        assertTrue(sneaky.length <= 100)
        assertFalse(sneaky.contains("\n"))
        assertFalse(sneaky.contains("["))
    }

    @Test fun theNotificationAlwaysSaysWhatItsDoing() {
        assertEquals("Listening for “Hey Prox”", WakeRules.notice(WakeHold.NONE, 0L, now).first)
        assertTrue(WakeRules.notice(WakeHold.CALL, 0L, now).second.contains("on a call"))
        assertTrue(WakeRules.notice(WakeHold.DESK, 0L, now).second.contains("at your PC"))
        assertTrue(WakeRules.notice(WakeHold.BATTERY, 0L, now).second.contains("battery low"))
        assertTrue(WakeRules.notice(WakeHold.MUTED, WakeMute.forAnHour(now), now).second.startsWith("Muted for 60 min"))
    }
}
