package com.proxai.hermes

import com.proxai.hermes.data.Attachment
import com.proxai.hermes.data.Attachments
import com.proxai.hermes.data.ChatMedia
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.HermesConfig
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Phase 2 (2026-10-29): C6 pictures and PDFs from ProxAI, C5 attachments to it. */
class MediaAttachTest {
    @Test fun mediaTagsBecomePicturesAndFiles() {
        val reply = "Here's your week:\nMEDIA:/output/week-44.png.\nAnd the sheet: MEDIA:`/output/quiz2-cheatsheet.pdf`\n" +
            "The guide is MEDIA:/output/quiz2-study-guide.html for now."
        val parts = ChatMedia.split(reply)
        assertEquals(ChatMedia.Part.Text("Here's your week:"), parts[0])
        assertEquals(ChatMedia.Part.Image("/output/week-44.png"), parts[1])          // trailing "." dropped
        assertEquals(ChatMedia.Part.Text("And the sheet:"), parts[2])
        assertEquals(ChatMedia.Part.File("/output/quiz2-cheatsheet.pdf"), parts[3])
        assertTrue((parts[4] as ChatMedia.Part.Text).s.contains("quiz2-study-guide.html"))   // html stays text
        assertEquals(listOf(ChatMedia.Part.Text("no media here")), ChatMedia.split("no media here"))
        // Paths outside the outbox are never fetched.
        assertEquals(1, ChatMedia.split("MEDIA:/home/prox/.ssh/id_rsa.png").size)
        assertTrue(ChatMedia.split("MEDIA:/home/prox/x.png").single() is ChatMedia.Part.Text)
    }

    @Test fun inlinePicturesFromTheNonStreamedReplyShowToo() {
        val parts = ChatMedia.split("Look ![image](data:image/png;base64,iVBORw0KGgo=) done")
        assertEquals(ChatMedia.Part.Image("data:image/png;base64,iVBORw0KGgo="), parts[1])
        assertEquals("done", (parts[2] as ChatMedia.Part.Text).s)
        assertFalse("MEDIA" in ChatMedia.strip("Chart: MEDIA:/output/a.png"))
    }

    @Test fun whatCanBeAttached() {
        assertEquals(Attachment.IMAGE, Attachments.kindOf("IMG_1.HEIC", "image/heic"))
        assertEquals(Attachment.PDF, Attachments.kindOf("notes.pdf", null))
        assertEquals(Attachment.TEXT, Attachments.kindOf("lab4.py", "application/octet-stream"))
        assertEquals(Attachment.TEXT, Attachments.kindOf("data", "text/csv"))
        assertNull(Attachments.kindOf("essay.docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
        val list = listOf(Attachment("a.jpg", Attachment.IMAGE, listOf("x")), Attachment("n.pdf", Attachment.PDF, listOf("1", "2"), 12),
            Attachment("lab.py", Attachment.TEXT, listOf("t")))
        assertEquals(3, Attachments.imageCount(list))
    }

    @Test fun theMessageSaysWhatIsAttached() {
        val list = listOf(Attachment("photo.jpg", Attachment.IMAGE, listOf("x")),
            Attachment("notes[1].pdf", Attachment.PDF, listOf("1", "2", "3", "4", "5"), 12),
            Attachment("short.pdf", Attachment.PDF, listOf("1"), 1),
            Attachment("lab4.py", Attachment.TEXT, listOf("t")))
        val note = Attachments.note(list)
        assertEquals("[Attached: photo.jpg (picture), notes(1).pdf (PDF: pages 1-5 of 12, as pictures), " +
            "short.pdf (PDF: 1 page, as pictures), lab4.py (text, below)]", note)
        assertEquals("yes", Regex("\\[[^\\]]*\\]").replace("$note\nyes", " ").trim())   // one note for the PC plugins
        val blocks = Attachments.textBlocks(list) { "def f():\n    return 1\n" }
        assertEquals("\n\n--- Attached file: lab4.py ---\ndef f():\n    return 1\n--- End of lab4.py ---", blocks)
        assertEquals("", Attachments.note(emptyList()))
    }

    @Test fun picturesGoAsImageParts() {
        val dir = Files.createTempDirectory("att").toFile()
        val f = File(dir, "p.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val urls = Attachments.dataUrls(listOf(Attachment("p.jpg", Attachment.IMAGE, listOf(f.path)),
            Attachment("t.txt", Attachment.TEXT, listOf(f.path))))
        assertEquals(listOf("data:image/jpeg;base64,AQID"), urls)
        val client = HermesClient(object : HermesConfig {
            override val serverUrl = "http://x"; override val apiKey = "k"; override val model = ""
        })
        assertEquals("hi", client.userContent("hi", emptyList()))
        val parts = client.userContent("what's this?", urls) as JSONArray
        assertEquals("text", parts.getJSONObject(0).getString("type"))
        assertEquals("what's this?", parts.getJSONObject(0).getString("text"))
        assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }
}
