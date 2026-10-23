package com.proxai.hermes

import com.proxai.hermes.data.ChatMessage
import com.proxai.hermes.data.ChatRepository
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.HermesConfig
import com.proxai.hermes.data.StreamEvent
import com.proxai.hermes.data.ToolChip
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.4.1: auto-compact measures correctly; limits unchanged. */
class CompactTest {
    private fun tools(n: Int) = (1..n).map { ToolChip("t$it", "tool", "🛠", false) }

    @Test fun cloudEstimateDividesByModelCalls() {
        // 3 tool steps = 4 model calls of ~16k each: 64k "read", but the conversation is ~16k.
        val reply = ChatMessage(role = "assistant", text = "x", promptTokens = 64_000, tools = tools(3))
        assertEquals(16_000, ChatRepository.contextEstimate(reply))
        assertEquals(31_000, ChatRepository.contextEstimate(reply.copy(promptTokens = 31_000, tools = emptyList())))
    }

    @Test fun statsShowLocalAsManagedByThePc() {
        val user = ChatMessage(role = "user", text = "hi")
        val local = ChatMessage(role = "assistant", text = "x", promptTokens = 412, local = true)
        val s = ChatRepository.stats(listOf(user, local))
        assertTrue(s.localManaged)
        assertEquals(0, s.lastPromptTokens)
        val cloud = ChatMessage(role = "assistant", text = "y", promptTokens = 70_000, tools = tools(1))
        val s2 = ChatRepository.stats(listOf(user, local, user.copy(id = "u2"), cloud))
        assertFalse(s2.localManaged)
        assertEquals(35_000, s2.lastPromptTokens)
    }

    @Test fun recognisesHermesCompactionStatus() {
        // Exact texts from hermes-agent agent/conversation_compression.py.
        val start = StreamEvent.Status("lifecycle", "🗜️ Compacting context — summarizing earlier conversation so I can continue...")
        val beat = StreamEvent.Status("lifecycle", "🗜️ Compacting context — still summarizing earlier conversation so I can continue...")
        val done = StreamEvent.Status("compacted", "✓ Context compaction complete — continuing turn...")
        assertEquals("start", ChatRepository.pcCompactionStep(start))
        assertEquals("start", ChatRepository.pcCompactionStep(beat))
        assertEquals("done", ChatRepository.pcCompactionStep(done))
        assertNull(ChatRepository.pcCompactionStep(StreamEvent.Status("lifecycle", "Waiting for the provider…")))
    }

    @Test fun streamCarriesHermesStatusEvents() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "event: hermes.status\ndata: {\"kind\": \"lifecycle\", \"text\": \"🗜️ Compacting context — summarizing earlier conversation so I can continue...\"}\n\n" +
                "event: hermes.status\ndata: {\"kind\": \"compacted\", \"text\": \"✓ Context compaction complete — continuing turn...\"}\n\n" +
                "data: {\"choices\": [{\"delta\": {\"content\": \"Hi\"}}]}\n\n" +
                "data: [DONE]\n\n"))
        server.start()
        val cfg = object : HermesConfig {
            override val serverUrl = server.url("/").toString()
            override val apiKey = "k".repeat(40)
            override val model = ""
        }
        val events = HermesClient(cfg).stream(null, "hello", "s1").toList()
        server.shutdown()
        val statuses = events.filterIsInstance<StreamEvent.Status>()
        assertEquals(listOf("lifecycle", "compacted"), statuses.map { it.kind })
        assertEquals("start", ChatRepository.pcCompactionStep(statuses[0]))
        assertEquals("done", ChatRepository.pcCompactionStep(statuses[1]))
        assertEquals("Hi", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
    }
}
