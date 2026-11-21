package com.proxai.hermes

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.proxai.hermes.data.AppLock
import com.proxai.hermes.data.CallWatcher
import com.proxai.hermes.data.CallWatcher.Companion.IDLE
import com.proxai.hermes.data.CallWatcher.Companion.OFFHOOK
import com.proxai.hermes.data.CallWatcher.Companion.RINGING
import com.proxai.hermes.data.LineClient
import com.proxai.hermes.data.Settings
import com.proxai.hermes.ui.SettingsSummary
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * ProxAI line (0.9.9, Phase 14c; Nathan 11-02: "Keep it unlocked as the phone conversation goes on and then
 * let it relock after the call is over back to the timer set"), and the new Settings list's summaries (F2).
 */
class CallLineTest {
    private var now = 1_000_000L
    private val timeout = 60_000L
    private fun unlockedAndAway(): AppLock = AppLock({ true }, { timeout }, { now }).apply {
        onScreen(); unlock(); offScreen()   // he left ProxMobile to make the call
    }

    // ---- the app lock's hold --------------------------------------------------------------------------

    @Test fun aHeldCallStaysOpenPastTheTimeoutAndRelocksOneTimeoutAfterItEnds() {
        val l = unlockedAndAway()
        assertTrue(l.holdForCall())
        now += 25 * 60_000L                                // a long call, far past the lock time
        assertFalse(l.check())
        assertTrue(l.appsAllowed())
        l.endCallHold()
        now += timeout - 1
        assertFalse(l.check())                             // the usual timer runs from the end of the call
        now += 1
        assertTrue(l.check())                              // exactly one timeout after it ended
    }

    @Test fun onScreenWhenTheCallEndsItStaysOpen() {
        val l = unlockedAndAway()
        l.holdForCall()
        now += 10 * 60_000L
        l.onScreen()                                       // back in ProxMobile during the call
        l.endCallHold()
        now += 10 * 60_000L
        assertFalse(l.check())
    }

    @Test fun aCallThatStartsLockedChangesNothing() {
        val l = unlockedAndAway()
        now += timeout                                     // locked before the call
        assertFalse(l.holdForCall())
        assertTrue(l.check())
        l.endCallHold()
        assertTrue(l.check())
        val fresh = AppLock({ true }, { timeout }, { now })   // a new process starts locked: no hold
        assertFalse(fresh.holdForCall())
    }

    @Test fun lockNowStillLocksDuringACall() {
        val l = unlockedAndAway()
        l.holdForCall()
        l.lockNow()
        assertTrue(l.check())
        assertFalse(l.heldForCall)
    }

    // ---- the watcher's transitions ---------------------------------------------------------------------

    private class Told : ArrayList<String>()

    @Test fun outgoingCallHoldsAndTellsThePcStartThenEnd() {
        val l = unlockedAndAway()
        val told = Told()
        val w = CallWatcher(l) { told += it }
        w.onState(IDLE)
        w.onState(OFFHOOK)                                 // dialled out: IDLE -> OFFHOOK, no RINGING
        assertTrue(l.heldForCall)
        assertEquals(listOf("start"), told)
        now += 5 * 60_000L
        assertFalse(l.check())
        w.onState(IDLE)
        assertFalse(l.heldForCall)
        assertEquals(listOf("start", "end"), told)
    }

    @Test fun incomingCallHoldsButSendsNoStart() {
        val l = unlockedAndAway()
        val told = Told()
        val w = CallWatcher(l) { told += it }
        w.onState(RINGING)
        w.onState(OFFHOOK)
        assertTrue(l.heldForCall)
        assertEquals(emptyList<String>(), told)            // the PIN skip is only for his own outgoing call
        w.onState(IDLE)
        assertEquals(listOf("end"), told)
        // The next outgoing call is outgoing again (RINGING was forgotten at IDLE).
        w.onState(OFFHOOK)
        assertEquals(listOf("end", "start"), told)
    }

    @Test fun lockedAtTheStartMeansNothingHeldNothingSent() {
        val l = unlockedAndAway()
        now += timeout
        val told = Told()
        val w = CallWatcher(l) { told += it }
        w.onState(OFFHOOK)
        w.onState(IDLE)
        assertFalse(l.heldForCall)
        assertTrue(told.isEmpty())
        assertTrue(l.check())
    }

    @Test fun appLockOffStillSkipsThePin() {
        // No app lock at all = ProxMobile is always unlocked: the PC still hears start and end; nothing to hold.
        val l = AppLock({ false }, { timeout }, { now })
        val told = Told()
        val w = CallWatcher(l) { told += it }
        w.onState(OFFHOOK)
        assertFalse(l.heldForCall)
        w.onState(IDLE)
        assertEquals(listOf("start", "end"), told)
    }

    @Test fun repeatedOffhookIsOneCall() {
        val l = unlockedAndAway()
        val told = Told()
        val w = CallWatcher(l) { told += it }
        w.onState(OFFHOOK)
        w.onState(OFFHOOK)
        assertEquals(listOf("start"), told)
    }

    // ---- telling the PC ---------------------------------------------------------------------------------

    @Test fun lineClientPostsTheEventWithTheTokenAndSwallowsErrors() {
        val token = "line-" + "t".repeat(40)
        val pc = MockWebServer()
        pc.enqueue(MockResponse().setBody("""{"ok":true}"""))
        pc.enqueue(MockResponse().setResponseCode(401).setBody("""{"ok":false}"""))
        pc.enqueue(MockResponse().setBody("""{"ok":true}""").setHeadersDelay(8, TimeUnit.SECONDS))
        pc.start()
        try {
            val url = pc.url("/phone/call").toString()
            assertTrue(LineClient(url, token).tell("start"))
            val req = pc.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/phone/call", req.path)
            assertEquals("Bearer $token", req.getHeader("Authorization"))
            assertEquals("""{"event":"start"}""", req.body.readUtf8())
            assertFalse(LineClient(url, token).tell("end"))                 // 401: no throw
            val began = System.currentTimeMillis()
            assertFalse(LineClient(url, token).tell("end"))                 // slow PC: gives up after 5 s
            assertTrue(System.currentTimeMillis() - began < 7_000)
            assertFalse(LineClient(url, token).tell("party"))               // only start / end
            assertFalse(LineClient("not a url", token).tell("start"))       // a bad URL: no throw
            assertFalse(LineClient(url, "").tell("start"))                  // no token: nothing sent
        } finally {
            pc.shutdown()
        }
    }

    @Test fun lineUrlDefaultsToTheServer() {
        val s = settings()
        s.serverUrl = "https://your-desktop.your-tailnet.ts.net"
        s.lineToken = "tok"
        assertEquals("https://your-desktop.your-tailnet.ts.net/phone/call", LineClient.from(s).url)
        s.lineUrl = "https://elsewhere.ts.net/line/call"
        assertEquals("https://elsewhere.ts.net/line/call", LineClient.from(s).url)
        s.serverUrl = ""
        s.lineUrl = ""
        assertEquals("", LineClient.from(s).url)                            // not set up: nothing to call
        assertFalse(s.lineNoPin)                                            // off until he switches it on
    }

    // ---- F2: the Settings list's summaries ----------------------------------------------------------

    private fun settings(): Settings {
        val prefs = MemPrefs()
        return Settings(object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs
        })
    }

    @Test fun settingsSummariesSayHowEachPageIsSet() {
        val s = settings()
        assertEquals("Not set up yet: add the server address and key", SettingsSummary.connection(s))
        s.serverUrl = "https://your-desktop.your-tailnet.ts.net/"
        s.apiKey = "k".repeat(40)
        assertEquals("Connected to your-desktop.your-tailnet.ts.net", SettingsSummary.connection(s))
        s.pcLive = true
        assertEquals("Live PC notices (within seconds)", SettingsSummary.notifications(s))
        s.pcLive = false
        assertEquals("PC notices about every 15 minutes", SettingsSummary.notifications(s))
        s.pcNotices = false
        assertEquals("PC notices off", SettingsSummary.notifications(s))
        s.pinHash = "pbkdf2:1:c2FsdA==:aGFzaA=="            // the lock only counts with a PIN set
        s.appLock = true
        s.lockAfterMs = 60_000
        assertEquals("App lock on · relocks after 1 min", SettingsSummary.security(s))
        s.lineNoPin = true
        assertTrue(SettingsSummary.security(s).endsWith("no PIN on calls while unlocked"))
        assertTrue(SettingsSummary.links(s).contains("Workbench not paired"))
        assertTrue(SettingsSummary.voice(s).isNotBlank())
        assertTrue(SettingsSummary.chat(s).contains("compacts at"))
    }
}
