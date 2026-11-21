package com.proxai.hermes

import android.content.ContextWrapper
import android.content.SharedPreferences
import com.proxai.hermes.data.AppLock
import com.proxai.hermes.data.PinAttempts
import com.proxai.hermes.data.PinCode
import com.proxai.hermes.data.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** S1 (Phase 3): the app lock's timer, the apps' window, the PIN hash and the waits after wrong PINs. */
class AppLockTest {
    private var now = 1_000_000L
    private var on = true
    private var timeout = 60_000L
    private fun lock() = AppLock({ on }, { timeout }, { now })

    @Test fun aFreshStartIsLockedAndItRelocksAfterTheTimeAway() {
        val l = lock()
        assertTrue(l.locked.value)
        l.onScreen()
        assertTrue(l.check())
        l.unlock()
        assertFalse(l.check())
        l.offScreen(); now += 59_000; l.onScreen()
        assertFalse(l.locked.value)                       // back within the minute: still open
        l.offScreen(); now += 60_000; l.onScreen()
        assertTrue(l.locked.value)                        // a full minute away (Nathan's default): locked
        timeout = 15 * 60_000L
        l.unlock(); l.offScreen(); now += 10 * 60_000L; l.onScreen()
        assertFalse(l.locked.value)                       // the setting is read each time
    }

    @Test fun onScreenItNeverTimesOut() {
        val l = lock()
        l.onScreen(); l.unlock()
        now += 60 * 60_000L
        assertFalse(l.check())
    }

    @Test fun switchedOffMeansNeverLocked() {
        on = false
        val l = lock()
        assertFalse(l.locked.value)
        l.lockNow()
        assertFalse(l.check())
        assertTrue(l.appsAllowed())
    }

    @Test fun appsKeepWorkingWhileUsedButNeverUnlockProxMobile() {
        val l = lock()
        l.onScreen(); l.unlock(); l.offScreen()           // went to a PC-built app
        repeat(5) { now += 50_000; assertTrue(l.appsAllowed()) }   // used every 50 s: works for 250 s
        assertTrue(l.check())                             // ProxMobile itself relocked after a minute
        now += 61_000
        assertFalse(l.appsAllowed())                      // app left alone over a minute: refused
        assertFalse(l.appsAllowed())                      // a refused call doesn't reopen it
        l.onScreen(); l.unlock()
        assertTrue(l.appsAllowed())
    }

    @Test fun appsAreRefusedAfterAFreshStartUntilAnUnlock() {
        assertFalse(lock().appsAllowed())
    }

    @Test fun lockNowAndTurningItOn() {
        val l = lock()
        l.turnedOn()                                      // just switched on in Settings: stays open
        assertFalse(l.check())
        l.lockNow()
        assertTrue(l.locked.value)
        assertFalse(l.appsAllowed())
        var ran = 0
        l.onUnlocked = { ran++ }
        l.unlock()
        assertEquals(1, ran)                              // a reply that waited goes out now
    }

    @Test fun pinsAreHashedNeverStored() {
        assertTrue(PinCode.valid("1234"))
        assertTrue(PinCode.valid("12345678"))
        listOf("123", "123456789", "12a4", "", "12 34").forEach { assertFalse(it, PinCode.valid(it)) }
        val h = PinCode.hash("4821")
        assertFalse(h.contains("4821"))
        assertTrue(PinCode.matches("4821", h))
        assertFalse(PinCode.matches("4822", h))
        assertNotEquals(h, PinCode.hash("4821"))            // a new salt every time
        assertFalse(PinCode.matches("4821", ""))
        assertFalse(PinCode.matches("4821", "pbkdf2:x:y:z"))
    }

    @Test fun wrongPinsMakeYouWait() {
        assertEquals(0, PinCode.waitAfter(4))
        assertEquals(30_000L, PinCode.waitAfter(5))
        assertEquals(30_000L, PinCode.waitAfter(9))
        assertEquals(60_000L, PinCode.waitAfter(10))
        assertEquals(15 * 60_000L, PinCode.waitAfter(500))
    }

    @Test fun attemptsAreCountedAndSurviveARestart() {
        val prefs = mutableMapOf<String, MemPrefs>()
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = prefs.getOrPut(name) { MemPrefs() }
        }
        val s = Settings(ctx)
        assertFalse(s.appLock)                            // no PIN yet: the switch alone doesn't lock
        s.appLock = true
        assertFalse(s.appLock)
        s.pinHash = PinCode.hash("2468")
        assertTrue(s.appLock)

        var t = 5_000_000L
        repeat(4) { i ->
            val r = PinAttempts.attempt(s, "0000", t)
            assertEquals(PinAttempts.Result.Wrong(4 - i, 0), r)
        }
        val fifth = PinAttempts.attempt(s, "0000", t) as PinAttempts.Result.Wrong
        assertEquals(30_000L, fifth.waitMs)
        assertEquals(PinAttempts.Result.Wait(30_000L), PinAttempts.attempt(Settings(ctx), "2468", t))   // even the right PIN waits
        t += 30_000
        assertEquals(PinAttempts.Result.Ok, PinAttempts.attempt(s, "2468", t))
        assertEquals(0, s.pinFails)
    }
}
