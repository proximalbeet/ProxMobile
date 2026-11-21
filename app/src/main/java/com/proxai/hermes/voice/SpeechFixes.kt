package com.proxai.hermes.voice

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** "heard => meant" phrase fixes for what the recognizer heard (Settings → Speech). */
object SpeechFixes {
    data class Rule(val heard: Regex, val meant: String)

    fun parse(rules: String): List<Rule> = rules.lines().mapNotNull { line ->
        val parts = line.split("=>", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val heard = parts[0].trim()
        val meant = parts[1].trim()
        if (heard.isEmpty()) return@mapNotNull null
        val words = heard.split(Regex("\\s+")).joinToString("\\s+") { Regex.escape(it) }
        Rule(Regex("(?<![\\p{L}\\p{N}])$words(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE), meant)
    }

    fun apply(text: String, rules: List<Rule>): String =
        rules.fold(text) { t, r -> r.heard.replace(t) { r.meant } } // lambda form: "$" stays literal

    /** Vocabulary setting -> list for the recognizer's biasing hint. */
    fun vocabulary(setting: String): ArrayList<String> =
        ArrayList(setting.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(100))
}

/** Last speech-recognizer events, shown under Settings → Speech so a failure can be read later. */
object SpeechLog {
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val lines = ArrayDeque<String>()

    @Synchronized fun add(msg: String) {
        lines.addLast("${fmt.format(Date())}  $msg")
        while (lines.size > 40) lines.removeFirst()
    }

    @Synchronized fun all(): List<String> = lines.toList().asReversed()
}
