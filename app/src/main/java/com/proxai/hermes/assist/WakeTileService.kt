package com.proxai.hermes.assist

import android.app.AlertDialog
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.proxai.hermes.data.WakeMute
import com.proxai.hermes.data.WakeSettings
import com.proxai.hermes.ui.MainActivity
import com.proxai.hermes.voice.WakeWordService

/**
 * WAKE-PHONE: the "Hey Prox" Quick Settings tile, beside "Ask ProxAI". On = listening allowed; tap it to mute
 * for 1 hour or until you turn it back on; tap a muted tile to listen again. With the Settings switch off, or
 * the listener not running, it opens ProxAI (the switch lives in Settings > Voice, not here).
 *
 * Round 2: on a locked phone muting stays one tap (until turned back on, no dialog over the lock screen), and
 * unmuting asks for the phone's unlock first (unlockAndRun), so nobody else can turn the mic back on from there.
 */
class WakeTileService : TileService() {
    private val wake by lazy { WakeSettings(this) }
    private val main = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        val now = System.currentTimeMillis()
        when (WakeTile.tap(wake.enabled, wake.muteUntil, now, phoneLocked = isLocked, serviceUp = WakeWordService.running)) {
            WakeTile.Tap.OPEN_APP -> openApp()
            WakeTile.Tap.UNMUTE -> unmute()
            WakeTile.Tap.UNLOCK_THEN_UNMUTE -> unlockAndRun { unmute() }
            WakeTile.Tap.MUTE_UNTIL_ON -> {
                wake.muteUntil = WakeMute.UNTIL_ON
                refresh()
            }
            WakeTile.Tap.ASK_MUTE -> showDialog(AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Mute “Hey Prox”")
                .setItems(arrayOf("For 1 hour", "Until I turn it back on")) { _, which ->
                    wake.muteUntil = WakeTile.muteChoice(which, System.currentTimeMillis())
                    refresh()
                }
                .setNegativeButton("Cancel", null)
                .create())
        }
    }

    private fun unmute() {
        wake.muteUntil = 0L
        // In case Android stopped it meanwhile. From here Android may refuse to start a microphone service; the
        // tile then says "Open ProxAI to start".
        WakeWordService.sync(this)
        refresh()
        main.postDelayed({ refresh() }, 1_500)
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val now = System.currentTimeMillis()
        val (state, sub) = WakeTile.look(wake.enabled, wake.muteUntil, now, WakeWordService.running)
        tile.state = when (state) {
            WakeTile.Look.OFF, WakeTile.Look.STOPPED -> Tile.STATE_INACTIVE   // still tappable: it opens ProxAI
            WakeTile.Look.MUTED -> Tile.STATE_INACTIVE
            WakeTile.Look.ON -> Tile.STATE_ACTIVE
        }
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = sub
        tile.updateTile()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 1, intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}

/** The tile's choices, plain so the tests can check them. */
object WakeTile {
    enum class Tap { OPEN_APP, UNMUTE, UNLOCK_THEN_UNMUTE, MUTE_UNTIL_ON, ASK_MUTE }
    enum class Look { OFF, MUTED, STOPPED, ON }

    fun tap(enabled: Boolean, muteUntil: Long, now: Long, phoneLocked: Boolean, serviceUp: Boolean): Tap = when {
        !enabled -> Tap.OPEN_APP
        WakeMute.muted(muteUntil, now) -> if (phoneLocked) Tap.UNLOCK_THEN_UNMUTE else Tap.UNMUTE
        phoneLocked -> Tap.MUTE_UNTIL_ON   // muting is always free, and one tap
        !serviceUp -> Tap.OPEN_APP
        else -> Tap.ASK_MUTE
    }

    /** The dialog's rows: 0 = for 1 hour, 1 = until turned back on. */
    fun muteChoice(which: Int, now: Long): Long = if (which == 0) WakeMute.forAnHour(now) else WakeMute.UNTIL_ON

    fun look(enabled: Boolean, muteUntil: Long, now: Long, serviceUp: Boolean): Pair<Look, String> = when {
        !enabled -> Look.OFF to "Off in Settings"
        WakeMute.muted(muteUntil, now) -> Look.MUTED to
            if (muteUntil == WakeMute.UNTIL_ON) "Muted" else WakeMute.label(muteUntil, now).removePrefix("Muted for ")
        !serviceUp -> Look.STOPPED to "Open ProxAI to start"
        else -> Look.ON to "On"
    }
}
