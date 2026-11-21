package com.proxai.hermes.data

/**
 * Voice replies arrive in short parts (paragraphs). Each finished part is shown as its own bubble
 * and spoken while the model is still writing the next one (10-23: long replies made you wait for
 * the whole thing before hearing anything).
 */
object VoiceParts {
    /** Paragraphs, split on blank lines outside ``` code blocks; reminder tags removed. */
    fun split(text: String): List<String> {
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        var inCode = false
        fun flush() {
            val part = ReminderStore.strip(cur.toString()).trim()
            if (part.isNotEmpty()) out += part
            cur.clear()
        }
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```")) inCode = !inCode
            if (!inCode && line.isBlank()) { flush(); continue }
            cur.append(line).append('\n')
        }
        flush()
        return out
    }

    /** Parts that are finished: while streaming the last one may still be growing. */
    fun finished(text: String, done: Boolean): List<String> {
        val parts = split(text)
        return if (done) parts else parts.dropLast(1)
    }
}
