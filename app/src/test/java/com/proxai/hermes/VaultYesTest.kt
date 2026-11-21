package com.proxai.hermes

import com.proxai.hermes.data.AppLock
import com.proxai.hermes.data.OnScreen
import com.proxai.hermes.data.VaultChange
import com.proxai.hermes.data.VaultYes
import com.proxai.hermes.data.VaultYesClient
import com.proxai.hermes.data.VoicePolicy
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** VAULT-UNDO-APP: approved vault changes and their Undo, against a mocked dumpsync /vault-yes/... (MockWebServer).
 *  Made-up notes only. */
class VaultYesTest {
    private lateinit var pc: MockWebServer
    private lateinit var client: VaultYesClient
    private val token = "d".repeat(43)
    private val owner = "o".repeat(43)

    @Before fun setUp() {
        pc = MockWebServer(); pc.start()
        client = VaultYesClient(pc.url("/dumpsync").toString(), token, owner)
    }

    @After fun tearDown() = pc.shutdown()

    private fun answer(code: Int, body: String) = pc.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private val two = """{"ok":true,"server_time":"2026-11-17T20:00:00Z","items":[
        {"id":"a1b2c3d4e5f6","path":"03-Projects/roof-quote.md","how":"appended","approved_by":"chat","can_undo":true,
         "time":"2026-11-17T19:58:00Z","text":"SECRET NOTE BODY","note":"SECRET NOTE BODY"},
        {"id":"a1b2c3d4e5f7","path":"05-Brain-Dump/idea.md","how":"created","approved_by":"call","can_undo":false,
         "time":"2026-11-17T19:59:00Z"}]}"""

    private fun change(id: String = "a1b2c3d4e5f6", canUndo: Boolean = true, shared: Boolean = false) =
        VaultChange(id, "03-Projects/roof-quote.md", "appended", "chat", canUndo, "2026-11-17T19:58:00Z", shared)

    @Test fun writtenSendsSinceBearerAppAndOwnerToken() {
        answer(200, two)
        val a = client.written("2026-11-17T19:00:00Z")
        val req = pc.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/dumpsync/vault-yes/written", req.requestUrl!!.encodedPath)
        assertEquals("2026-11-17T19:00:00Z", req.requestUrl!!.queryParameter("since"))
        assertEquals("Bearer $token", req.getHeader("Authorization"))
        assertEquals("com.proxai.hermes", req.getHeader("X-ProxAI-App"))
        assertEquals(owner, req.getHeader("X-ProxAI-Owner-Token"))
        assertTrue(a.ok)
        assertEquals("2026-11-17T20:00:00Z", a.written!!.serverTime)
        assertEquals(2, a.written!!.items.size)
    }

    @Test fun aSinceThatIsntIsoIsLeftOut() {
        answer(200, """{"ok":true,"items":[]}""")
        client.written("yesterday&x=1")
        assertNull(pc.takeRequest().requestUrl!!.queryParameter("since"))
    }

    @Test fun theLineIsPathHowAndWordNeverNoteText() {
        val items = VaultYes.parse(two)!!.items
        assertEquals("📝 03-Projects/roof-quote.md appended (from your chat)", items[0].line)
        assertEquals("📝 05-Brain-Dump/idea.md created (from your call)", items[1].line)
        items.forEach { assertFalse(it.line.contains("SECRET")); assertFalse(it.toString().contains("SECRET")) }
        assertTrue(items[0].undoable)
        assertFalse(items[1].undoable)          // can_undo false: no Undo
    }

    @Test fun approverIsOnlyAFixedWord() {
        assertEquals("chat", VaultYes.word("chat"))
        assertEquals("terminal", VaultYes.word(" Terminal "))
        assertEquals("call", VaultYes.word("call"))
        assertEquals("other", VaultYes.word("other"))
        assertEquals("other", VaultYes.word("Nathan's laptop <b>"))
        assertEquals("other", VaultYes.word(null))
        assertEquals("other", VaultYes.word(42))
    }

    @Test fun howIsAPlainWordOrChanged() {
        assertEquals("appended", VaultYes.how("appended"))
        assertEquals("changed", VaultYes.how("appended\nrm -rf"))
        assertEquals("changed", VaultYes.how("<script>"))
        assertEquals("changed", VaultYes.how(null))
        assertEquals("changed", VaultYes.how("x".repeat(40)))
    }

    @Test fun pathIsEscapedAndCut() {
        assertEquals("a\\u000ab.md", VaultYes.escape("a\nb.md"))
        assertEquals("evil\\u202egpj.md", VaultYes.escape("evil‮gpj.md"))     // bidi override shown, not obeyed
        assertEquals("z\\u200bw.md", VaultYes.escape("z​w.md"))               // zero-width shown
        val long = VaultYes.escape("p/" + "x".repeat(500))
        assertEquals(VaultYes.MAX_PATH, long.length)
        assertTrue(long.endsWith("…"))
        assertEquals("(no path)", VaultYes.escape(null))
        assertEquals("(no path)", VaultYes.escape(""))
    }

