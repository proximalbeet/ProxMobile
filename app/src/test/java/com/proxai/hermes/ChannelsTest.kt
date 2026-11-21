package com.proxai.hermes

import android.content.ContextWrapper
import com.proxai.hermes.data.Channel
import com.proxai.hermes.data.ChannelStore
import com.proxai.hermes.data.ChatMessage
import com.proxai.hermes.data.ModelRoute
import com.proxai.hermes.data.Prompts
import com.proxai.hermes.data.TrashStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Phase 2, C4 channels and C7 trash (2026-10-29). */
class ChannelsTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun namesAndIds() {
        assertTrue(Channel.newId("Homework 2!").matches(Regex("homework-2-[0-9a-f]{4}")))
        assertTrue(Channel.newId("🔥🔥").startsWith("channel-"))
        assertEquals("One Touch", Channel.cleanName("  One   Touch "))
        assertEquals(Channel.MAX_NAME, Channel.cleanName("x".repeat(99)).length)
    }

    @Test fun eachChannelPicksItsModel() {
        // null follows Settings. X1-PM (11-16): a saved "auto" is the cloud model, never the local one.
        assertEquals(ModelRoute.cloud(ModelRoute.HAIKU), Channel.route(null, "auto", "qwen3.5:9b", "prox-ai", ModelRoute.HAIKU))
        assertEquals(ModelRoute.cloud(ModelRoute.HAIKU), Channel.route(null, "cloud", "q", "p", ModelRoute.HAIKU))
        // A picked cloud model wins and is sent with its provider.
        val opus = Channel.route(ModelRoute.OPUS, "auto", "q", "p", ModelRoute.HAIKU)
        assertEquals(ModelRoute(ModelRoute.OPUS, "anthropic", local = false), opus)
        val local = Channel.route("local", "cloud", "qwen3.5:9b", "prox-ai", ModelRoute.HAIKU)
        assertEquals(ModelRoute("qwen3.5:9b", "prox-ai", local = true), local)
        // A channel model not on the list follows Settings' cloud model; never sent as typed.
        assertEquals(ModelRoute.cloud(ModelRoute.HAIKU), Channel.route("gpt-5", "cloud", "q", "p", ModelRoute.HAIKU))
        assertEquals(ModelRoute.cloud(ModelRoute.SONNET), Channel.route("gpt-5", "cloud", "q", "p", "nonsense"))
        assertEquals("Same as Settings", Channel.modelLabel(null))
        assertEquals("Fable 5.2", Channel.modelLabel(ModelRoute.FABLE))
        // A choice saved before the 5.2 switch is read as the current id.
        assertEquals(ModelRoute.FABLE, ModelRoute.current("claude-fable-5-1"))
        assertEquals(ModelRoute.FABLE, ChannelStore.fromJson(org.json.JSONObject().put("id", "x-1").put("model", "claude-fable-5-1"))?.model)
    }

    @Test fun channelsSurviveASaveAndTheDmNeverComesFromTheFile() {
        val c = Channel("homework-ab12", "Homework", ModelRoute.SONNET, "android-abc", "summary", 5)
        assertEquals(c, ChannelStore.fromJson(ChannelStore.toJson(c)))
        assertNull(ChannelStore.fromJson(ChannelStore.toJson(c.copy(id = Channel.DM))))
        assertNull(ChannelStore.fromJson(ChannelStore.toJson(c.copy(model = null)))!!.model)
    }

    @Test fun theChannelIsInEveryMessageForThePc() {
        val note = Prompts.turnNote(false, channel = "Home]work")
        assertTrue("; channel: #Home)work, a separate chat for this topic" in note)
        assertFalse("channel" in Prompts.turnNote(false))
        // Still one [...] note, so the PC plugins see only his words.
        assertEquals("yes", Regex("\\[[^\\]]*\\]").replace(note + "\nyes", " ").trim())
    }

    private fun store(): Pair<TrashStore, File> {
        val dir = Files.createTempDirectory("trash").toFile()
        val ctx = object : ContextWrapper(null) { override fun getFilesDir() = dir }
        return TrashStore(ctx) to dir
    }

    @Test fun trashKeepsTheConversationAndItsSession() {
        val (trash, _) = store()
        val ch = Channel("homework-ab12", "Homework", ModelRoute.OPUS, "android-old", "", 1)
        val msgs = listOf(ChatMessage(role = "user", text = "Explain pointers\nplease"),
            ChatMessage(role = "assistant", text = "A pointer holds an address."))
        val item = trash.add(ch, msgs, channelDeleted = true, now = 1_000)!!
        assertEquals("Explain pointers", item.title)
        val back = trash.list().single()
        assertEquals(item.id, back.id)
        assertEquals(ch, back.channel)                       // id, name, model and session come back
        assertTrue(back.channelDeleted)
        assertEquals(msgs.map { it.text }, trash.messages(item.id).map { it.text })
        // An empty chat (or one with only app notes) isn't worth a trash entry.
        assertNull(trash.add(ch, listOf(ChatMessage(role = "note", text = "compacted")), false))
        assertEquals(1, trash.list().size)
    }

    @Test fun theDmGoesToTheTrashToo() {
        val (trash, _) = store()
        val dm = Channel(Channel.DM, "ProxAI", null, "android-dm", "carry", 0)
        trash.add(dm, listOf(ChatMessage(role = "notice", text = "Gym", title = "☀️ Today")), channelDeleted = false)
        val back = trash.list().single()
        assertTrue(back.channel.isDm)
        assertEquals("android-dm", back.channel.sessionId)
        assertEquals("☀️ Today", back.title)
    }

    @Test fun trashEmptiesItselfAfterTheSetDays() {
        val (trash, _) = store()
        val ch = Channel("a-1234", "A")
        val msgs = listOf(ChatMessage(role = "user", text = "hi"))
        trash.add(ch, msgs, false, now = 0)
        trash.add(ch, msgs, false, now = 40 * day)
        assertEquals(0, trash.purge(0, now = 45 * day))     // 0 = keep forever
        assertEquals(1, trash.purge(30, now = 45 * day))
        assertEquals(40 * day, trash.list().single().deletedAt)
    }

    @Test fun longTitlesAreCut() {
        val t = TrashStore.title(listOf(ChatMessage(role = "user", text = "word ".repeat(30))))
        assertTrue(t.endsWith("…") && t.length <= 61)
        assertEquals("Conversation", TrashStore.title(emptyList()))
    }
}
