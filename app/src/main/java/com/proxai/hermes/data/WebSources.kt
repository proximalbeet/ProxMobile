package com.proxai.hermes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Phase 2, C9 (2026-10-29): the web sources in a reply, each labelled
 * - Human-verified: its site is on Nathan's trusted list (he marks sites himself, in the chat or Settings);
 * - AI-checked: ProxAI says it confirmed the claim in a second, independent source ("✓checked" after the link);
 * - Unknown: neither.
 * Pure rules, unit-tested (SourcesTest).
 */
object WebSources {
    enum class Trust(val badge: String, val label: String) {
        HUMAN("✅", "Human-verified"), AI("🤖", "AI-checked"), UNKNOWN("❔", "Unknown")
    }

    data class Source(val url: String, val domain: String, val title: String?, val aiChecked: Boolean)

    private const val CHECKED = """(?:\s*(?:✓\s*checked|\(checked\)|✔\s*checked))"""
    private val MD_LINK = Regex("""\[([^\]\n]{1,200})\]\((https?://[^\s)]+)\)($CHECKED)?""", RegexOption.IGNORE_CASE)
    private val BARE = Regex("""(?<![(\[<])\bhttps?://[^\s)\]>"'`*]+($CHECKED)?""", RegexOption.IGNORE_CASE)

    /** The sources in [text], in order, once each (a link marked checked anywhere counts as checked). */
    fun find(text: String): List<Source> {
        val out = linkedMapOf<String, Source>()
        fun add(url: String, title: String?, checked: Boolean) {
            val clean = url.trimEnd('.', ',', ';', ':', '!', '?')
            val d = domain(clean) ?: return
            val prev = out[clean]
            out[clean] = Source(clean, d, prev?.title ?: title, checked || prev?.aiChecked == true)
        }
        data class Hit(val at: Int, val url: String, val title: String?, val checked: Boolean)
        val md = MD_LINK.findAll(text).map { m -> m.range to Hit(m.range.first, m.groupValues[2], m.groupValues[1].trim(),
            m.groupValues[3].isNotEmpty()) }.toList()
        val bare = BARE.findAll(text).filter { m -> md.none { (r, _) -> m.range.first in r } }.map { m ->
            Hit(m.range.first, m.value.replace(Regex(CHECKED + "$", RegexOption.IGNORE_CASE), ""), null, m.groupValues[1].isNotEmpty())
        }
        (md.map { it.second } + bare).sortedBy { it.at }.forEach { add(it.url, it.title, it.checked) }
        return out.values.toList()
    }

    /** "https://www.nature.com/articles/x" -> "nature.com"; null for anything that isn't a web address. */
    fun domain(url: String): String? {
        val host = Regex("""^https?://([^/:?#\s]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)?.lowercase() ?: return null
        if (!host.contains('.')) return null
        return host.removePrefix("www.")
    }

    /** A site on the list counts for its subdomains too (trusting nih.gov covers ncbi.nlm.nih.gov). */
    fun trust(s: Source, trusted: Set<String>): Trust = when {
        trusted.any { t -> s.domain == t || s.domain.endsWith(".$t") } -> Trust.HUMAN
        s.aiChecked -> Trust.AI
        else -> Trust.UNKNOWN
    }

    fun cleanDomain(input: String): String? =
        domain(if (input.trim().startsWith("http", ignoreCase = true)) input.trim() else "https://" + input.trim())
}

/** Nathan's trusted sites (C9), shared by every bubble and Settings. */
class TrustedSites(private val settings: Settings) {
    private val _sites = MutableStateFlow(settings.trustedSites)
    val sites: StateFlow<Set<String>> = _sites

    fun set(domain: String, trusted: Boolean) {
        val d = WebSources.cleanDomain(domain) ?: return
        val next = if (trusted) _sites.value + d else _sites.value - d
        settings.trustedSites = next
        _sites.value = next
    }
}
