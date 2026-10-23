package com.proxai.hermes

import com.proxai.hermes.data.PcAlert
import com.proxai.hermes.data.PcNotice
import com.proxai.hermes.data.PcStatsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PcStatsTest {
    // Shape of GET /apps/v1/pc/alerts (server/proxai_apps_server.py, alerts.jsonl lines).
    private val json = """{"alerts": [
      {"id": 7, "time": "2026-10-22T21:17:35", "level": "ALERT", "kind": "cpu_t", "msg": "CPU package 86C (warn 85C), CPU use 47%"},
      {"id": 8, "time": "2026-10-22T22:40:00", "level": "CRIT", "kind": "protect", "msg": "PROTECT unloaded local model (qwen3.5:35b-a3b) because GPU reached 83C"}]}"""
    private val now = LocalDateTime.parse("2026-10-22T22:45:00")

    @Test fun parsesAlerts() {
        val a = PcAlert.parseList(json)
        assertEquals(listOf(7, 8), a.map { it.id })
        assertTrue(a[1].critical)
    }

    @Test fun firstCheckOnlyShowsTheLast30Minutes() {
        val (show, last) = PcAlert.toNotify(PcAlert.parseList(json), 0, now)
        assertEquals(listOf(8), show.map { it.id })
        assertEquals(8, last)
    }

    @Test fun laterChecksShowEverythingNew() {
        val (show, last) = PcAlert.toNotify(PcAlert.parseList(json), 6, now)
        assertEquals(listOf(7, 8), show.map { it.id })   // late is fine: nothing gets lost
        assertEquals(8, last)
        val (none, same) = PcAlert.toNotify(emptyList(), 8, now)
        assertEquals(emptyList<PcAlert>(), none)
        assertEquals(8, same)
    }

    @Test fun parsesNoticesAndShowsOnlyNewOnes() {
        val json = """{"notices":[{"id":4,"time":"2026-10-23T14:00:00","kind":"schedule","title":"⏰ check the PC temps",
            "text":"GPU 41 °C"},{"id":5,"time":"2026-10-23T14:20:00","kind":"app","title":"📱 Pong is ready","text":"Install it"}]}"""
        val n = PcNotice.parseList(json)
        assertEquals(listOf("⏰ check the PC temps", "📱 Pong is ready"), n.map { it.title })
        val now = LocalDateTime.of(2026, 10, 23, 14, 30)
        // First check ever: only the last 30 minutes, so installing doesn't replay old notices.
        val (first, last) = PcNotice.toNotify(n, 0, now)
        assertEquals(listOf(5), first.map { it.id })
        assertEquals(5, last)
        val (later, last2) = PcNotice.toNotify(n, 4, now)
        assertEquals(listOf(5), later.map { it.id })
        assertEquals(5, last2)
        assertTrue(PcNotice.parseList("""{"notices":[]}""").isEmpty())
    }

    @Test fun onlyProxAiAppsWithThePinnedKeyGetStats() {
        assertNull(PcStatsProvider.refuse("com.proxai.apps.pc_monitor", "AB12", "ab12"))
        assertTrue(PcStatsProvider.refuse("com.evil.app", "ab12", "ab12")!!.contains("Only ProxAI-built"))
        assertTrue(PcStatsProvider.refuse(null, "ab12", "ab12")!!.contains("Only ProxAI-built"))
        assertTrue(PcStatsProvider.refuse("com.proxai.apps.x", "ab12", null)!!.contains("Install a ProxAI app"))
        assertTrue(PcStatsProvider.refuse("com.proxai.apps.x", "cd34", "ab12")!!.contains("isn't signed"))
        assertTrue(PcStatsProvider.refuse("com.proxai.apps.x", null, "ab12")!!.contains("isn't signed"))
    }
}
