package com.proxai.hermes

import com.proxai.hermes.data.Prompts
import com.proxai.hermes.data.Reminder
import com.proxai.hermes.data.ReminderStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/** B6: ProxAI can move and cancel reminders by id. */
class RemindersEditTest {
    private val zone = ZoneId.of("America/New_York")
    private fun at(s: String) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test fun movesAndCancelsAreParsedAndHidden() {
        val reply = "Moved it to 6 and dropped the other one.\n[[remind-move: #3 | 2026-10-28T18:00]]\n[[remind-cancel: 5]]\n[[reminder-cancel:#5]]"
        assertEquals(listOf(3 to at("2026-10-28T18:00")), ReminderStore.parseMoves(reply, zone))
        assertEquals(listOf(5), ReminderStore.parseCancels(reply))
        assertEquals("Moved it to 6 and dropped the other one.", ReminderStore.strip(reply))
        assertTrue(ReminderStore.parse(reply, zone).isEmpty())   // not mistaken for new reminders
    }

    @Test fun noteListsUpcomingRemindersWithIds() {
        val now = at("2026-10-28T12:00")
        val items = listOf(Reminder(3, at("2026-10-28T17:00"), "Call mom"), Reminder(4, at("2026-10-28T09:00"), "Past"))
        val list = ReminderStore.listForNote(items, now, zone)
        assertEquals("#3 2026-10-28T17:00 Call mom", list)
        val note = Prompts.turnNote(false, java.time.ZonedDateTime.now(zone), list)
        assertTrue(note.endsWith("; Reminders set: #3 2026-10-28T17:00 Call mom]"))
        assertFalse(Prompts.turnNote(false, java.time.ZonedDateTime.now(zone), "").contains("Reminders"))
    }
}
