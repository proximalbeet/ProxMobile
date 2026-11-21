package com.proxai.hermes

import com.proxai.hermes.data.ChatMessage
import com.proxai.hermes.data.ChatNotices
import com.proxai.hermes.data.ChatTimes
import com.proxai.hermes.data.NoticeStore
import com.proxai.hermes.data.PcAlert
import com.proxai.hermes.data.PcNotice
import com.proxai.hermes.data.Prompts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** Phase 2 (2026-10-29): notices as chat messages (C1-C3) and message times (C10). */
class ChatDmTest {
    private val zone = ZoneId.of("America/Detroit")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun notice(title: String, text: String, time: String, kind: String = "rhythm") =
        ChatMessage(role = "notice", title = title, text = text, kind = kind, noticeKey = "notice:$title", time = at(time))

    /** The regex every PC plugin uses to drop [...] notes before reading Nathan's words. */
    private val pcNotes = Regex("\\[[^\\]]*\\]")

    @Test fun onlyNoticesSinceHisLastMessageGoAlong() {
        val msgs = listOf(
            notice("☀️ Today", "old brief", "2026-10-28T09:35:00"),
            ChatMessage(role = "user", text = "thanks"),
            ChatMessage(role = "assistant", text = "anytime"),
            notice("☀️ Today", "1. Gym\n2. Call insurance", "2026-10-29T09:35:00"),
            notice("💡 3 app ideas", "Idea 1: Quote Check", "2026-10-29T10:02:00", kind = "scout"),
        )
        val note = ChatNotices.note(msgs, zone)
        assertTrue(note.startsWith("[Context, not his words"))
        assertTrue(note.endsWith("]"))
        assertTrue("(1) Daily rhythm: ☀️ Today, 9:35 AM: 1. Gym" in note)
        assertTrue("(2) Scout: 💡 3 app ideas, 10:02 AM: Idea 1: Quote Check" in note)
        assertFalse("old brief" in note)
        assertEquals("", ChatNotices.note(msgs + ChatMessage(role = "user", text = "done 1"), zone))
        assertEquals("", ChatNotices.note(emptyList(), zone))
    }

    @Test fun pcPluginsStillSeeOnlyHisWords() {
        // A "]" in a notice used to be able to end the note early and leak text into "yes".
        val msgs = listOf(notice("🌙 End of day", "[x] Gym ] [ ] Call insurance", "2026-10-29T21:00:00"))
        val wire = ChatNotices.note(msgs, zone) + "\n" +
            Prompts.turnNote(false, java.time.ZonedDateTime.now(zone)) + "\n" + "done 1 3"
        assertEquals("done 1 3", pcNotes.replace(wire, " ").trim())
        assertEquals(1, Regex("\\[").findAll(ChatNotices.note(msgs, zone)).count())
    }

    // ---- NOTICE-QUOTE (0.9.15): what of a notice reaches the model ----

    private fun pipelineNotice(title: String, text: String, noQuote: Boolean = false, decision: String? = null,
                               next: String? = null) =
        ChatMessage(role = "notice", title = title, text = text, kind = "pipeline", noticeKey = "notice:$title",
            time = at("2026-11-16T09:35:00"), noQuote = noQuote, decision = decision, next = next)

    @Test fun pipelineNoticeWithALeadNameAndBodyIsQuoted() {
        val note = ChatNotices.note(listOf(pipelineNotice("Lead: Jane Roe, Roe Roofing",
            "Jane asked for a quote on a 30-square tear-off. Her email: can you come Thursday?",
            decision = "send the quote", next = "call Jane Fri 11-20")), zone)
        assertTrue(note, "(1) Pipeline: Lead: Jane Roe, Roe Roofing, 9:35 AM (decision: send the quote; " +
            "next step: call Jane Fri 11-20): Jane asked for a quote on a 30-square tear-off. Her email: can you come Thursday?" in note)
        // no decision or next step: as before
        val plain = ChatNotices.note(listOf(pipelineNotice("Lead: Sam Doe", "wants siding")), zone)
        assertTrue(plain, "(1) Pipeline: Lead: Sam Doe, 9:35 AM: wants siding" in plain)
    }

