package com.proxai.hermes

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.proxai.hermes.data.AppStore
import com.proxai.hermes.data.Approval
import com.proxai.hermes.data.ChatRepository
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.PcModeMonitor
import com.proxai.hermes.data.PcStatsClient
import com.proxai.hermes.data.Prompts
import com.proxai.hermes.data.ReminderStore
import com.proxai.hermes.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 0.9.9, the 11-02 fix round: the approval pop-up (F1), what goes in his message (B21) and the PC notice
 * long poll (B19). The real ChatRepository against a mocked PC, like [ChatChannelsTest].
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class FixRoundTest {
    private data class Sent(val session: String, val system: String, val user: String)

    private val main = newSingleThreadContext("test-main")
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val server = MockWebServer()
    private val sent = CopyOnWriteArrayList<Sent>()
    private val approvalAsks = CopyOnWriteArrayList<String>()
    @Volatile private var approvalJson = """{"approval": null}"""
    @Volatile private var usage = ""
    private lateinit var settings: Settings
    private lateinit var repo: ChatRepository

    @Before fun setUp() {
        Dispatchers.setMain(main)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                if (path.startsWith("/apps/v1/approvals")) {
                    approvalAsks += path
                    return MockResponse().setBody(approvalJson)
                }
                if (!path.startsWith("/v1/chat/completions")) return MockResponse().setResponseCode(404)
                val msgs = JSONObject(request.body.readUtf8()).getJSONArray("messages")
                var system = ""
                var user = ""
                for (i in 0 until msgs.length()) {
                    val m = msgs.getJSONObject(i)
                    val content = m.opt("content").let { if (it is String) it else it.toString() }
                    if (m.getString("role") == "system") system = content else if (m.getString("role") == "user") user = content
                }
                sent += Sent(request.getHeader("X-Hermes-Session-Id").orEmpty(), system, user)
                val word = "Reply${sent.size}"
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\": [{\"delta\": {\"content\": \"$word\"}}]}\n\n$usage" + "data: [DONE]\n\n")
            }
        }
        server.start()
        val dir = Files.createTempDirectory("fixround").toFile()
        val prefs = mutableMapOf<String, MemPrefs>()
        val ctx = object : ContextWrapper(null) {
            override fun getFilesDir() = dir
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemPrefs() }
        }
        settings = Settings(ctx)
        settings.serverUrl = server.url("/").toString()
        settings.apiKey = "k".repeat(40)
        settings.modelChoice = "cloud"
        repo = ChatRepository(ctx, settings, HermesClient(settings), AppStore(ctx), PcModeMonitor(settings),
            ReminderStore(ctx), scope) { true }
    }

    @After fun tearDown() {
        scope.cancel()
        server.shutdown()
        Dispatchers.resetMain()
        main.close()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun idle() = runBlocking {
        withTimeout(10_000) { withContext(main) { delay(50); while (repo.busy.value) delay(20) } }
    }

    private fun waitFor(what: String, ok: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!ok()) {
            if (System.currentTimeMillis() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(20)
        }
    }

    private fun approvalFor(session: String, question: String = "Switch to SERVER mode?") =
        """{"approval": {"id": "a1", "session": "$session", "plugin": "proxai-mode", "question": "$question",
            "detail": "That will: turn the lights off.", "created": ${System.currentTimeMillis() / 1000.0},
            "expires": ${System.currentTimeMillis() / 1000.0 + 600}}}"""

    // ---- F1: the approval pop-up -------------------------------------------------------------------

    @Test fun approvalParsesAndAnswersWithAPlainWordBehindANote() {
        val a = Approval.parse(approvalFor("android-x", "Delete [Pong] from the PC?"))!!
        assertEquals("a1", a.id)
        assertEquals("PC mode", a.title)
        assertEquals("That will: turn the lights off.", a.detail)
        assertTrue(a.expiresAt > System.currentTimeMillis())
        val note = Approval.note(a, accept = true)
        assertTrue(note.startsWith("[Approval: Nathan tapped Accept"))
        assertEquals(1, note.count { it == '[' })                 // one note: the PC's plugins strip it whole
        assertEquals(1, note.count { it == ']' })
        assertTrue("(Pong)" in note)
        assertEquals("yes", Approval.word(true))
        assertEquals("no", Approval.word(false))
        assertNull(Approval.parse("""{"approval": null}"""))
        assertNull(Approval.parse("""{"approval": {"id": "", "question": "x"}}"""))
        assertNull(Approval.parse("not json"))
    }

    @Test fun questionPopsUpAfterTheReplyAndAcceptSendsYes() {
        approvalJson = approvalFor(settings.sessionId)
        onMain { repo.send("server mode") }
        idle()
        waitFor("the pop-up") { repo.approval.value != null }
        assertTrue(approvalAsks.last().contains("session=${settings.sessionId}"))
        assertEquals("Switch to SERVER mode?", repo.approval.value!!.approval.question)

        approvalJson = """{"approval": null}"""
        onMain { repo.answerApproval(true) }
        idle()
        assertNull(repo.approval.value)
        val answer = sent.last()
        assertEquals(settings.sessionId, answer.session)
        assertTrue(answer.user.endsWith("\nyes"))
        assertTrue("[Approval: Nathan tapped Accept in ProxAI's pop-up for: “Switch to SERVER mode?”]" in answer.user)
        assertTrue(repo.messages.value.any { it.role == "user" && it.text == "✅ Accept" })
    }

    @Test fun rejectSendsNoAndClosingSendsNothing() {
        approvalJson = approvalFor(settings.sessionId)
        onMain { repo.send("server mode") }
        idle()
        waitFor("the pop-up") { repo.approval.value != null }
        approvalJson = """{"approval": null}"""
        onMain { repo.answerApproval(false) }
        idle()
        assertTrue(sent.last().user.endsWith("\nno"))
        assertTrue("tapped Reject" in sent.last().user)

        approvalJson = approvalFor(settings.sessionId).replace("\"a1\"", "\"a2\"")
        onMain { repo.send("server mode") }
        idle()
        waitFor("the second pop-up") { repo.approval.value != null }
        val before = sent.size
        onMain { repo.closeApproval() }
        assertNull(repo.approval.value)
        onMain { repo.checkApproval() }                  // closed on this phone: it doesn't come back
        Thread.sleep(300)
        assertNull(repo.approval.value)
        assertEquals(before, sent.size)
    }

    @Test fun typingSomethingElseTakesThePopUpDown() {
        approvalJson = approvalFor(settings.sessionId)
        onMain { repo.send("server mode") }
        idle()
        waitFor("the pop-up") { repo.approval.value != null }
        approvalJson = """{"approval": null}"""
        onMain { repo.send("actually, never mind") }
        assertNull(repo.approval.value)
        idle()
        assertFalse("[Approval:" in sent.last().user)
    }

    @Test fun noAutoCompactWhileAQuestionWaits() {
        settings.compactAtTokens = 100
        usage = "data: {\"choices\": [], \"usage\": {\"prompt_tokens\": 5000, \"completion_tokens\": 10}}\n\n"
        approvalJson = approvalFor(settings.sessionId)
        onMain { repo.send("server mode") }
        idle()
        waitFor("the pop-up") { repo.approval.value != null }
        Thread.sleep(300)
        idle()
        assertEquals(1, sent.size)                       // no compaction request under the question
        assertFalse(repo.messages.value.any { it.role == "note" && "ompact" in it.text })

        // Without a waiting question the same reply compacts, as before.
        approvalJson = """{"approval": null}"""
        onMain { repo.closeApproval(); repo.send("thanks") }
        idle()
        waitFor("the compaction") { sent.any { "Summarize our conversation" in it.user } }
    }

    // ---- B21: his message is his message --------------------------------------------------------------

    @Test fun summaryRidesInTheSystemPartOfEveryRequestNotInHisMessage() {
        settings.carryOver = "**Where we are:** testing the fix round.\n**Still open:**\n- update BoardRoom tomorrow at 2am"
        onMain { repo.send("what's on today?") }
        idle()
        onMain { repo.send("and tomorrow?") }
        idle()
        for (s in sent) {
            assertTrue("EARLIER IN THIS CHAT" in s.system)
            assertTrue("update BoardRoom tomorrow at 2am" in s.system)
            assertFalse("Where we are" in s.user)
            assertFalse("Context from earlier" in s.user)
        }
        assertTrue(sent[0].user.endsWith("\nwhat's on today?"))
        assertEquals(sent[0].system, sent[1].system)     // the same system part all session (the local model's cache)
        assertTrue(settings.carryOver.isNotBlank())        // kept until the next compaction
    }

    @Test fun systemPartWithoutASummaryIsUnchanged() {
        assertEquals(Prompts.system(), Prompts.system(""))
        assertFalse("EARLIER IN THIS CHAT" in Prompts.system())
        assertTrue(Prompts.system("x").endsWith("x"))
    }

    @Test fun remindersAreListedOnlyWhenTheyMatter() {
        for (t in listOf("remind me at 5 to call mom", "what reminders do I have?", "snooze that", "cancel the alarm"))
            assertTrue(t, ReminderStore.mentionsReminders(t))
        for (t in listOf("yes", "server mode", "what's the weather?"))
            assertFalse(t, ReminderStore.mentionsReminders(t))
        // No reminders set: the phone note never says "Reminders set".
        onMain { repo.send("hello") }
        idle()
        assertFalse("Reminders set" in sent.last().user)
    }

    // ---- B19: the PC notice long poll -------------------------------------------------------------------

    @Test fun noticesLongPollAsksThePcToWait() {
        val pc = MockWebServer()
        pc.enqueue(MockResponse().setBody("""{"notices": [{"id": 8, "time": "2026-11-02T12:50:53", "kind": "calendar",
            "title": "⏰ Technical and Prof Comm in 9 min", "text": "Room 112"}]}"""))
        pc.enqueue(MockResponse().setBody("""{"approval": null}"""))
        pc.start()
        try {
            settings.serverUrl = pc.url("/").toString()
            val client = PcStatsClient(settings)
            val got = client.waitForNotices(7, 45)
            assertEquals(listOf(8), got.map { it.id })
            val asked = pc.takeRequest()
            assertEquals("/apps/v1/pc/notices?after=7&wait=45", asked.path)
            assertEquals("Bearer ${"k".repeat(40)}", asked.getHeader("Authorization"))
            assertNull(client.approval(listOf("android-a b", "android-c")))
            assertEquals("/apps/v1/approvals?session=android-a+b&session=android-c", pc.takeRequest().path)
            assertNull(client.approval(emptyList()))      // nothing to ask about: no request at all
        } finally {
            pc.shutdown()
        }
    }
}
