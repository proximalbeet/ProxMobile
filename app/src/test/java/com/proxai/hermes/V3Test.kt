package com.proxai.hermes

import com.proxai.hermes.data.ChatMessage
import com.proxai.hermes.data.ChatRepository
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.HermesConfig
import com.proxai.hermes.data.ModelRoute
import com.proxai.hermes.data.PcMode
import com.proxai.hermes.data.PcModeMonitor
import com.proxai.hermes.data.Prompts
import com.proxai.hermes.data.ReminderStore
import com.proxai.hermes.data.StreamEvent
import com.proxai.hermes.voice.SpeechController
import com.proxai.hermes.voice.SpeechFixes
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** v0.3: speech fixes, server-mode model routing, reminders, usage + auto-compact numbers. */
class V3Test {
    private val zone = ZoneId.of("America/Detroit")

    // ---- speech ----

    @Test fun wordFixesReplaceWholePhrasesOnly() {
        val rules = SpeechFixes.parse("crime job => cron job\nMac interview => mock interview\nbad line\n => x")
        assertEquals(2, rules.size)
        assertEquals("set up a cron job tonight", SpeechFixes.apply("set up a Crime  job tonight", rules))
        assertEquals("prep for the mock interview", SpeechFixes.apply("prep for the Mac interview", rules))
        // Not inside other words, and unrelated text untouched.
        assertEquals("crime jobsite", SpeechFixes.apply("crime jobsite", rules))
        assertEquals("my Mac is fine", SpeechFixes.apply("my Mac is fine", rules))
    }

    @Test fun wordFixesKeepDollarSignsLiteral() {
        val rules = SpeechFixes.parse("five bucks => \$5")
        assertEquals("it was \$5", SpeechFixes.apply("it was five bucks", rules))
    }

    @Test fun vocabularyListIsCleanedAndDeduplicated() {
        assertEquals(listOf("cron", "Hermes"), SpeechFixes.vocabulary(" cron \n\nHermes\ncron\n"))
    }

    @Test fun errorNamesAreReadable() {
        assertEquals("client", SpeechController.errorName(5))
        assertEquals("speech service disconnected", SpeechController.errorName(11))
        assertEquals("error 99", SpeechController.errorName(99))
    }

    @Test fun voicePromptWarnsAboutMishearing() {
        val now = ZonedDateTime.of(2026, 10, 22, 17, 5, 0, 0, zone)
        val voice = Prompts.system(true, now)
        assertTrue(voice.contains("misheard"))
        assertTrue(voice.contains("Current phone time: Thursday 2026-10-22 17:05 (America/Detroit)"))
        assertTrue(voice.contains("[[remind:"))
        assertFalse(Prompts.system(false, now).contains("misheard"))
    }

    // ---- model routing ----

    private val server = PcMode("server", localModel = "qwen3.5:35b-a3b")
    private val personal = PcMode("personal")

    @Test fun autoUsesLocalModelOnlyInServerMode() {
        val local = ModelRoute.pick("auto", server, "qwen3.5:9b", "prox-ai", "")
        assertTrue(local.local)
        assertEquals("qwen3.5:35b-a3b", local.model) // the PC's own server-mode model wins
        assertEquals("prox-ai", local.provider)
        assertFalse(ModelRoute.pick("auto", personal, "qwen3.5:35b-a3b", "prox-ai", "").local)
        assertFalse(ModelRoute.pick("auto", null, "qwen3.5:35b-a3b", "prox-ai", "").local)
        assertFalse(ModelRoute.pick("auto", PcMode("offline"), "qwen3.5:35b-a3b", "prox-ai", "").local)
    }

    @Test fun manualChoicesOverrideTheMode() {
        assertFalse(ModelRoute.pick("cloud", server, "q", "prox-ai", "").local)
        val forced = ModelRoute.pick("local", personal, "qwen3.5:35b-a3b", "prox-ai", "")
        assertTrue(forced.local)
        assertEquals("qwen3.5:35b-a3b", forced.model)
        assertEquals("opus", ModelRoute.pick("cloud", personal, "q", "p", "opus").model)
    }

    @Test fun healthAndModeParsing() {
        assertTrue(PcModeMonitor.parseHealth("""{"status":"ok","idle_exit_seconds":31536000}""").isServer)
        assertEquals("unknown", PcModeMonitor.parseHealth("""{"status":"ok","idle_exit_seconds":300}""").mode)
        val m = PcModeMonitor.parseMode("""{"mode":"server","local_model":"qwen3.5:35b-a3b","since":"x"}""")
        assertTrue(m.isServer)
        assertEquals("qwen3.5:35b-a3b", m.localModel)
    }