    @Test fun aNoQuoteNoticeGivesOnlyItsKindAndTitle() {
        val note = ChatNotices.note(listOf(
            pipelineNotice("Lead: Sam Doe", "wants siding"),
            pipelineNotice("CL1 signed: Acme Roofing", "Customer list: Bob Smith, 12 Elm St, 555-0100", noQuote = true,
                decision = "onboard", next = "set up booking"),
        ), zone)
        assertTrue(note, "(1) Pipeline: Lead: Sam Doe, 9:35 AM: wants siding" in note)
        assertTrue(note, note.endsWith("\n(2) Pipeline: CL1 signed: Acme Roofing]"))   // no time, steps or body
        for (secret in listOf("Bob Smith", "Elm St", "555-0100", "onboard", "set up booking")) assertFalse(secret, secret in note)
        // no title: the kind alone
        assertTrue(ChatNotices.note(listOf(pipelineNotice("", "record", noQuote = true)), zone).contains("(1) Pipeline]"))
    }

    @Test fun capsAreUnchanged() {
        val one = ChatNotices.note(listOf(notice("t", "§".repeat(3000), "2026-10-29T09:35:00")), zone)
        assertEquals(1500, one.count { it == '§' })                           // each body: 1500
        val five = ChatNotices.note((1..5).map { notice("t$it", "§".repeat(1500), "2026-10-29T09:3${it}:00") }, zone)
        assertEquals(4000, five.substringAfter(":\n").dropLast(1).length)     // all together: 4000
        val longStep = ChatNotices.note(listOf(pipelineNotice("Lead", "x", decision = "¤".repeat(1000))), zone)
        assertEquals(300, longStep.count { it == '¤' })                        // a decision is a line
        val seven = ChatNotices.note((1..7).map { notice("t$it", "x", "2026-10-29T09:3${it}:00") }, zone)
        assertTrue("(5) Daily rhythm: t7," in seven)                          // still the last 5 notices
        assertFalse("(6)" in seven); assertFalse("t2," in seven)
    }

    @Test fun unbracketStillApplies() {
        val msgs = listOf(
            pipelineNotice("Lead [hot] ]", "[x] quote ] sent", decision = "go ]", next = "[call]"),
            pipelineNotice("CL2 ] signed [", "record", noQuote = true),
        )
        val note = ChatNotices.note(msgs, zone)
        assertEquals(1, note.count { it == '[' })
        assertEquals(1, note.count { it == ']' })
        val wire = note + "\n" + Prompts.turnNote(false, java.time.ZonedDateTime.now(zone)) + "\n" + "yes 1"
        assertEquals("yes 1", pcNotes.replace(wire, " ").trim())
    }

    @Test fun noQuoteIsReadFromThePcAndKept() {
        val list = PcNotice.parseList("""{"notices":[
            {"id":1,"time":"2026-11-16T09:35:00","kind":"pipeline","title":"Lead: Sam","text":"b","decision":"quote","next":"call"},
            {"id":2,"time":"2026-11-16T09:36:00","kind":"pipeline","title":"CL1","text":"record","no_quote":true},
            {"id":3,"time":"2026-11-16T09:37:00","kind":"rhythm","title":"Today","text":"x","decision":null,"no_quote":false}]}""")
        assertEquals(listOf(false, true, false), list.map { it.noQuote })      // absent means quote, as today
        assertEquals(listOf("quote", "", ""), list.map { it.decision })
        assertEquals(listOf("call", "", ""), list.map { it.next })
        val saved = com.proxai.hermes.data.messagesFromJson(com.proxai.hermes.data.messagesToJson(listOf(
            pipelineNotice("CL1", "record", noQuote = true), pipelineNotice("Lead", "b", decision = "quote", next = "call"))))
        assertEquals(listOf(true, false), saved.map { it.noQuote })            // a restart doesn't start quoting it
        assertEquals(listOf(null, "quote"), saved.map { it.decision })
        assertEquals(listOf(null, "call"), saved.map { it.next })
    }

