package com.proxai.hermes

import com.proxai.hermes.data.ApkPart
import com.proxai.hermes.data.AppResearch
import com.proxai.hermes.data.Attachment
import com.proxai.hermes.data.PhoneApp
import com.proxai.hermes.data.ResearchSend
import com.proxai.hermes.data.ResearchSends
import com.proxai.hermes.data.ResearchTarget
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 0.9.6: sends to the PC run outside any screen (ResearchSends + ResearchSendService), so leaving ProxAI no longer
 * cuts them off. The chat shows ResearchSends.current and takes finished attachments from ResearchSends.ready.
 */
class ResearchSendsTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File

    @Before fun setUp() {
        ResearchSends.reset()
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("sends").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
        ResearchSends.reset()
    }

    private fun client() = AppResearch(server.url("/").toString().trimEnd('/'), "test-key")

    private fun clock(): PhoneApp {
        val base = File(dir, "DeskClock.apk").apply { writeBytes(byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(4000)) }
        return PhoneApp("Clock", "com.android.deskclock", "15", 35, listOf(ApkPart("base.apk", base)))
    }

    @Test fun anAppGoesToThePcAndWaitsForTheChat() {
        server.enqueue(MockResponse().setBody("""{"files":{},"ready":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))
        assertTrue(ResearchSends.claim("Clock"))
        val seen = mutableListOf<ResearchSend>()
        val a = ResearchSends.run(ResearchTarget.App(clock()), client(), icon = { "/icons/$it.png" }) { seen += it }

        assertEquals(Attachment.APP, a!!.kind)
        assertEquals(listOf("/icons/com.android.deskclock.png"), a.files)
        assertTrue(seen.any { !it.finished && it.total == 4004L && it.done == 4004L })   // progress came through
        val end = ResearchSends.current.value!!
        assertTrue(end.finished)
        assertNull(end.error)
        assertFalse(ResearchSends.busy)
        assertEquals(listOf(a), ResearchSends.ready.value)                               // waits for the next message
        assertEquals(listOf(a), ResearchSends.takeReady())
        assertTrue(ResearchSends.takeReady().isEmpty())                                  // handed out once
    }

    @Test fun aFinishedOneCanBeTakenOutOfTheTray() {
        server.enqueue(MockResponse().setBody("""{"files":{"base.apk":{"size":4004}},"ready":true}"""))
        ResearchSends.claim("Clock")
        val a = ResearchSends.run(ResearchTarget.App(clock()), client())!!
        assertTrue(ResearchSends.remove(a))
        assertFalse(ResearchSends.remove(a))
        assertTrue(ResearchSends.ready.value.isEmpty())
    }

    @Test fun oneSendAtATime() {
        assertTrue(ResearchSends.claim("Clock"))
        assertTrue(ResearchSends.busy)
        assertFalse(ResearchSends.claim("Camera"))
        assertEquals("Clock", ResearchSends.current.value!!.title)
        server.enqueue(MockResponse().setBody("""{"files":{"base.apk":{"size":4004}},"ready":true}"""))   // already there
        ResearchSends.run(ResearchTarget.App(clock()), client())
        assertTrue(ResearchSends.claim("Camera"))                                         // free again
    }

    @Test fun aFailureStaysInTheTrayUntilCleared() {
        server.enqueue(MockResponse().setBody("""{"files":{},"ready":false,"error":"the PC's disk is almost full"}"""))
        ResearchSends.claim("Clock")
        assertNull(ResearchSends.run(ResearchTarget.App(clock()), client()))
        val s = ResearchSends.current.value!!
        assertEquals("The PC's disk is almost full", s.error)
        assertTrue(s.finished)
        assertTrue(ResearchSends.takeReady().isEmpty())
        ResearchSends.dismiss()
        assertNull(ResearchSends.current.value)
    }

    @Test fun cancelBeforeItStartsSendsNothing() {
        ResearchSends.claim("Clock")
        ResearchSends.cancel()
        assertNull(ResearchSends.current.value)
        assertNull(ResearchSends.run(ResearchTarget.App(clock()), client()))
        assertEquals(0, server.requestCount)
        assertNull(ResearchSends.current.value)
    }

    @Test fun aPickedFilesCopyIsDeletedEitherWay() {
        val copy = File(dir, "copy-1").apply { writeBytes(ByteArray(300) { 7 }) }
        server.enqueue(MockResponse().setBody("""{"folder":"app-research/files","ready":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"ok":true,"folder":"app-research/files","file":"20261101-101500-fw.bin","size":300,"sha256":"x"}"""))
        ResearchSends.claim("fw.bin")
        val a = ResearchSends.run(ResearchTarget.PickedFile("fw.bin", copy), client())
        assertEquals(Attachment.PC_FILE, a!!.kind)
        assertFalse(copy.exists())

        val copy2 = File(dir, "copy-2").apply { writeBytes(ByteArray(10)) }
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid API key"}"""))
        ResearchSends.claim("fw2.bin")
        assertNull(ResearchSends.run(ResearchTarget.PickedFile("fw2.bin", copy2), client()))
        assertFalse(copy2.exists())
        assertEquals("The PC rejected the API key", ResearchSends.current.value!!.error)
    }
}
