package com.proxai.hermes

import com.proxai.hermes.data.AppInstaller
import com.proxai.hermes.data.PcApp
import com.proxai.hermes.data.PcAppsRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class PcAppsTest {
    // Shape the PC's apps server returns (server/proxai_apps_server.py), from the 10-22 Water Log build.
    private val list = """{"apps": [
      {"id": "water-log", "name": "Water Log", "description": "Tracks daily water intake.", "state": "built",
       "message": "Water Log 1.0 is ready", "updated": "2026-10-22T21:52:40",
       "latest": {"code": 1, "version": "1.0", "size": 631807, "sha256": "ab12", "time": "2026-10-22T21:52:40",
                  "package": "com.proxai.apps.water_log", "features": ["vibrate"],
                  "permissions": ["android.permission.VIBRATE"], "cert_sha256": "cc"}},
      {"id": "habit-log", "name": "Habit Log", "description": "", "state": "writing", "message": "Writing the screens",
       "updated": "2026-10-22T22:01:00"}]}"""

    @Test fun parsesTheServerList() {
        val apps = PcApp.parseList(list)
        assertEquals(listOf("water-log", "habit-log"), apps.map { it.id })
        val w = apps[0]
        assertEquals("com.proxai.apps.water_log", w.latest!!.packageName)
        assertEquals(listOf("android.permission.VIBRATE"), w.latest!!.permissions)
        assertFalse(w.busy)
        assertTrue(apps[1].busy)
        assertNull(apps[1].latest)
    }

    @Test fun mainButton() {
        val w = PcApp.parseList(list)[0]
        assertEquals("install", PcApp.action(w, null))
        assertEquals("open", PcApp.action(w, 1))
        val newer = w.copy(latest = w.latest!!.copy(code = 2))
        assertEquals("update", PcApp.action(newer, 1))
        assertEquals("none", PcApp.action(PcApp.parseList(list)[1], null))
    }

    @Test fun buildsSkipMissingFiles() {
        val b = PcApp.parseBuilds("""{"builds": [{"code": 1, "version": "1.0", "available": false},
            {"code": 2, "version": "1.1", "available": true, "note": "Roll back to build 1"}]}""")
        assertEquals(listOf(2), b.map { it.code })
        assertEquals("Roll back to build 1", b[0].note)
    }

    @Test fun signatureRules() {
        assertEquals("That isn't a ProxAI-built app", AppInstaller.check("com.evil.app", "aa", null))
        assertEquals("Couldn't read the app's signature", AppInstaller.check("com.proxai.apps.x", null, null))
        assertNull(AppInstaller.check("com.proxai.apps.x", "AA11", null))          // first install: trusted
        assertNull(AppInstaller.check("com.proxai.apps.x", "aa11", "AA11"))        // same key
        assertTrue(AppInstaller.check("com.proxai.apps.x", "bb22", "aa11")!!.contains("different key"))
    }

    @Test fun sha256OfFile() {
        val f = Files.createTempFile("apk", ".bin").toFile()
        f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", PcAppsRepo.sha256(f))
        f.delete()
    }
}
