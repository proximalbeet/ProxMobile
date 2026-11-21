package com.proxai.hermes

import com.proxai.hermes.data.ApkPart
import com.proxai.hermes.data.AppResearch
import com.proxai.hermes.data.AppResearchException
import com.proxai.hermes.data.AppResearchFileSent
import com.proxai.hermes.data.AppResearchSent
import com.proxai.hermes.data.Attachment
import com.proxai.hermes.data.Attachments
import com.proxai.hermes.data.ChatMessage
import com.proxai.hermes.data.PhoneApp
import com.proxai.hermes.data.PhoneApps
import com.proxai.hermes.data.PhoneWidget
import com.proxai.hermes.data.messagesFromJson
import com.proxai.hermes.data.messagesToJson
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * P3 (0.9.5): someone else's app on this phone (its APK files), or a firmware image / other file, goes to
 * the PC for Claude Code + REA. Nathan: REA is for other apps, widgets and firmware, not his own apps.
 */
class AppResearchTest {
    private lateinit var server: MockWebServer
    private lateinit var dir: File
    private val key = "test-key-0123456789abcdef"

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        dir = Files.createTempDirectory("phone-apps").toFile()
    }

    @After fun tearDown() {
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun apk(name: String, size: Int): File =
        File(dir, name).apply { writeBytes(byteArrayOf(0x50, 0x4B, 3, 4) + ByteArray(size - 4) { (it % 251).toByte() }) }

    private fun notes(vararg files: File, widgets: List<PhoneWidget> = emptyList()) =
        PhoneApp("Notes Plus", "com.example.notes", "4.2.0", 42, files.map { ApkPart(it.name, it) }, widgets = widgets)

    private fun client() = AppResearch(server.url("/").toString().trimEnd('/'), key)

    @Test fun sendsOnlyTheFilesThePcLacks() {
        val base = apk("base.apk", 3000)
        val split = apk("split_config.arm64_v8a.apk", 500)
        server.enqueue(MockResponse().setBody(
            """{"folder":"app-research/com.example.notes-42","files":{"base.apk":{"size":3000,"sha256":"x"}},"ready":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))
        val progress = mutableListOf<Pair<Long, Long>>()
        val sent = client().send(notes(base, split)) { done, total -> progress += done to total }

        assertEquals(AppResearchSent("app-research/com.example.notes-42", files = 2, skipped = 1, bytesSent = 500, bytes = 3500), sent)
        val ask = server.takeRequest()
        assertEquals("GET", ask.method)
        assertEquals("/apps/v1/research/com.example.notes/42?bytes=3500", ask.path)
        assertEquals("Bearer $key", ask.getHeader("Authorization"))
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/apps/v1/research/com.example.notes/42/split_config.arm64_v8a.apk?label=Notes+Plus&version=4.2.0", put.path)
        assertEquals("Bearer $key", put.getHeader("Authorization"))
        assertEquals("application/vnd.android.package-archive", put.getHeader("Content-Type"))
        assertArrayEquals(split.readBytes(), put.body.readByteArray())
        assertEquals(500L to 500L, progress.last())
        assertEquals(2, server.requestCount)                      // base.apk was already there: not sent again
    }

    @Test fun saysWhyThePcCantTakeIt() {
        server.enqueue(MockResponse().setBody(
            """{"folder":"app-research/com.example.notes-42","files":{},"ready":false,"error":"the PC has no app-research folder yet (~/dev/proxai/app-research)"}"""))
        val e = assertThrows(AppResearchException::class.java) { client().send(notes(apk("base.apk", 100))) }
        assertEquals("The PC has no app-research folder yet (~/dev/proxai/app-research)", e.message)
        assertEquals(1, server.requestCount)                      // nothing uploaded
    }

    @Test fun aRefusedUploadSaysWhy() {
        server.enqueue(MockResponse().setBody("""{"files":{},"ready":true}"""))
        server.enqueue(MockResponse().setResponseCode(507).setBody("""{"error":"the PC's disk is almost full"}"""))
        val e = assertThrows(AppResearchException::class.java) { client().send(notes(apk("base.apk", 100))) }
        assertEquals("The PC's disk is almost full", e.message)
    }

    @Test fun anOlderPcAndAWrongKeySayItPlainly() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))
        val old = assertThrows(AppResearchException::class.java) { client().send(notes(apk("base.apk", 100))) }
        assertTrue(old.message!!, old.message!!.contains("needs the P3 update"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"invalid API key"}"""))
        val bad = assertThrows(AppResearchException::class.java) { client().send(notes(apk("base.apk", 100))) }
        assertEquals("The PC rejected the API key", bad.message)
    }

    @Test fun aNewSendStartsAfterACancel() {
        val c = client()
        c.cancel()
        server.enqueue(MockResponse().setBody("""{"files":{},"ready":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"ok":true}"""))
        assertEquals(1, c.send(notes(apk("base.apk", 100))).files)
    }

    @Test fun sendsAFirmwareFile() {
        val fw = File(dir, "copy-1234").apply { writeBytes(ByteArray(2048) { 0x7F }) }
        server.enqueue(MockResponse().setBody("""{"folder":"app-research/files","ready":true}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"ok":true,"folder":"app-research/files","file":"20261031-171500-router_fw_v2.bin","size":2048,"sha256":"ab"}"""))
        val sent = client().sendFile("router fw v2.bin", fw)
        assertEquals(AppResearchFileSent("app-research/files", "20261031-171500-router_fw_v2.bin", 2048, "ab"), sent)
        assertEquals("/apps/v1/research/files?bytes=2048", server.takeRequest().path)
        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/apps/v1/research/files/router%20fw%20v2.bin", put.path)
        assertEquals("application/octet-stream", put.getHeader("Content-Type"))
        assertArrayEquals(fw.readBytes(), put.body.readByteArray())

        val note = Attachments.note(listOf(PhoneApps.fileAttachment("router fw v2.bin", sent)))
        assertEquals("[Attached: router fw v2.bin (file from this phone, 3 KB, now on the PC at ~/dev/proxai/app-research/files/" +
            "20261031-171500-router_fw_v2.bin. To look inside it (firmware, an APK file, a binary), use claude_code with project " +
            "app-research and reverse_engineering true; it's at files/20261031-171500-router_fw_v2.bin)]", note)
    }

    @Test fun aFirmwareFileThePcCantTakeSaysWhy() {
        server.enqueue(MockResponse().setBody("""{"folder":"app-research/files","ready":false,"error":"app-research on the PC is full (20 GB): clear old apps there"}"""))
        val e = assertThrows(AppResearchException::class.java) { client().sendFile("x.bin", File(dir, "c").apply { writeBytes(ByteArray(10)) }) }
        assertEquals("App-research on the PC is full (20 GB): clear old apps there", e.message)
        assertEquals(1, server.requestCount)
    }

    @Test fun theNoteSaysWhereTheFilesAreAndNamesTheWidgets() {
        val sent = AppResearchSent("app-research/com.example.notes-42", files = 3, skipped = 0, bytesSent = 152_000_000, bytes = 152_000_000)
        val widgets = listOf(PhoneWidget("Quick note", "com.example.notes.widget.QuickNoteWidget"),
            PhoneWidget("Notes [list]", "com.example.notes.widget.ListWidget"))
        val a = PhoneApps.attachment(notes(apk("base.apk", 100), widgets = widgets), "/data/attachments/icon.png", sent)
        assertEquals(Attachment.APP, a.kind)
        val note = Attachments.note(listOf(a))
        assertTrue(note, note.startsWith("[Attached: Notes Plus (app from this phone: package com.example.notes, version 4.2.0 " +
            "(code 42), 3 APK files (base + 2 splits), 152 MB, home-screen widgets: Quick note " +
            "(com.example.notes.widget.QuickNoteWidget), Notes (list) (com.example.notes.widget.ListWidget), now on the PC in " +
            "~/dev/proxai/app-research/com.example.notes-42/."))
        assertTrue(note, note.contains("use claude_code with project app-research and reverse_engineering true; its files are in com.example.notes-42/"))
        assertEquals(1, note.count { it == '[' })                  // a widget's own brackets don't break the note
        assertEquals(0, Attachments.imageCount(listOf(a)))          // the icon is for the bubble, not the model
        assertTrue(Attachments.dataUrls(listOf(a)).isEmpty())
    }

    @Test fun theMainApkIsAlwaysBaseApk() {
        // A preinstalled app keeps its own name on the phone (/system/priv-app/Settings/Settings.apk).
        val main = apk("Settings.apk", 100)
        val split = apk("split_config.en.apk", 50)
        val odd = apk("split_bad-name.apk", 50)
        val parts = PhoneApps.parts(main.path, arrayOf(split.path, odd.path, File(dir, "missing.apk").path))
        assertEquals(listOf(ApkPart("base.apk", main), ApkPart("split_config.en.apk", split)), parts)
        assertTrue(PhoneApps.parts(File(dir, "gone.apk").path, null).isEmpty())
        assertTrue(PhoneApps.parts(null, null).isEmpty())
    }

    @Test fun nathansOwnAppsArentOffered() {
        assertTrue(PhoneApps.isOwn("com.proxai.hermes"))                       // ProxAI itself
        assertTrue(PhoneApps.isOwn("com.proxai.apps.nothing_else_to_suppress_deliver"))   // a PC-built app
        assertFalse(PhoneApps.isOwn("com.instagram.android"))
        assertFalse(PhoneApps.isOwn("com.proxaix.other"))
        assertTrue(PhoneApps.validPackage("com.instagram.android"))
        assertFalse(PhoneApps.validPackage("instagram"))
        assertFalse(PhoneApps.validPackage("com..example"))
        assertEquals(listOf("152 MB", "3.5 MB", "2 KB"), listOf(152_000_000L, 3_500_000L, 1_500L).map(PhoneApps::mb))
    }

    @Test fun theAttachmentsSurviveTheChatFile() {
        val app = Attachment("Notes Plus", Attachment.APP, listOf("/data/attachments/icon.png"), info = "app from this phone: package com.example.notes")
        val fw = Attachment("router.bin", Attachment.PC_FILE, emptyList(), info = "file from this phone, 3 KB")
        val back = messagesFromJson(messagesToJson(listOf(ChatMessage(id = "m1", role = "user", text = "what is it built with?",
            attachments = listOf(app, fw)))))
        assertEquals(listOf(app, fw), back.single().attachments)
        assertEquals(0, Attachments.imageCount(listOf(fw)))
        val old = Attachment("photo.jpg", Attachment.IMAGE, listOf("/data/attachments/p.jpg"))   // chats saved before 0.9.5
        assertEquals("", messagesFromJson(messagesToJson(listOf(ChatMessage(id = "m2", role = "user", text = "x", attachments = listOf(old)))))
            .single().attachments.single().info)
    }
}
