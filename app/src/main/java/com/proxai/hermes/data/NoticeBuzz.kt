package com.proxai.hermes.data

/**
 * B5 (Nathan, 10-30): what buzzes the phone. On by default: reminders, calendar heads-ups, the brief
 * and nudges, app builds and PC updates. PC temperature alerts stay off (Monitor and Vitals show them).
 * Each group has a switch in Settings; a notice whose switch is off stays quiet but still lands in Chat.
 * Pure rules, unit-tested (NoticeBuzzTest).
 */
enum class Buzz { CALENDAR, BRIEF, UPDATES }

object NoticeBuzz {
    /**
     * The switch a PC notice answers to. Calendar heads-ups are kind "calendar" (rhythm, 10-30);
     * older ones were rhythm notices titled "⏰ <event> in N min".
     */
    fun group(kind: String, title: String): Buzz = when {
        kind == "calendar" || (kind == "rhythm" && title.startsWith("⏰")) -> Buzz.CALENDAR
        kind == "rhythm" -> Buzz.BRIEF
        else -> Buzz.UPDATES
    }

    /** (notices that get a notification, notices that only go to Chat), by the switches in [on]. */
    fun split(notices: List<PcNotice>, on: (Buzz) -> Boolean): Pair<List<PcNotice>, List<PcNotice>> =
        notices.partition { on(group(it.kind, it.title)) }
}
