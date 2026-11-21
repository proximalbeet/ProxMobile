package com.proxai.hermes.data

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * ProxAI line (0.9.9, Phase 14c; Nathan 11-02): no spoken PIN when he calls the ProxAI number while ProxMobile
 * is unlocked, and ProxMobile stays unlocked through the call ("let it relock after the call is over back to
 * the timer set").
 *
 * What one phone's call states mean (TelephonyManager.CALL_STATE_*). Pure, tested in CallWatcherTest:
 * - OFFHOOK while ProxMobile is unlocked (or has no app lock at all): the call holds a lock that's on open
 *   ([AppLock.holdForCall]). An outgoing call (IDLE then OFFHOOK, no RINGING between) also tells the PC
 *   "start", which lets the next call to the ProxAI line skip the PIN (the PC uses a start once, within 45 s).
 * - IDLE after such a call: the hold ends ([AppLock.endCallHold]: the normal timer starts) and the PC hears
 *   "end".
 * - A call that starts while locked changes nothing. Any call is held, not only calls to ProxAI: the phone
 *   can't cheaply tell which number was dialled.
 */
class CallWatcher(private val lock: AppLock, private val tell: (event: String) -> Unit) {
    private var last = IDLE
    private var ringing = false
    private var active = false   // the call began with ProxMobile unlocked

    fun onState(state: Int) {
        when (state) {
            RINGING -> ringing = true
            OFFHOOK -> if (last != OFFHOOK) {
                val outgoing = last == IDLE && !ringing
                active = !lock.check()           // unlocked now (always, with the app lock off)
                if (active) lock.holdForCall()   // a lock that's on stays open through the call
                if (active && outgoing) tell("start")
            }
            IDLE -> {
                if (active) {
                    lock.endCallHold()
                    tell("end")
                }
                active = false
                ringing = false
            }
        }
        last = state
    }

    companion object {
        const val IDLE = 0       // TelephonyManager.CALL_STATE_IDLE
        const val RINGING = 1    // TelephonyManager.CALL_STATE_RINGING
        const val OFFHOOK = 2    // TelephonyManager.CALL_STATE_OFFHOOK

        /** WAKE-PHONE: a call is ringing or going ("Hey Prox" doesn't listen then). */
        fun inCall(state: Int): Boolean = state == RINGING || state == OFFHOOK
    }
}

/**
 * Tells the PC's phone line that a call started or ended: POST {"event": "start" | "end"} with the line
 * token, to the URL in Settings (blank = <server>/phone/call). Best effort: 5 s, every failure swallowed.
 * The token never goes in a log, the UI or an error.
 */
class LineClient(val url: String, private val token: String) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).callTimeout(5, TimeUnit.SECONDS)
        .build()

    /** True if the PC said ok. Never throws. */
    fun tell(event: String): Boolean {
        if (url.isBlank() || token.isBlank() || event !in EVENTS) return false
        return try {
            val req = Request.Builder().url(url)
                .header("Authorization", "Bearer $token")
                .post("""{"event":"$event"}""".toRequestBody("application/json".toMediaType()))
                .build()
            http.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false   // PC off, off the tailnet, a bad URL: the PIN is asked as usual
        }
    }

    companion object {
        private val EVENTS = setOf("start", "end")

        fun from(s: Settings): LineClient =
            LineClient(s.lineUrl.ifBlank { if (s.serverUrl.isBlank()) "" else "${s.baseUrl}/phone/call" }, s.lineToken)
    }
}
