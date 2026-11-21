package com.proxai.hermes

import com.proxai.hermes.data.WebSources
import com.proxai.hermes.data.WebSources.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 2, C9 (2026-10-29): web sources labelled Human-verified / AI-checked / Unknown. */
class SourcesTest {
    private val reply = """
        Creatine is safe for most adults [NIH review](https://ods.od.nih.gov/factsheets/Creatine/) ✓checked,
        though one blog disagrees: https://www.someblog.io/creatine-myths.
        See also [Mayo Clinic](https://www.mayoclinic.org/drugs-supplements-creatine/art-20347591).
        Same review again: [NIH](https://ods.od.nih.gov/factsheets/Creatine/)
    """.trimIndent()

    @Test fun findsEachSourceOnceInOrder() {
        val s = WebSources.find(reply)
        assertEquals(listOf("ods.od.nih.gov", "someblog.io", "mayoclinic.org"), s.map { it.domain })
        assertEquals("NIH review", s[0].title)
        assertTrue(s[0].aiChecked)
        assertEquals("https://www.someblog.io/creatine-myths", s[1].url)            // trailing "." dropped
        assertNull(s[1].title)
        assertFalse(s[2].aiChecked)
        assertTrue(WebSources.find("no links, just MEDIA:/output/a.png and `code`").isEmpty())
    }

    @Test fun labels() {
        val s = WebSources.find(reply)
        val trusted = setOf("nih.gov", "mayoclinic.org")
        assertEquals(Trust.HUMAN, WebSources.trust(s[0], trusted))                   // subdomain of a trusted site
        assertEquals(Trust.UNKNOWN, WebSources.trust(s[1], trusted))
        assertEquals(Trust.HUMAN, WebSources.trust(s[2], trusted))
        assertEquals(Trust.AI, WebSources.trust(s[0], emptySet()))                   // ✓checked, not on his list
        // "evilnih.gov" isn't nih.gov.
        assertEquals(Trust.UNKNOWN, WebSources.trust(WebSources.find("https://evilnih.gov/x").single(), trusted))
    }

    @Test fun sitesAreCleanedUp() {
        assertEquals("nature.com", WebSources.cleanDomain("https://www.nature.com/articles/x"))
        assertEquals("nih.gov", WebSources.cleanDomain(" NIH.gov "))
        assertNull(WebSources.cleanDomain("not a site"))
        assertNull(WebSources.domain("https://localhost/x"))
    }
}
