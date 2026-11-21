package com.proxai.hermes

import com.proxai.hermes.data.AppStore
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.HermesConfig
import com.proxai.hermes.data.StreamEvent
import com.proxai.hermes.ui.Seg
import com.proxai.hermes.ui.segments
import com.proxai.hermes.voice.SpeechController
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    private val app = """<!DOCTYPE html>
<html><head><title>Tip Splitter</title>
<meta name="description" content="Split a bill fairly"></head>
<body><script>1</script></body></html>"""

    @Test fun extractsHtmlAppFromReply() {
        val reply = "Here's a tip calculator.\n\n```html\n$app\n```\nEnjoy!"
        val html = AppStore.extractHtml(reply)
        assertNotNull(html)
        assertEquals("Tip Splitter", AppStore.extractTitle(html!!))
        assertEquals("Split a bill fairly", AppStore.extractDescription(html))
    }

    @Test fun ignoresNonAppCode() {
        assertNull(AppStore.extractHtml("```python\nprint(1)\n```"))
        assertNull(AppStore.extractHtml("```html\n<b>bold</b>\n```"))
    }

    @Test fun segmentsHandleStreamingFence() {
        val segs = segments("Building it\n```html\n<!DOCTYPE html>\n<html>")
        assertEquals(2, segs.size)
        val code = segs[1] as Seg.Code
        assertEquals("html", code.lang)
        assertTrue(!code.closed)
    }

    @Test fun spokenTextDropsCode() {
        assertEquals("Here's your timer.", SpeechController.spokenText("**Here's** your timer.\n```html\n<html></html>\n```"))
        assertEquals("Your app is ready.", SpeechController.spokenText("```html\n<html></html>\n```"))
    }

    /** Frames copied from Hermes' _sse_frame + keepalive format (gateway/platforms/api_server.py). */
    @Test fun parsesHermesStream() = runBlocking {
        val server = MockWebServer()
        val body = buildString {
            append(": keepalive\n\n")
            append("event: hermes.tool.progress\ndata: {\"tool\": \"web_search\", \"emoji\": \"\\ud83d\\udd0d\", \"label\": \"search: weather\", \"toolCallId\": \"t1\", \"status\": \"running\"}\n\n")
            append("data: {\"id\": \"c\", \"object\": \"chat.completion.chunk\", \"choices\": [{\"index\": 0, \"delta\": {\"role\": \"assistant\"}}]}\n\n")
            append("data: {\"id\": \"c\", \"object\": \"chat.completion.chunk\", \"choices\": [{\"index\": 0, \"delta\": {\"content\": \"Hello \"}}]}\n\n")
            append("event: hermes.tool.progress\ndata: {\"tool\": \"web_search\", \"toolCallId\": \"t1\", \"status\": \"completed\"}\n\n")
            append("data: {\"id\": \"c\", \"object\": \"chat.completion.chunk\", \"choices\": [{\"index\": 0, \"delta\": {\"content\": \"there\"}, \"finish_reason\": null}]}\n\n")
            append("data: [DONE]\n\n")
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setHeader("X-Hermes-Session-Id", "android-test").setBody(body))
        server.start()

        val settings = FakeSettings(server.url("/v1/").toString(), "k".repeat(40))
        val events = HermesClient(settings).stream("sys", "hi", "android-test").toList()

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer " + "k".repeat(40), req.getHeader("Authorization"))
        assertEquals("android-test", req.getHeader("X-Hermes-Session-Id"))
        assertTrue(req.body.readUtf8().contains("\"stream\":true"))

        val text = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
        assertEquals("Hello there", text)
        val tools = events.filterIsInstance<StreamEvent.Tool>()
        assertEquals(2, tools.size)
        assertEquals("search: weather", tools[0].label)
        assertTrue(tools[0].running && !tools[1].running)
        assertEquals("android-test", (events.last() as StreamEvent.Done).sessionId)
        server.shutdown()
    }

    @Test fun reportsBadKey() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401)
            .setBody("{\"error\": {\"message\": \"Invalid gateway API key (API_SERVER_KEY)\"}}"))
        server.start()
        val events = HermesClient(FakeSettings(server.url("/").toString(), "bad")).stream(null, "hi", "s").toList()
        val err = events.single() as StreamEvent.Error
        assertTrue(err.message, err.message.contains("API key"))
        server.shutdown()
    }
}

private class FakeSettings(
    override val serverUrl: String,
    override val apiKey: String,
    override val model: String = "",
) : HermesConfig
