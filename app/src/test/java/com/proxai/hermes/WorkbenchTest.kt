package com.proxai.hermes

import com.proxai.hermes.data.ListenerCursor
import com.proxai.hermes.data.WorkbenchClient
import com.proxai.hermes.data.WorkbenchException
import com.proxai.hermes.data.WorkbenchLogin
import com.proxai.hermes.data.WorkbenchWait
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Workbench logins (0.9.3): the client ProxMobile uses to answer the IDE's login requests. */
class WorkbenchTest {
    private lateinit var server: MockWebServer
    private val key = "proxmobile-key-for-the-tests-0123456789abcdef"
    private val id = "abcdefghijklmnop"

    @Before fun setUp() { server = MockWebServer().apply { start() } }
    @After fun tearDown() { server.shutdown() }

    private fun client() = WorkbenchClient(server.url("/workbench").toString().trimEnd('/'), key)

    @Test fun listsWaitingLoginsWithItsOwnKey() {
        server.enqueue(MockResponse().setBody("""{"logins":[{"id":"$id","label":"Chrome on Linux","choices":[29,73,92],"left":110}]}"""))
        val logins = client().waiting()
        assertEquals(listOf(id), logins.map { it.id })
        assertEquals(listOf(29, 73, 92), logins[0].choices)
        assertEquals("Chrome on Linux", logins[0].label)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/workbench/api/logins", req.path)
        assertEquals("Bearer $key", req.getHeader("Authorization"))
    }

    @Test fun answersWithTheTappedNumberOrNull() {
        server.enqueue(MockResponse().setBody("""{"state":"approved"}"""))
        server.enqueue(MockResponse().setBody("""{"state":"refused"}"""))
        assertEquals("approved", client().answer(id, 92))
        assertEquals("refused", client().answer(id, null))
        val tapped = server.takeRequest()
        assertEquals("POST", tapped.method)
        assertEquals("/workbench/api/logins/$id", tapped.path)
        assertEquals("Bearer $key", tapped.getHeader("Authorization"))
        assertEquals(92, JSONObject(tapped.body.readUtf8()).getInt("number"))
        assertTrue("Not me sends null", JSONObject(server.takeRequest().body.readUtf8()).isNull("number"))
    }

    @Test fun errorsCarryTheWorkbenchsWordsNeverTheKey() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"That login isn't waiting any more."}"""))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"wrong key"}"""))
        server.enqueue(MockResponse().setResponseCode(502).setBody("Bad gateway"))
        val gone = assertThrows(WorkbenchException::class.java) { client().answer(id, 92) }
        assertEquals("conflict", gone.code)
        assertEquals("That login isn't waiting any more.", gone.message)
        val wrong = assertThrows(WorkbenchException::class.java) { client().waiting() }
        assertEquals("unauthorized", wrong.code)
        assertFalse(wrong.message!!.contains(key))
        assertEquals("unreachable", assertThrows(WorkbenchException::class.java) { client().waiting() }.code)
    }

    @Test fun refusesAStrangeIdBeforeTheNetwork() {
        assertThrows(IllegalArgumentException::class.java) { client().answer("../../api/jobs", 1) }
        assertEquals(0, server.requestCount)
    }

    @Test fun keepsOnlyWellFormedLogins() {
        val parsed = WorkbenchClient.parse(JSONArray("""[
            {"id":"$id","label":"Firefox on Linux","choices":[10,55,99]},
            {"id":"short","label":"x","choices":[10,55,99]},
            {"id":"abcdefghijklmnoq","label":"x","choices":[10,55]},
            {"id":"abcdefghijklmnor","label":"x","choices":[10,55,"99"]},
            {"id":"abcdefghijklmnos","label":"x","choices":[10,55,100]},
            {"id":"abcdefghijklmnot","choices":[11,22,33]}
        ]"""))
        assertEquals(listOf(id, "abcdefghijklmnot"), parsed.map { it.id })
        assertEquals("A browser", parsed[1].label)
        assertEquals(emptyList<Any>(), WorkbenchClient.parse(null))
    }

    @Test fun theLongPollSaysWhatItSawAlready() {
        server.enqueue(MockResponse().setBody("""{"epoch":"ab12","logins":[{"id":"$id","seq":7,"label":"Chrome on Linux","choices":[29,73,92]}]}"""))
        val answer = client().waitForLogins(after = 6, epoch = "ab12")
        assertEquals("ab12", answer.epoch)
        assertEquals(listOf(7L), answer.logins.map { it.seq })
        val req = server.takeRequest()
        assertEquals("/workbench/api/logins?wait=25&after=6&epoch=ab12", req.path)
        assertEquals("Bearer $key", req.getHeader("Authorization"))
    }

    @Test fun aListenerPopsUpEachLoginOnceAndCountsAgainAfterARestart() {
        val a = WorkbenchLogin("abcdefghijklmnop", "A", listOf(11, 22, 33), seq = 1)
        val b = WorkbenchLogin("abcdefghijklmnoq", "B", listOf(44, 55, 66), seq = 2)
        val (first, c1) = ListenerCursor().next(WorkbenchWait("e1", listOf(a)))
        assertEquals(listOf(a), first)
        val (second, c2) = c1.next(WorkbenchWait("e1", listOf(a, b)))
        assertEquals("a is up already", listOf(b), second)
        assertEquals(emptyList<WorkbenchLogin>(), c2.next(WorkbenchWait("e1", listOf(a, b))).first)
        val c = WorkbenchLogin("abcdefghijklmnor", "C", listOf(10, 20, 30), seq = 1)
        assertEquals("a restarted Workbench counts from 1 again", listOf(c), c2.next(WorkbenchWait("e2", listOf(c))).first)
    }

    @Test fun pairsOnlyWithALongKeyAndTalksOnlyOverHttps() {
        assertTrue(WorkbenchClient.validKey(key))
        assertFalse(WorkbenchClient.validKey("short"))
        assertFalse(WorkbenchClient.validKey("k".repeat(40) + "&url=https://elsewhere"))
        assertTrue(WorkbenchClient("https://your-desktop.your-tailnet.ts.net/workbench", key).configured)
        assertFalse(WorkbenchClient("http://your-desktop.your-tailnet.ts.net/workbench", key).configured)
        assertFalse(WorkbenchClient("https://your-desktop.your-tailnet.ts.net/workbench", "").configured)
        assertFalse(WorkbenchClient("", key).configured)
    }
}
