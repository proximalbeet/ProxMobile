package com.proxai.hermes.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Phase 2, Chat as the DM (2026-10-29): the PC's notices become ProxAI messages in the chat.
 * C1: kinds in [CHAT_KINDS] (brief, nudges, end of day, scout ideas...) land in the chat as soon as
 * they arrive. C2: any other notice lands there when its notification is tapped. C3: the chat kinds
 * get a Reply button. Pure rules, unit-tested (ChatDmTest).
 */
object ChatNotices {
    /** Things to talk about: they go into the chat on arrival and their notifications get Reply. */
    val CHAT_KINDS = setOf("rhythm", "scout", "pipeline", "roadmap", "schedule")
    private const val MAX_EACH = 1500
    private const val MAX_ALL = 4000
    /** A notice's decision or next step (NOTICE-QUOTE): a line, not a body. */
    private const val MAX_STEP = 300
    /** A no_quote notice's title (round 4): its only text, so it's capped too. */
    private const val MAX_TITLE = 200

    /** The PC's "no_quote", failing closed (round 4): absent (null here) or JSON false quotes the notice; any other
     *  value present (true, 1, "yes", "false" as text, an object, JSON null) doesn't. Unit-tested. */
    fun noQuoteOf(v: Any?): Boolean = v != null && v != false

    fun key(n: PcNotice) = "notice:${n.id}"
    fun key(a: PcAlert) = "alert:${a.id}"

    fun label(kind: String?): String = when (kind) {
        "rhythm" -> "Daily rhythm"
        "scout" -> "Scout"
        "pipeline" -> "Pipeline"
        "roadmap" -> "Roadmap"
        "schedule" -> "Scheduled job"
        "app" -> "App builder"
        "build" -> "PC build"
        "wiki" -> "Wiki"
        "study" -> "Study Coach"
        "calendar" -> "Calendar"
        "reminder" -> "Reminder"
        "alert" -> "PC alert"
        else -> "PC"
    }

    /** A PC time ("2026-10-28T09:35:38", the PC's local time) as epoch ms; [fallback] if unreadable. */
    fun timeOf(iso: String, zone: ZoneId = ZoneId.systemDefault(), fallback: Long = System.currentTimeMillis()): Long =
        try { LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli() } catch (e: DateTimeParseException) { fallback }

    /**
     * The notices that arrived since Nathan's last message, as one note for his next message, so
     * "done 1" or "tell me more about idea 2" makes sense to ProxAI (they aren't in the Hermes session).
     * One [...] block with no brackets inside: the PC's plugins drop [...] notes before reading his
     * words for a yes or a short reply, and a stray "]" would leak notice text into them.
     *
     * NOTICE-QUOTE (0.9.15), Nathan's data line of 2026-11-16: names, stages, next steps, due dates, decisions and
     * lead email text may reach the model; a signed client's record and their customers' data never do. Each notice
     * gives its kind and title (names kept), its decision and next step when it has them, then its body. A notice the
     * PC marked no_quote gives only its kind and title.
     */
    fun note(messages: List<ChatMessage>, zone: ZoneId = ZoneId.systemDefault(), max: Int = 5): String {
        val fresh = messages.takeLastWhile { it.role != "user" }.filter { it.role == "notice" }.takeLast(max)
        if (fresh.isEmpty()) return ""
        val body = fresh.mapIndexed { i, m ->
            val title = m.title?.takeIf { it.isNotBlank() }
            if (m.noQuote) return@mapIndexed "(${i + 1}) ${label(m.kind)}" + (title?.let { ": ${it.trim().take(MAX_TITLE)}" } ?: "")
            val head = listOfNotNull(title, ChatTimes.clock(m.time, zone)).joinToString(", ")
            val steps = listOfNotNull(m.decision?.takeIf { it.isNotBlank() }?.let { "decision: ${it.trim().take(MAX_STEP)}" },
                m.next?.takeIf { it.isNotBlank() }?.let { "next step: ${it.trim().take(MAX_STEP)}" })
            val withSteps = if (steps.isEmpty()) head else "$head (${steps.joinToString("; ")})"
            "(${i + 1}) ${label(m.kind)}: $withSteps: ${m.text.trim().take(MAX_EACH)}"
        }.joinToString("\n").take(MAX_ALL)
        // B21 (0.9.9): plainly context, not his words; his own message comes after the notes.
        return "[Context, not his words: notices ProxAI posted to Nathan's chat since his last message. His message " +
            "below may answer one of them:\n" + unbracket(body) + "]"
    }

    fun unbracket(s: String) = s.replace('[', '(').replace(']', ')')
}

/** C10: message times and day dividers. */
object ChatTimes {
    private val CLOCK = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)
    private val DAY_YEAR = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    /** "3:42 PM"; "" for messages saved without a time. */
    fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        if (ms <= 0) "" else CLOCK.format(Instant.ofEpochMilli(ms).atZone(zone))

    fun date(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    /** "Today", "Yesterday", "Mon, Oct 27" (with the year when it isn't this year). */
    fun day(ms: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val d = date(ms, zone)
        val today = date(now, zone)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.year == today.year -> DAY.format(d)
            else -> DAY_YEAR.format(d)
        }
    }

    /** Indexes of the messages that start a new day (a divider goes above each). Time 0 never starts one. */
    fun dayStarts(messages: List<ChatMessage>, zone: ZoneId = ZoneId.systemDefault()): Set<Int> {
        val out = mutableSetOf<Int>()
        var prev: LocalDate? = null
        messages.forEachIndexed { i, m ->
            if (m.time <= 0) return@forEachIndexed
            val d = date(m.time, zone)
            if (d != prev) out += i
            prev = d
        }
        return out
    }
}