    @Test fun entriesSharingAnIdAreListedMarkedAndHaveNoUndo() {
        val w = VaultYes.parse("""{"items":[
            {"id":"0123456789ab","path":"a.md","how":"edited","approved_by":"chat","can_undo":true,"time":"1"},
            {"id":"0123456789ab","path":"b.md","how":"edited","approved_by":"terminal","can_undo":true,"time":"2"},
            {"id":"0123456789ac","path":"c.md","how":"edited","approved_by":"chat","can_undo":true,"time":"3"},
            {"path":"d.md","how":"edited","approved_by":"chat","can_undo":true,"time":"4"}]}""")!!
        assertEquals(4, w.items.size)
        val (a, b, c, d) = w.items
        assertTrue(a.sharedId && b.sharedId)
        assertFalse(a.undoable || b.undoable)
        assertEquals(VaultYes.SHARED_MARK, a.mark)
        assertTrue(c.undoable); assertEquals("", c.mark)
        assertFalse(d.undoable)                 // no id: nothing to undo by
    }

    @Test fun freshNotifiesEachEntryOnce() {
        val items = VaultYes.parse(two)!!.items
        val (first, seen) = VaultYes.fresh(items, emptyList())
        assertEquals(2, first.size)
        val (again, seen2) = VaultYes.fresh(items, seen)
        assertTrue(again.isEmpty())
        assertEquals(seen, seen2)
        val keep = VaultYes.fresh(items, List(VaultYes.MAX_SEEN) { "old$it" }).second
        assertEquals(VaultYes.MAX_SEEN, keep.size)
        assertEquals(items.last().key, keep.last())
    }

    // Security #48: only the PC's 12 lower-case hex characters are ever an id.
    @Test fun onlyTwelveHexCharactersAreAnId() {
        assertTrue(VaultYes.validId("0123456789ab"))
        for (bad in listOf(".", "..", "", "a1b2c3d4e5f", "a1b2c3d4e5f67", "A1B2C3D4E5F6", "a1b2c3d4e5f\n", "../../x/y/zz",
            "03-Projects/a.md", "a1b2c3d4e5f6\n"))
            assertFalse("'$bad'", VaultYes.validId(bad))
        // an item with such an id is listed but never undoable, so it can never reach addPathSegment
        val w = VaultYes.parse("""{"items":[{"id":"..","path":"a.md","how":"edited","approved_by":"chat","can_undo":true}]}""")!!
        assertFalse(w.items[0].undoable)
        assertFalse(client.undo(w.items[0].copy(sharedId = false)).ok)
        assertEquals(0, pc.requestCount)
    }

    // Security #48: a time is shown unescaped, so it is kept only when it is an ISO time.
    @Test fun aTimeIsKeptOnlyWhenItIsIso() {
        assertEquals("2026-11-17T19:58:00Z", VaultYes.isoTime("2026-11-17T19:58:00Z"))
        assertEquals("", VaultYes.isoTime("<b>now</b>"))
        assertEquals("", VaultYes.isoTime("2026-11-17T19:58:00Z‮evil"))
        assertEquals("", VaultYes.isoTime(12345))
        val w = VaultYes.parse("""{"items":[{"id":"0123456789ab","path":"a.md","time":"yesterday <i>x</i>"}]}""")!!
        assertEquals("", w.items[0].time)
        val back = VaultYes.pendingFrom(VaultYes.pendingJson(listOf(change().copy(time = "2026-11-17T19:58:00Z"))))
        assertEquals("2026-11-17T19:58:00Z", back[0].time)
        assertEquals("", VaultYes.pendingFrom("""[{"id":"0123456789ab","path":"a","time":"bad"}]""")[0].time)
    }

    // Security #47: with the app lock on and ProxMobile locked, an Undo sends nothing and says to unlock.
    @Test fun lockedUndoNeedsAnUnlock() {
        assertTrue(VaultYes.needsUnlock(lockOn = true, locked = true))
        assertFalse(VaultYes.needsUnlock(lockOn = true, locked = false))    // unlocked: one tap stays
        assertFalse(VaultYes.needsUnlock(lockOn = false, locked = true))    // lock off: never blocked
        assertEquals("Unlock ProxAI to undo", VaultYes.UNLOCK_TO_UNDO)
    }

    // PM-TIDY-0919: app lock on, phone locked, an untouched wake overlay up. The overlay is "in the foreground"
    // (a reply needs no notification) but never unlocks ProxMobile, so an Undo sends nothing and re-posts the
    // "Unlock ProxAI to undo" line. The receiver decides from app.lock.check(), not from inForeground.
    @Test fun undoUnderAnUntouchedWakeOverlayNeedsAnUnlockAndSendsNothing() {
        var clock = 1_800_000_000_000L
        val lock = AppLock({ true }, { 60_000L }, { clock })        // app lock on; a fresh start is locked
        val screens = OnScreen<String> { VoicePolicy.countsOnScreen(VoicePolicy.WAKE, used = false) }
        screens.start("wake")
        assertTrue("the overlay is up, so inForeground is true", screens.anyVisible)
        assertEquals(VaultYes.UndoStep.RepostUnlock, VaultYes.undoStep(lockOn = true, lockedNow = lock.check()))
        // Unlocked once, then past the lock time with the overlay still open: still locked, still nothing sent.
        lock.unlock(); lock.offScreen(); clock += 61_000
        assertTrue(screens.anyVisible)
        assertEquals(VaultYes.UndoStep.RepostUnlock, VaultYes.undoStep(true, lock.check()))
        assertEquals("Unlock ProxAI to undo", VaultYes.UNLOCK_TO_UNDO)
        // Control: really unlocked, or lock off, the Undo is sent.
        lock.unlock()
        assertEquals(VaultYes.UndoStep.Send, VaultYes.undoStep(true, lock.check()))
        assertEquals(VaultYes.UndoStep.Send, VaultYes.undoStep(lockOn = false, lockedNow = true))
    }

