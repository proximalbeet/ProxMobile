package com.proxai.hermes

import com.proxai.hermes.data.HermesConfig
import com.proxai.hermes.voice.ProxVoice
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

class ProxVoiceTest {
    private class Cfg(override val serverUrl: String, override val apiKey: String = "k".repeat(40)) : HermesConfig {
        override val model = ""
    }

    @Test fun firstChunkIsFirstSentenceForFastStart() {
        val parts = ProxVoice.chunks("Done. I built your tip calculator. It splits the bill. Tap open to try it.")
        assertEquals("Done.", parts[0])
        assertEquals("I built your tip calculator. It splits the bill. Tap open to try it.", parts[1])
        assertEquals(2, parts.size)
    }

    @Test fun chunksStayUnderLimit() {
        val long = (1..80).joinToString(" ") { "word$it," } + " end."
        val parts = ProxVoice.chunks("Short one. $long Last sentence.", max = 120)
        assertTrue(parts.all { it.length <= 120 })
        assertEquals("Short one. $long Last sentence.".split(" ").size, parts.joinToString(" ").split(" ").size)
    }

    @Test fun defaultPiecesAreShortEnoughForAFullGpu() {
        // 10-22: 204- and 215-char pieces ran the PC out of GPU memory with the local model loaded.
        val reply = "Alright so here is the deal with the graphics card tonight, the local model holds most " +
            "of the memory and the voice needs a little room, so long pieces fail while short ones work. " +
            "Second sentence that is also fairly long so the packing has to make a decision about it."
        val parts = ProxVoice.chunks(reply)
        assertTrue(parts.joinToString("|"), parts.all { it.length <= ProxVoice.MAX_PIECE })
        assertEquals(180, ProxVoice.MAX_PIECE)
    }

    @Test fun onlyAnUnreachablePcSkipsTheRetry() {
        assertTrue(ProxVoice.unreachable(java.net.ConnectException("refused")))
        assertTrue(ProxVoice.unreachable(IOException("Prox's voice service isn't running on the PC")))
        assertTrue(ProxVoice.unreachable(IOException("ProxAI isn't running")))
        assertTrue(!ProxVoice.unreachable(IOException("voice unavailable: CUDA out of memory. Tried to allocate 382.00 MiB")))
    }

    @Test fun emptyTextGivesNoChunks() {
        assertEquals(emptyList<String>(), ProxVoice.chunks("   "))
    }

    @Test fun bridgeSaysPc() { assertEquals("pc", ProxVoice.SPOKEN_BY_PC) }

    @Test fun jsonInsteadOfAudioIsRejected() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"ok\":true}"))
        server.start()
        try {
            ProxVoice(Cfg(server.url("/").toString()), Files.createTempDirectory("prox-voice").toFile()).synth("Hi.")
            fail("expected an error")
        } catch (e: IOException) {
            assertEquals("Prox's voice sent something that isn't audio", e.message)
        }
        server.shutdown()
    }

    @Test fun synthPostsToVoicePathWithKey() = runBlocking {
        val server = MockWebServer()
        val mp3 = Buffer().write(byteArrayOf(0x49, 0x44, 0x33, 0x04, 1, 2, 3, 4))
        server.enqueue(MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(mp3))
        server.start()
        val dir = Files.createTempDirectory("prox-voice").toFile()

        // Server URL as typed in Settings, with a trailing slash and /v1.
        val file = ProxVoice(Cfg(server.url("/v1/").toString()), dir).synth("Hello there.")

        val req = server.takeRequest()
        assertEquals("/voice/v1/tts", req.path)
        assertEquals("Bearer " + "k".repeat(40), req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("Hello there.", body.getString("text"))
        assertFalse(body.has("voice"))
        assertTrue(file.name.endsWith(".mp3"))
        assertEquals(8, file.length())
        server.shutdown()
    }

    @Test fun serviceDownGivesClearError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502))
        server.start()
        try {
            ProxVoice(Cfg(server.url("/").toString()), Files.createTempDirectory("prox-voice").toFile()).synth("Hi.")
            fail("expected an error")
        } catch (e: IOException) {
            assertEquals("Prox's voice service isn't running on the PC", e.message)
        }
        server.shutdown()
    }
}