    // Security #51: tier "thread" is chat only (PcStats pull posts no notification for it).
    @Test fun tierThreadIsChatOnly() {
        val list = PcNotice.parseList("""{"notices":[
            {"id":1,"time":"t","kind":"pipeline","title":"a","text":"b","tier":"thread"},
            {"id":2,"time":"t","kind":"pipeline","title":"a","text":"b","tier":"alert"},
            {"id":3,"time":"t","kind":"pipeline","title":"a","text":"b"},
            {"id":4,"time":"t","kind":"pipeline","title":"a","text":"b","tier":["thread"]}]}""")
        assertEquals(listOf(true, false, false, false), list.map { it.chatOnly })
    }

    @Test fun whichKindsComeIntoTheChat() {
        for (k in listOf("rhythm", "scout", "pipeline", "roadmap", "schedule")) assertTrue(k, k in ChatNotices.CHAT_KINDS)
        for (k in listOf("app", "build", "wiki", "study")) assertFalse(k, k in ChatNotices.CHAT_KINDS)
        assertEquals("notice:46", ChatNotices.key(PcNotice(46, "2026-10-26T09:35:38", "rhythm", "☀️ Today", "…")))
        assertEquals("Study Coach", ChatNotices.label("study"))
        assertEquals("PC", ChatNotices.label("something-new"))
    }

    // ---- Round 4 (0.9.15): no_quote fails closed; a notice by intent is only a pointer ----

    private fun parsedNoQuote(raw: String?): Boolean = PcNotice.parseList("""{"notices":[{"id":1,"time":"2026-11-16T09:35:00",
        "kind":"pipeline","title":"t","text":"b"${if (raw == null) "" else ",\"no_quote\":$raw"}}]}""").single().noQuote

    @Test fun noQuoteFailsClosed() {
        assertFalse("absent", parsedNoQuote(null))
        assertFalse("false", parsedNoQuote("false"))
        for (raw in listOf("true", "1", "0", "\"yes\"", "\"false\"", "\"\"", "{}", "{\"x\":false}", "[]", "null"))
            assertTrue(raw, parsedNoQuote(raw))
        assertFalse(ChatNotices.noQuoteOf(null)); assertFalse(ChatNotices.noQuoteOf(false))
        assertTrue(ChatNotices.noQuoteOf(true)); assertTrue(ChatNotices.noQuoteOf("no"))
    }

    @Test fun aNoQuoteTitleIsCappedToo() {
        val note = ChatNotices.note(listOf(pipelineNotice("¤".repeat(1000), "record", noQuote = true)), zone)
        assertEquals(200, note.count { it == '¤' })
        val quoted = ChatNotices.note(listOf(pipelineNotice("Lead: " + "n".repeat(250), "b")), zone)
        assertTrue("n".repeat(250) in quoted)                                 // a quoted notice's title: as before
    }

    @Test fun savedMessagesFailClosedToo() {
        val arr = com.proxai.hermes.data.messagesToJson(listOf(pipelineNotice("CL1", "record")))
        arr.getJSONObject(0).put("noQuote", "yes")
        assertTrue(com.proxai.hermes.data.messagesFromJson(arr).single().noQuote)
    }

    private fun store(): Pair<NoticeStore, () -> String?> {
        var saved: String? = null
        return NoticeStore({ saved }, { saved = it }) to { saved }
    }