    @Test fun undoPostsToTheIdAsOneSegment() {
        answer(200, """{"ok":true}""")
        val a = client.undo(change())
        val req = pc.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/dumpsync/vault-yes/a1b2c3d4e5f6/undo", req.requestUrl!!.encodedPath)
        assertEquals(owner, req.getHeader("X-ProxAI-Owner-Token"))
        assertEquals("com.proxai.hermes", req.getHeader("X-ProxAI-App"))
        assertTrue(a.ok)
        assertEquals("Undone: 03-Projects/roof-quote.md", a.message)
    }

    @Test fun conflictAndNotSetUpAreSaidPlainly() {
        answer(409, """{"ok":false,"error":"changed"}""")
        val c = client.undo(change())
        assertFalse(c.ok); assertEquals(409, c.status); assertEquals(VaultYes.CONFLICT, c.message)
        assertTrue(c.message.contains("the note changed since"))
        answer(503, """{"ok":false}""")
        val n = client.undo(change())
        assertEquals(503, n.status); assertEquals(VaultYes.NOT_SET_UP, n.message)
        answer(503, "")
        val l = client.written(null)
        assertFalse(l.ok); assertEquals(VaultYes.LIST_NOT_SET_UP, l.message)
    }

    @Test fun noUndoIsSentForSharedOrNotUndoable() {
        assertFalse(client.undo(change(shared = true)).ok)
        assertFalse(client.undo(change(canUndo = false)).ok)
        assertEquals(0, pc.requestCount)
    }

    @Test fun nothingIsSentWithoutBothTokens() {
        val noOwner = VaultYesClient(pc.url("/dumpsync").toString(), token, "")
        assertFalse(noOwner.isConfigured)
        assertEquals(VaultYes.NO_TOKENS, noOwner.written(null).message)
        assertEquals(VaultYes.NO_TOKENS, noOwner.undo(change()).message)
        val badOwner = VaultYesClient(pc.url("/dumpsync").toString(), token, "has space")
        assertEquals(VaultYes.BAD_TOKEN, badOwner.written(null).message)
        assertEquals(0, pc.requestCount)
    }

    @Test fun redirectsAreNotFollowed() {
        pc.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/steal"))
        val a = client.written(null)
        assertFalse(a.ok)
        assertEquals(1, pc.requestCount)
    }

    @Test fun pcAwayIsUnreachable() {
        pc.shutdown()
        assertEquals(VaultYes.UNREACHABLE, client.written(null).message)
        assertEquals(VaultYes.UNREACHABLE, client.undo(change()).message)
    }

    @Test fun refusalTextIsEscapedAndCapped() {
        answer(403, """{"ok":false,"error":"wrong owner token\nreally"}""")
        val a = client.undo(change())
        assertTrue(a.message.contains("Owner token"))
        assertTrue(a.message.contains("\\u000a"))
        assertFalse(a.message.contains("\n"))
    }

    @Test fun pendingRoundTripKeepsOnlyUndoable() {
        val list = listOf(change(), change(id = "a1b2c3d4e5f7", canUndo = false), change(id = "a1b2c3d4e5f8", shared = true))
        val back = VaultYes.pendingFrom(VaultYes.pendingJson(list))
        assertEquals(listOf("a1b2c3d4e5f6"), back.map { it.id })
        assertEquals(list[0].key, back[0].key)
        assertEquals(list[0].line, back[0].line)
        assertTrue(VaultYes.pendingFrom("not json").isEmpty())
        val keys = listOf("a|1|x", "b\n|2|y")
        assertEquals(keys, VaultYes.keysFrom(VaultYes.keysJson(keys)))
    }

    @Test fun badAnswersParseSafely() {
        assertNull(VaultYes.parse("<html>"))
        assertTrue(VaultYes.parse("{}")!!.items.isEmpty())
        assertEquals("", VaultYes.parse("""{"server_time":"soon"}""")!!.serverTime)
        val odd = VaultYes.parse("""{"items":[{"id":"x","path":7,"how":null,"approved_by":["chat"],"can_undo":"yes"}]}""")!!
        assertEquals("📝 (no path) changed (from your other)", odd.items[0].line)
        assertFalse(odd.items[0].canUndo)      // only a JSON true counts
    }
}
