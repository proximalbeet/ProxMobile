package com.proxai.hermes.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.proxai.hermes.notify.ReminderReceiver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

data class Reminder(val id: Int, val at: Long, val text: String)

/**
 * Phone reminders. Hermes adds `[[remind: 2026-10-22T17:30 | text]]` to a reply (see Prompts);
 * the app strips the tag, stores the reminder and sets an alarm that posts a notification.
 * Everything stays on the phone, so it works without Firebase or a Hermes change.
 */
class ReminderStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("proxai_reminders", Context.MODE_PRIVATE)
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<Reminder>> = _items

    fun add(at: Long, text: String): Reminder {
        val r = Reminder(nextId(), at, text.trim().take(300))
        save(_items.value + r)
        schedule(r)
        return r
    }

    fun remove(id: Int) {
        alarms()?.cancel(pending(Reminder(id, 0, "")))
        save(_items.value.filterNot { it.id == id })
    }

    fun get(id: Int): Reminder? = _items.value.firstOrNull { it.id == id }

    /** After a reboot or app update alarms are gone; set them again. */
    fun rescheduleAll() {
        _items.value.forEach { schedule(it) }
    }

    fun snooze(id: Int, minutes: Int) {
        val r = get(id) ?: return
        val moved = r.copy(at = System.currentTimeMillis() + minutes * 60_000L)
        save(_items.value.map { if (it.id == id) moved else it })
        schedule(moved)
    }

    private fun schedule(r: Reminder) {
        val am = alarms() ?: return
        val at = maxOf(r.at, System.currentTimeMillis() + 1_000)
        val pi = pending(r)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
    }

    private fun pending(r: Reminder): PendingIntent = PendingIntent.getBroadcast(
        context, r.id,
        Intent(context, ReminderReceiver::class.java).setAction(ReminderReceiver.ACTION_FIRE)
            .putExtra(ReminderReceiver.EXTRA_ID, r.id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun alarms() = context.getSystemService(AlarmManager::class.java)

    private fun nextId(): Int {
        val id = prefs.getInt("next_id", 1)
        prefs.edit().putInt("next_id", if (id >= 1_000_000) 1 else id + 1).apply()
        return id
    }

    private fun save(list: List<Reminder>) {
        val sorted = list.sortedBy { it.at }
        _items.value = sorted
        val arr = JSONArray()
        sorted.forEach { arr.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        prefs.edit().putString("items", arr.toString()).apply()
    }

    private fun load(): List<Reminder> = runCatching {
        val arr = JSONArray(prefs.getString("items", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Reminder(o.getInt("id"), o.getLong("at"), o.getString("text"))
        }
    }.getOrDefault(emptyList())

    companion object {
        /** `[[remind: 2026-10-22T17:30 | Call mom]]` (also "reminder", a space instead of T, seconds). */
        private val TAG = Regex(
            "\\[\\[\\s*remind(?:er)?\\s*:?\\s*(\\d{4}-\\d{2}-\\d{2}[T ]\\d{1,2}:\\d{2}(?::\\d{2})?)\\s*\\|\\s*([^\\]]+?)\\s*\\]\\]",
            RegexOption.IGNORE_CASE)

        /** Did Nathan's message ask for a reminder? Tags in replies to anything else are ignored. */
        private val ASKED = Regex(
            "\\b(remind(er|ers)?|alarm|alert me|ping me|notify me|nudge me|wake me|don'?t let me forget|" +
                "make sure i (remember|don'?t forget))\\b", RegexOption.IGNORE_CASE)

        fun userAsked(prompt: String): Boolean = ASKED.containsMatchIn(prompt.replace('’', '\''))

        data class Parsed(val at: Long, val text: String)

        private fun same(a: String, b: String) =
            a.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim() == b.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

        /**
         * Which reminder tags to actually set. ProxAI may set reminders on its own, but on 10-23 it set
         * them for times already past and repeated them every reply. Rules: no times already past (unasked:
         * at least a minute ahead); no repeat of a reminder that's already set (or twice in one reply); and when
         * Nathan didn't ask for a reminder, at most one per reply.
         */
        fun keep(parsed: List<Parsed>, asked: Boolean, existing: List<Reminder>, now: Long): List<Parsed> {
            val out = mutableListOf<Parsed>()
            for (p in parsed) {
                // "remind me in a minute" can land a few seconds late; an unasked one must be ahead.
                if (p.at < now + if (asked) -60_000 else 60_000) continue
                if (existing.any { it.at > now && same(it.text, p.text) }) continue
                if (out.any { same(it.text, p.text) }) continue
                out += p
            }
            return if (asked) out else out.take(1)
        }

        /** Reminder tags in [reply], with local times turned into epoch millis. */
        fun parse(reply: String, zone: ZoneId = ZoneId.systemDefault()): List<Parsed> =
            TAG.findAll(reply).mapNotNull { m ->
                val raw = m.groupValues[1].replace(' ', 'T')
                val time = try {
                    LocalDateTime.parse(if (raw.length == 15) raw.replaceRange(11, 11, "0") else raw)
                } catch (e: DateTimeParseException) {
                    null
                } ?: return@mapNotNull null
                Parsed(time.atZone(zone).toInstant().toEpochMilli(), m.groupValues[2].trim())
            }.toList()

        /** [reply] without reminder tags (what's shown and spoken). */
        fun strip(reply: String): String =
            reply.replace(TAG, "").replace(Regex("[ \\t]+\\n"), "\n").replace(Regex("\\n{3,}"), "\n\n").trim()

        private val SHOW = DateTimeFormatter.ofPattern("EEE MMM d, h:mm a")
        fun format(at: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            SHOW.format(java.time.Instant.ofEpochMilli(at).atZone(zone))
    }
}
