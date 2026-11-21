package com.proxai.hermes.assist

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.proxai.hermes.data.ScreenContext
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.proxai.hermes.ui.AssistActivity

/**
 * Registering a VoiceInteractionService is what makes ProxAI show up under
 * Settings → Apps → Default apps → Digital assistant app. Once picked there,
 * "press and hold power button" (and the corner-swipe assist gesture) lands here.
 */
class ProxVoiceInteractionService : VoiceInteractionService() {
    override fun onReady() {
        super.onReady()
        running = this
    }

    override fun onShutdown() {
        if (running === this) running = null
        super.onShutdown()
    }

    companion object {
        @Volatile private var running: ProxVoiceInteractionService? = null

        /**
         * WAKE-PHONE: "Hey Prox" opens the same session as the power-button hold (allowed from the background
         * and over the lock screen, because ProxAI is the digital assistant). No screenshot and no app name are
         * asked for (flags 0), and the session ignores any that arrive. False when ProxAI isn't the assistant.
         */
        fun showWake(unlockToTalk: Boolean = false): Boolean {
            val s = running ?: return false
            return runCatching {
                s.showSession(Bundle().apply {
                    putString(ARG_SOURCE, com.proxai.hermes.data.VoicePolicy.WAKE)
                    putBoolean(ARG_UNLOCK_TO_TALK, unlockToTalk)
                }, 0)
            }.isSuccess
        }

        const val ARG_SOURCE = "proxai_source"
        /** Round 3 (shape C): open with only "Unlock to talk". The overlay also decides this itself. */
        const val ARG_UNLOCK_TO_TALK = "proxai_unlock_to_talk"
    }
}

class ProxSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ProxSession(this)
}

/**
 * Has no UI of its own: it opens the Compose voice overlay and hides right away.
 * S2: when Android sends a screenshot (the "Use screenshot" setting; only while the phone is
 * unlocked) and the app's name, the overlay opens once they're here (at most [WAIT_MS]), so
 * "Look at my screen" can use them ([ScreenContext]); nothing is sent unless that's tapped.
 * 10-30 (0.9.2): 0.9.1 opened the overlay first and kept the session up for the screenshot; on the
 * S26 the overlay then didn't open at all while unlocked (on the lock screen there's no screenshot,
 * so it still worked). Open-then-hide-at-once is the order that has always worked.
 */
class ProxSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    private val handler = Handler(Looper.getMainLooper())
    private val openNow = Runnable { open() }
    private var waiting = false
    private var needShot = false
    private var needApp = false
    /** "power", or "wake" for "Hey Prox" (WAKE-PHONE: never a screenshot or the app's name). */
    private var source = "power"
    private var unlockToTalk = false

    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        ScreenContext.newLook()
        handler.removeCallbacks(openNow)
        source = if (args?.getString(ProxVoiceInteractionService.ARG_SOURCE) == com.proxai.hermes.data.VoicePolicy.WAKE)
            com.proxai.hermes.data.VoicePolicy.WAKE else "power"
        unlockToTalk = source == com.proxai.hermes.data.VoicePolicy.WAKE &&
            args?.getBoolean(ProxVoiceInteractionService.ARG_UNLOCK_TO_TALK, false) == true
        val mayLook = com.proxai.hermes.data.VoicePolicy.mayLookAtScreen(source)
        needShot = mayLook && showFlags and SHOW_WITH_SCREENSHOT != 0
        needApp = needShot && showFlags and SHOW_WITH_ASSIST != 0
        if (needShot) {
            waiting = true
            handler.postDelayed(openNow, WAIT_MS)
        } else open()
    }

    /** Opens the overlay, then hides this session at once (as before 0.9.1). */
    private fun open() {
        handler.removeCallbacks(openNow)
        waiting = false
        needShot = false
        val intent = Intent(context, AssistActivity::class.java)
            .putExtra(AssistActivity.EXTRA_SOURCE, source)
            .putExtra(AssistActivity.EXTRA_UNLOCK_TO_TALK, unlockToTalk)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            // Works over the lock screen and is allowed from the background.
            startAssistantActivity(intent)
        } catch (e: Exception) {
            context.startActivity(intent)
        }
        hide()
    }

    private fun openIfReady() {
        if (waiting && !needShot && !needApp) open()
    }

    override fun onHandleAssist(state: AssistState) {
        super.onHandleAssist(state)
        if (state.index == 0 && com.proxai.hermes.data.VoicePolicy.mayLookAtScreen(source)) {
            val pkg = state.assistStructure?.activityComponent?.packageName
            if (pkg != null && pkg != context.packageName) {
                val pm = context.packageManager
                ScreenContext.app(runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull())
            }
            needApp = false
        }
        openIfReady()
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null && com.proxai.hermes.data.VoicePolicy.mayLookAtScreen(source)) ScreenContext.offer(screenshot)
        needShot = false
        openIfReady()
    }

    override fun onHide() {
        super.onHide()
        handler.removeCallbacks(openNow)
        waiting = false
    }

    companion object {
        /** The longest the overlay waits for the screenshot and app name (they usually take ~0.2 s). */
        const val WAIT_MS = 700L
    }
}

/** Android requires an assistant to declare a recognizer. We use Google's/on-device one instead. */
class StubRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        listener?.error(SpeechRecognizer.ERROR_CLIENT)
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

/** Quick Settings tile — a fallback trigger on phones whose power button can't be remapped. */
class AssistTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = Intent(this, AssistActivity::class.java)
            .putExtra(AssistActivity.EXTRA_SOURCE, "tile")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