    @Test fun monitorFallsBackToHealthAndTreats502AsPersonal() = runBlocking {
        val web = MockWebServer()
        var voiceUp = true
        web.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                !voiceUp -> MockResponse().setResponseCode(502)
                request.path == "/voice/v1/mode" -> MockResponse().setResponseCode(404).setBody("""{"error":"not found"}""")
                request.path == "/voice/health" -> MockResponse().setBody("""{"status":"ok","idle_exit_seconds":31536000}""")
                else -> MockResponse().setResponseCode(404)
            }
        }
        web.start()
        val mon = PcModeMonitor(Fake(web.url("/").toString(), "key"))
        assertEquals("server", mon.refresh()!!.mode)
        voiceUp = false
        assertEquals("personal", mon.refresh()!!.mode)
        web.shutdown()
    }

    @Test fun clientSendsLocalModelWithProviderAndReadsUsage() = runBlocking {
        val web = MockWebServer()
        val body = buildString {
            append("data: {\"model\": \"qwen3.5:35b-a3b\", \"choices\": [{\"index\": 0, \"delta\": {\"content\": \"pong\"}}]}\n\n")
            append("data: {\"model\": \"qwen3.5:35b-a3b\", \"choices\": [{\"index\": 0, \"delta\": {}, \"finish_reason\": \"stop\"}], " +
                "\"usage\": {\"prompt_tokens\": 20952, \"completion_tokens\": 30, \"total_tokens\": 20982}}\n\n")
            append("data: [DONE]\n\n")
        }
        web.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        web.start()
        val route = ModelRoute("qwen3.5:35b-a3b", "prox-ai", local = true)
        val events = HermesClient(Fake(web.url("/").toString(), "key")).stream(null, "ping", "s", route).toList()
        val sent = JSONObject(web.takeRequest().body.readUtf8())
        assertEquals("qwen3.5:35b-a3b", sent.getString("model"))
        assertEquals("prox-ai", sent.getString("provider"))
        val done = events.last() as StreamEvent.Done
        assertEquals(20952, done.usage!!.promptTokens)
        assertEquals(30, done.usage!!.completionTokens)
        assertEquals("qwen3.5:35b-a3b", done.model)
        web.shutdown()
    }

    @Test fun thinkingIsReportedOnceBeforeTheAnswer() = runBlocking {
        val web = MockWebServer()
        val chunk = { d: String -> "data: {\"model\": \"qwen3.5:35b-a3b\", \"choices\": [{\"index\": 0, \"delta\": $d}]}\n\n" }
        val body = chunk("{\"reasoning_content\": \"The user\"}") + chunk("{\"reasoning_content\": \" wants\"}") +
            chunk("{\"content\": \"Hi\"}") + "data: [DONE]\n\n"
        web.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body))
        web.start()
        val events = HermesClient(Fake(web.url("/").toString(), "key")).stream(null, "hi", "s").toList()
        assertEquals(1, events.count { it is StreamEvent.Thinking })
        assertTrue(events.indexOfFirst { it is StreamEvent.Thinking } < events.indexOfFirst { it is StreamEvent.Delta })
        assertEquals("Hi", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        web.shutdown()
    }

    @Test fun cloudRouteSendsNoModelOrProvider() = runBlocking {
        val web = MockWebServer()
        web.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: [DONE]\n\n"))
        web.start()
        HermesClient(Fake(web.url("/").toString(), "key")).stream(null, "hi", "s", ModelRoute.CLOUD_DEFAULT).toList()
        val sent = JSONObject(web.takeRequest().body.readUtf8())
        assertFalse(sent.has("model"))
        assertFalse(sent.has("provider"))
        web.shutdown()
    }

    // ---- reminders ----

    @Test fun reminderTagsAreParsedInLocalTime() {
        val reply = "Got it, I'll remind you at 5:30.\n[[remind: 2026-10-22T17:30 | Call Mom]]\n" +
            "[[Reminder: 2026-10-23 9:05 | Gas up the Jeep]]"
        val r = ReminderStore.parse(reply, zone)
        assertEquals(2, r.size)
        assertEquals(LocalDateTime.of(2026, 10, 22, 17, 30).atZone(zone).toInstant().toEpochMilli(), r[0].at)
        assertEquals("Call Mom", r[0].text)
        assertEquals(LocalDateTime.of(2026, 10, 23, 9, 5).atZone(zone).toInstant().toEpochMilli(), r[1].at)
        assertEquals("Gas up the Jeep", r[1].text)
        assertEquals("Got it, I'll remind you at 5:30.", ReminderStore.strip(reply))
    }

    @Test fun badReminderTagsAreIgnored() {
        assertTrue(ReminderStore.parse("[[remind: tomorrow | x]] [[remind: 2026-13-45T25:99 | y]]", zone).isEmpty())
    }

    // ---- context / cost ----

    @Test fun statsCoverOnlyTheCurrentSession() {
        val msgs = listOf(
            ChatMessage(role = "assistant", text = "a", promptTokens = 90_000, completionTokens = 1_000),
            ChatMessage(role = "note", text = "The conversation was compacted. Your next message..."),
            ChatMessage(role = "user", text = "hi"),
            ChatMessage(role = "assistant", text = "b", promptTokens = 22_000, completionTokens = 500),
            ChatMessage(role = "assistant", text = "c", promptTokens = 21_000, completionTokens = 100, local = true),
        )
        val s = ChatRepository.stats(msgs)
        assertEquals(21_000, s.lastPromptTokens)
        assertEquals(43_000L, s.inputTokens)
        // Only the cloud reply costs money: 22k * $2/M + 500 * $10/M.
        assertEquals(0.049, s.costUsd, 1e-9)
        assertEquals(3, ChatRepository.sessionMessages(msgs).size)
    }
}

private class Fake(override val serverUrl: String, override val apiKey: String, override val model: String = "") : HermesConfig
