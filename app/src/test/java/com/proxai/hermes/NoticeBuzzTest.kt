package com.proxai.hermes

import com.proxai.hermes.data.Buzz
import com.proxai.hermes.data.NoticeBuzz
import com.proxai.hermes.data.PcNotice
import org.junit.Assert.assertEquals
import org.junit.Test

/** B5 (10-30): which switch each PC notice answers to, and what buzzes vs. goes quietly to Chat. */
class NoticeBuzzTest {
    private fun n(id: Int, kind: String, title: String) = PcNotice(id, "2026-10-30T09:00:00", kind, title, "text")

    @Test fun groupsByKind() {
        assertEquals(Buzz.CALENDAR, NoticeBuzz.group("calendar", "⏰ Computer Graphics in 9 min"))
        // Before rhythm tagged them "calendar", heads-ups were rhythm notices starting with the clock.
        assertEquals(Buzz.CALENDAR, NoticeBuzz.group("rhythm", "⏰ Gym in 9 min"))
        assertEquals(Buzz.BRIEF, NoticeBuzz.group("rhythm", "☀️ Today"))
        assertEquals(Buzz.BRIEF, NoticeBuzz.group("rhythm", "🌙 End of day"))
        listOf("app", "build", "study", "wiki", "scout", "pipeline", "roadmap", "schedule", "").forEach {
            assertEquals(it, Buzz.UPDATES, NoticeBuzz.group(it, "Something"))
        }
        // A clock in another kind's title doesn't make it a calendar heads-up.
        assertEquals(Buzz.UPDATES, NoticeBuzz.group("schedule", "⏰ Nightly cleanup done"))
    }

    @Test fun splitFollowsTheSwitches() {
        val all = listOf(n(1, "calendar", "⏰ Class in 9 min"), n(2, "rhythm", "☀️ Today"), n(3, "app", "App ready"))
        val (buzz, quiet) = NoticeBuzz.split(all) { it != Buzz.CALENDAR }
        assertEquals(listOf(2, 3), buzz.map { it.id })
        assertEquals(listOf(1), quiet.map { it.id })
        val (allOn, none) = NoticeBuzz.split(all) { true }
        assertEquals(3, allOn.size)
        assertEquals(0, none.size)
    }
}