    @Test fun aNoticeByIntentIsOnlyAPointer() {
        val (s, _) = store()
        s.put(listOf(NoticeStore.entry(PcNotice(7, "2026-11-16T09:35:00", "pipeline", "CL1 signed", "record",
            noQuote = true)), NoticeStore.entry(PcAlert(3, "2026-11-16T09:40:00", "CRIT", "gpu", "GPU 95 C"))))
        // the stored copy, whatever the intent's extras said (they are never read)
        val n = s.get("notice:7")!!
        assertEquals("CL1 signed", n.title); assertEquals("record", n.text); assertTrue(n.noQuote)
        assertEquals("pipeline", n.kind); assertEquals("2026-11-16T09:35:00", n.time)
        val a = s.get("alert:3")!!
        assertEquals("🔥 PC critical", a.title); assertEquals("alert", a.kind); assertEquals("GPU 95 C", a.text)
        // made-up or not-yet-fetched keys are dropped: no injected notice, no pre-claimed id
        for (k in listOf("notice:8", "notice:999", "alert:4", "reminder:1:0", "notice:7 ", "", null)) assertEquals(k, null, s.get(k))
    }

    @Test fun theStoreSurvivesARestartAndKeepsTheNewest() {
        val (s, saved) = store()
        s.put((1..120).map { NoticeStore.entry(PcNotice(it, "2026-11-16T09:00:00", "rhythm", "t$it", "x$it")) })
        val again = NoticeStore({ saved() }, { })                                       // a new process
        assertEquals(null, again.get("notice:20"))                                     // the oldest went first
        assertEquals("t21", again.get("notice:21")!!.title)
        assertEquals("t120", again.get("notice:120")!!.title)
        s.put(listOf(NoticeStore.entry(PcNotice(120, "2026-11-16T10:00:00", "rhythm", "new", "y"))))   // same key: replaced
        assertEquals("new", s.get("notice:120")!!.title)
        assertEquals(NoticeStore.MAX, NoticeStore.fromJson(saved()!!).size)
        assertEquals(null, NoticeStore({ "not json" }, { }).get("notice:1"))            // a broken store drops, never crashes
        assertTrue(NoticeStore.fromJson("""[{"key":"notice:1","noQuote":"yes"}]""").single().noQuote)   // fails closed
    }

    @Test fun pcTimesAreReadAsLocalTime() {
        assertEquals(at("2026-10-29T09:35:38"), ChatNotices.timeOf("2026-10-29T09:35:38", zone))
        assertEquals(42L, ChatNotices.timeOf("not a time", zone, fallback = 42L))
    }

    @Test fun clockAndDayLabels() {
        val now = at("2026-10-29T15:00:00")
        assertEquals("3:42 PM", ChatTimes.clock(at("2026-10-29T15:42:00"), zone))
        assertEquals("", ChatTimes.clock(0, zone))
        assertEquals("Today", ChatTimes.day(at("2026-10-29T00:05:00"), now, zone))
        assertEquals("Yesterday", ChatTimes.day(at("2026-10-28T23:59:00"), now, zone))
        assertEquals("Mon, Oct 26", ChatTimes.day(at("2026-10-26T12:00:00"), now, zone))
        assertEquals("Wed, Dec 31, 2025", ChatTimes.day(at("2025-12-31T12:00:00"), now, zone))
    }

    @Test fun dividersGoAboveTheFirstMessageOfEachDay() {
        val msgs = listOf(
            ChatMessage(role = "user", text = "a", time = at("2026-10-28T10:00:00")),
            ChatMessage(role = "assistant", text = "b", time = at("2026-10-28T10:01:00")),
            ChatMessage(role = "note", text = "old, no time", time = 0),
            ChatMessage(role = "user", text = "c", time = at("2026-10-29T08:00:00")),
            ChatMessage(role = "assistant", text = "d", time = at("2026-10-29T08:01:00")),
        )
        assertEquals(setOf(0, 3), ChatTimes.dayStarts(msgs, zone))
    }
}
