package com.proxai.hermes

import com.proxai.hermes.data.DocsRefused
import com.proxai.hermes.data.DocsTimer
import com.proxai.hermes.data.ProxDocs
import com.proxai.hermes.data.ProxDocsClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections

/**
 * P4 (0.9.7): proxDocs on the phone. The client against a fake apps server, and the app-wide session:
 * the token stays in memory, it locks with the app lock's time away, and the answers go when it locks.
 */
class ProxDocsTest {
    private lateinit var server: MockWebServer
    private val seen: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
    private var clock = 1_000_000L
    @Volatile private var lockedOnPc = false

    private val ready = """"ok": true, "mode": "server", "server": true, "model": "qwen3.5:35b-a3b", "loaded": true,
        "loading": false, "ready": true, "code": "ready", "why": "", "password_set": true,
        "library": {"passages": 1957, "commits": 221, "apps": 12}"""

    @Before fun setUp() {
        ProxDocs.reset()
        seen.clear()
        lockedOnPc = false
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val route = request.path!!.removePrefix("/apps/v1/docs/")
                val body = request.body.readUtf8().ifBlank { "{}" }.let { JSONObject(it) }
                seen += route to body
                if (request.getHeader("Authorization") != "Bearer test-key") return MockResponse().setResponseCode(401)
                val json = when (route) {
                    "status" -> "{$ready}"
                    "unlock" -> if (body.optString("password") == "right one") """{$ready, "token": "${"t".repeat(43)}",
                        "relock_seconds": ${body.optLong("relock_seconds")}}"""
                        else """{"ok": false, "code": "wrong_password", "error": "Wrong password. 3 more before a wait.",
                        "tries_left": 3, "wait_seconds": 0}"""
                    "alive" -> if (lockedOnPc) """{"ok": false, "code": "locked", "error": "proxDocs locked again."}"""
                        else """{$ready, "unlocked": true}"""
                    "ask" -> if (lockedOnPc) """{"ok": false, "code": "locked", "error": "proxDocs locked again."}"""
                        else """{"ok": true, "answer": "It locks after the app lock's time away [1].", "seconds": 12.5,
                        "sources": [{"n": 1, "title": "AppLock.kt: AppLock", "where": "ProxMobile data/AppLock.kt",
                        "kind": "code", "cited": true}]}"""
                    "lock" -> """{"ok": true, "locked": true, "was_open": true}"""
                    else -> return MockResponse().setResponseCode(404).setBody("{\"error\": \"not found\"}")
                }
                return MockResponse().setBody(json)
            }
        }
        server.start()
        ProxDocs.client = { client() }
        ProxDocs.relockMs = { 60_000L }
        ProxDocs.now = { clock }
        ProxDocs.aliveEveryMs = 50
    }

    @After fun tearDown() {
        ProxDocs.reset()
        ProxDocs.aliveEveryMs = 30_000
        ProxDocs.now = System::currentTimeMillis
        server.shutdown()
    }

    private fun client(key: String = "test-key") = ProxDocsClient(server.url("/").toString().trimEnd('/'), key)

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(10)
        }
    }

    private fun routes() = synchronized(seen) { seen.map { it.first } }

    @Test fun timerFollowsTheAppLockTime() {
        var t = 0L
        val timer = DocsTimer({ 60_000L }, { t })
        assertTrue(timer.onScreenNow)
        timer.offScreen()
        t += 59_000
        assertFalse(timer.onScreen())            // back within the lock time: still open
        timer.offScreen()
        t += 60_000
        assertTrue(timer.onScreen())             // away the whole lock time: lock
        assertFalse(timer.onScreen())            // only once
    }

    @Test fun clientUnlockSendsThePasswordOnceAndReadsTheToken() {
        val (token, st) = client().unlock("right one", 60)
        assertEquals(43, token.length)
        assertTrue(st.ready)
        assertEquals(1957, st.passages)
        val (route, body) = seen.single()
        assertEquals("unlock", route)
        assertEquals("phone", body.getString("where"))
        assertEquals(60, body.getLong("relock_seconds"))
    }

    @Test fun clientRefusals() {
        val e = assertThrows(DocsRefused::class.java) { client().unlock("nope", 60) }
        assertEquals("wrong_password", e.code)
        assertEquals(3, e.triesLeft)
        assertEquals("old_server", assertThrows(DocsRefused::class.java) { client().load("x".repeat(43)) }.code)
        val bad = assertThrows(java.io.IOException::class.java) { client(key = "wrong").status() }
        assertTrue(bad.message!!.contains("API key"))
    }

    @Test fun clientAskReadsSources() {
        val a = client().ask("t".repeat(43), "How does the app lock work?")
        assertEquals("It locks after the app lock's time away [1].", a.answer)
        assertEquals("ProxMobile data/AppLock.kt", a.sources.single().where)
        assertTrue(a.sources.single().cited)
        assertEquals(12.5, a.seconds, 0.01)
    }

    @Test fun unlockAskAndLockClearsTheAnswers() {
        ProxDocs.unlock("right one")
        waitFor("unlock") { ProxDocs.ui.value.unlocked }
        assertEquals(60L, seen.first { it.first == "unlock" }.second.getLong("relock_seconds"))
        ProxDocs.ask("How does the app lock work?")
        waitFor("answer") { ProxDocs.ui.value.answers.isNotEmpty() }
        assertNull(ProxDocs.ui.value.asking)
        ProxDocs.lock("Locked.")
        assertFalse(ProxDocs.ui.value.unlocked)
        assertTrue(ProxDocs.ui.value.answers.isEmpty())             // answers live only while it's open
        assertEquals("Locked.", ProxDocs.ui.value.note)
        waitFor("the PC told") { "lock" in routes() }
        assertEquals("t".repeat(43), seen.last { it.first == "lock" }.second.getString("token"))
    }

    @Test fun wrongPasswordShowsWhy() {
        ProxDocs.unlock("nope")
        waitFor("refusal") { ProxDocs.ui.value.error != null }
        assertFalse(ProxDocs.ui.value.unlocked)
        assertEquals("Wrong password. 3 more before a wait.", ProxDocs.ui.value.error)
    }

    @Test fun locksAfterTheLockTimeAwayButNotBefore() {
        ProxDocs.unlock("right one")
        waitFor("unlock") { ProxDocs.ui.value.unlocked }
        ProxDocs.offScreen()
        clock += 30_000
        ProxDocs.onScreen()
        assertTrue(ProxDocs.ui.value.unlocked)                       // 30 s away: still open
        ProxDocs.offScreen()
        clock += 60_000
        ProxDocs.onScreen()
        assertFalse(ProxDocs.ui.value.unlocked)                      // a full minute away: locked
        waitFor("the PC told") { "lock" in routes() }
    }

    @Test fun keepsTellingThePcWhileOnScreenAndStopsOffScreen() {
        ProxDocs.unlock("right one")
        waitFor("unlock") { ProxDocs.ui.value.unlocked }
        waitFor("alive calls") { routes().count { it == "alive" } >= 3 }
        ProxDocs.offScreen()
        Thread.sleep(120)
        val n = routes().count { it == "alive" }
        Thread.sleep(250)
        assertEquals(n, routes().count { it == "alive" })           // off screen: no more alive calls
    }

    // ---- 0.9.8: the bridge for PC-built apps with the "docs" feature ----

    @Test fun bridgeStatusWhileLocked() {
        val o = JSONObject(ProxDocs.bridge("""{"op": "status"}"""))
        assertTrue(o.getBoolean("ok"))
        assertFalse(o.getBoolean("unlocked"))
        assertTrue(o.getBoolean("ready"))
        assertEquals(1957, o.getInt("passages"))
        assertFalse(o.has("token"))                                   // the app never gets the key
        val ask = JSONObject(ProxDocs.bridge("""{"op": "ask", "question": "How does the app lock work?"}"""))
        assertEquals("locked", ask.getString("code"))
        assertFalse("ask" in routes())                                // nothing went to the PC
    }

    @Test fun bridgeAskSharesTheSessionButNotTheTab() {
        ProxDocs.unlock("right one")
        waitFor("unlock") { ProxDocs.ui.value.unlocked }
        val o = JSONObject(ProxDocs.bridge("""{"op": "ask", "question": "How does the app lock work?"}"""))
        assertTrue(o.getBoolean("ok"))
        assertEquals("It locks after the app lock's time away [1].", o.getString("answer"))
        assertEquals("ProxMobile data/AppLock.kt", o.getJSONArray("sources").getJSONObject(0).getString("where"))
        assertFalse(o.toString().contains("t".repeat(43)))            // no token in what the app gets
        assertTrue(ProxDocs.ui.value.answers.isEmpty())                // the app's answers stay out of the Docs tab
        assertEquals("t".repeat(43), seen.last { it.first == "ask" }.second.getString("token"))
    }

    @Test fun bridgeSeesThePcLockAndLocksFromTheApp() {
        ProxDocs.unlock("right one")
        waitFor("unlock") { ProxDocs.ui.value.unlocked }
        assertTrue(JSONObject(ProxDocs.bridge("""{"op": "alive"}""")).getBoolean("unlocked"))
        lockedOnPc = true
        val o = JSONObject(ProxDocs.bridge("""{"op": "ask", "question": "anything at all?"}"""))
        assertEquals("locked", o.getString("code"))
        assertFalse(ProxDocs.ui.value.unlocked)
        lockedOnPc = false
        ProxDocs.unlock("right one")
        waitFor("unlock again") { ProxDocs.ui.value.unlocked }
        assertTrue(JSONObject(ProxDocs.bridge("""{"op": "lock"}""")).getBoolean("ok"))
        assertFalse(ProxDocs.ui.value.unlocked)
        waitFor("the PC told") { "lock" in routes() }
        assertEquals("bad_request", JSONObject(ProxDocs.bridge("not json")).getString("code"))
        assertEquals("bad_request", JSONObject(ProxDocs.bridge("""{"op": "nope"}""")).getString("code"))
    }

    @Test fun anAppInUseKeepsItOpenLikeTheAppLock() {
        ProxDocs.unlock("right one")
        waitFor("unlock") { ProxDocs.ui.value.unlocked }
        ProxDocs.offScreen()                                          // ProxAI goes away; the docs app is on screen
        repeat(6) {                                                   // its shell checks in every 20 s for 2 minutes
            clock += 20_000
            assertTrue(JSONObject(ProxDocs.bridge("""{"op": "alive"}""")).getBoolean("unlocked"))
        }
        clock += 60_000                                               // the app went away too: 30 s grace + lock time
        clock += 30_000
        val o = JSONObject(ProxDocs.bridge("""{"op": "alive"}"""))
        assertFalse(o.getBoolean("unlocked"))
        assertFalse(ProxDocs.ui.value.unlocked)
    }

    @Test fun timerCountsAppUse() {
        var t = 1_000_000L                                            // 0 means "on screen" to the timer
        val timer = DocsTimer({ 60_000L }, { t })
        timer.used()                                                  // on screen: nothing to do
        assertTrue(timer.onScreenNow)
        timer.offScreen()
        t += 50_000
        timer.used()                                                  // seen until t + 30 s
        t += 80_000
        assertFalse(timer.expired())                                  // 80 s after the app was seen: 30 s + 50 s < grace + 60
        t += 11_000
        assertTrue(timer.expired())
    }

    @Test fun onlyAppsWithTheDocsFeature() {
        assertNull(com.proxai.hermes.data.PcStatsProvider.featureRefusal("docs", setOf("docs")))
        assertEquals("This app wasn't built with the 'docs' feature.",
            com.proxai.hermes.data.PcStatsProvider.featureRefusal("docs", setOf("pc-stats", "ask")))
    }

    @Test fun pcSayingLockedLocksThePhone() {
        ProxDocs.unlock("right one")
        waitFor("unlock") { ProxDocs.ui.value.unlocked }
        lockedOnPc = true
        waitFor("locked by the PC") { !ProxDocs.ui.value.unlocked }
        assertNotNull(ProxDocs.ui.value.note)
    }
}
