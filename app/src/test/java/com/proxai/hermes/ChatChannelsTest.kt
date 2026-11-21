package com.proxai.hermes

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.proxai.hermes.data.AppStore
import com.proxai.hermes.data.Channel
import com.proxai.hermes.data.ChatRepository
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.ModelRoute
import com.proxai.hermes.data.PcModeMonitor
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Phase 2, C4 + C7 (2026-10-29): the real ChatRepository with channels and the trash, against a
 * mocked Hermes API server, on its own "main" thread like in the app.
 */
@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
class ChatChannelsTest {
    private data class Sent(val session: String, val body: String)

    private val main = newSingleThreadContext("test-main")
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val server = MockWebServer()
    private val sent = CopyOnWriteArrayList<Sent>()
    @Volatile private var replyDelayMs = 0L
    private lateinit var settings: Settings
    private lateinit var repo: ChatRepository

    @Before fun setUp() {
        Dispatchers.setMain(main)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/v1/chat/completions") != true) return MockResponse().setResponseCode(404)
                val body = request.body.readUtf8()
                sent += Sent(request.getHeader("X-Hermes-Session-Id").orEmpty(), body)
                val word = "Reply${sent.size}"
                return MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBodyDelay(replyDelayMs, TimeUnit.MILLISECONDS)
                    .setBody("data: {\"choices\": [{\"delta\": {\"content\": \"$word\"}}]}\n\ndata: [DONE]\n\n")
            }
        }
        server.start()
        val dir = Files.createTempDirectory("chat").toFile()
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

    /** Waits on the main thread, so it never looks between one reply ending and a queued one starting. */
    private fun idle() = runBlocking {
        withTimeout(10_000) { withContext(main) { delay(50); while (repo.busy.value) delay(20) } }
    }

    @Test fun eachChannelHasItsOwnSessionModelAndMessages() {
        val hw = onMain { repo.createChannel("Homework", ModelRoute.OPUS) }
        assertEquals(hw.id, repo.active.value)
        onMain { repo.send("explain pointers") }
        idle()
        assertEquals(hw.sessionId, sent[0].session)
        assertTrue("channel: #Homework" in sent[0].body)
        assertTrue(ModelRoute.OPUS in sent[0].body)
        assertEquals(listOf("explain pointers", "Reply1"), repo.messages.value.map { it.text })

        onMain { repo.switchTo(Channel.DM) }
        assertTrue(repo.messages.value.isEmpty())
        onMain { repo.send("hey") }
        idle()
        assertEquals(settings.sessionId, sent[1].session)
        assertNotEquals(hw.sessionId, settings.sessionId)
        assertFalse("channel:" in sent[1].body)
        assertTrue(ModelRoute.SONNET in sent[1].body)             // the DM follows Settings (Sonnet by default, X1-PM)

        onMain { repo.switchTo(hw.id) }
        assertEquals(listOf("explain pointers", "Reply1"), repo.messages.value.map { it.text })
        assertEquals(Channel.DM, onMain { repo.channelList.value.first().id })
    }

    @Test fun aReplyFinishesInItsOwnChannelAndTheNextMessageWaits() {
        val hw = onMain { repo.createChannel("Homework", null) }
        replyDelayMs = 400
        onMain { repo.send("long question") }
        onMain { repo.switchTo(Channel.DM) }
        assertEquals(hw.id, repo.busyChannel.value)
        onMain { repo.send("quick one") }                          // waits for the Homework reply
        assertEquals("quick one", repo.queuedText.value)
        idle()
        assertEquals(listOf(hw.sessionId, settings.sessionId), sent.map { it.session })
        assertEquals(listOf("quick one", "Reply2"), repo.messages.value.map { it.text })
        onMain { repo.switchTo(hw.id) }
        assertEquals(listOf("long question", "Reply1"), repo.messages.value.map { it.text })
        assertNull(repo.queuedText.value)
    }

    @Test fun newChatGoesToTheTrashAndRestoreBringsBackItsMemory() {
        onMain { repo.send("remember 42") }
        idle()
        val old = settings.sessionId
        onMain { repo.newChat() }
        assertTrue(repo.messages.value.isEmpty())
        assertNotEquals(old, settings.sessionId)
        val item = onMain { repo.trashItems() }.single()
        assertEquals("remember 42", item.title)
        assertEquals(0, onMain { repo.restoreDisplaces(item) })        // B12: empty DM, so Restore needn't ask
        onMain { repo.send("new topic") }
        idle()
        assertEquals(2, onMain { repo.restoreDisplaces(item) })        // "new topic" + its reply would move
        assertTrue(onMain { repo.restore(item.id) })
        assertEquals(listOf("remember 42", "Reply1"), repo.messages.value.map { it.text })
        assertEquals(old, settings.sessionId)                        // the PC's memory of it comes back
        // What was there ("new topic") went to the trash instead of being lost.
        assertEquals(listOf("new topic"), onMain { repo.trashItems() }.map { it.title })
    }

    @Test fun aDeletedChannelComesBackWithItsNameModelAndSession() {
        val c = onMain { repo.createChannel("One Touch", ModelRoute.SONNET) }
        onMain { repo.send("quote for siding") }
        idle()
        onMain { repo.deleteChannel(c.id) }
        assertEquals(Channel.DM, repo.active.value)
        assertEquals(listOf(Channel.DM), repo.channelList.value.map { it.id })
        val item = onMain { repo.trashItems() }.single()
        assertTrue(item.channelDeleted)
        assertEquals(0, onMain { repo.restoreDisplaces(item) })        // the channel is gone: nothing to move
        assertTrue(onMain { repo.restore(item.id) })
        val back = repo.channelList.value.single { !it.isDm }
        assertEquals(listOf(c.id, "One Touch", ModelRoute.SONNET, c.sessionId), listOf(back.id, back.name, back.model, back.sessionId))
        assertEquals(c.id, repo.active.value)
        assertEquals("quote for siding", repo.messages.value.first().text)
    }

    @Test fun noticesLandInTheDmAndGoAlongOnlyWithDmMessages() {
        val hw = onMain { repo.createChannel("Homework", null) }
        assertTrue(onMain { repo.addNotice("notice:7", "rhythm", "☀️ Today", "1. Gym", 1L) })
        assertFalse(onMain { repo.addNotice("notice:7", "rhythm", "☀️ Today", "1. Gym", 1L) })   // once
        assertTrue(repo.messages.value.isEmpty())                   // Homework is on screen
        onMain { repo.send("homework q") }
        idle()
        assertFalse("Context, not his words" in sent[0].body)
        onMain { repo.switchTo(Channel.DM) }
        assertEquals("notice", repo.messages.value.single().role)
        onMain { repo.send("done 1") }
        idle()
        assertTrue("Context, not his words" in sent[1].body && "1. Gym" in sent[1].body)
        assertEquals(hw.sessionId, sent[0].session)
    }

    @Test fun attachmentsGoWithTheMessage() {
        val dir = Files.createTempDirectory("att").toFile()
        val pic = java.io.File(dir, "p.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val txt = java.io.File(dir, "lab4.txt").apply { writeText("x = 1") }
        val atts = listOf(com.proxai.hermes.data.Attachment("photo.jpg", "image", listOf(pic.path)),
            com.proxai.hermes.data.Attachment("lab4.py", "text", listOf(txt.path)))
        onMain { repo.send("", attachments = atts) }                 // a picture with no words is fine
        idle()
        val body = org.json.JSONObject(sent[0].body)
        val parts = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        val text = parts.getJSONObject(0).getString("text")
        assertTrue("[Attached: photo.jpg (picture), lab4.py (text, below)]" in text)
        assertTrue("--- Attached file: lab4.py ---\nx = 1\n--- End of lab4.py ---" in text)
        assertEquals("data:image/jpeg;base64,AQID", parts.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals(atts, repo.messages.value.first().attachments)
    }

    @Test fun newChatMidReplyDoesNotLeaveTheChatStuck() {
        replyDelayMs = 2_000
        onMain { repo.send("slow") }
        onMain { repo.newChat() }
        idle()
        assertFalse(repo.busy.value)
        assertNull(repo.busyChannel.value)
        replyDelayMs = 0
        onMain { repo.send("after") }
        idle()
        assertEquals("after", repo.messages.value.first().text)
    }
}

/** SharedPreferences in memory (the real one needs Android). */
internal class MemPrefs : SharedPreferences {
    private val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map.toMutableMap()
    override fun getString(key: String, defValue: String?) = map[key] as String? ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, defValues: MutableSet<String>?) = map[key] as MutableSet<String>? ?: defValues
    override fun getInt(key: String, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String) = key in map
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String, value: String?) = apply { map[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?) = apply { map[key] = values }
        override fun putInt(key: String, value: Int) = apply { map[key] = value }
        override fun putLong(key: String, value: Long) = apply { map[key] = value }
        override fun putFloat(key: String, value: Float) = apply { map[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { map[key] = value }
        override fun remove(key: String) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() {}
    }
}
