package com.proxai.hermes.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
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
class ProxVoiceInteractionService : VoiceInteractionService()

class ProxSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = ProxSession(this)
}

/** Has no UI of its own: it immediately opens the Compose voice overlay. */
class ProxSession(service: VoiceInteractionSessionService) : VoiceInteractionSession(service) {
    override fun onPrepareShow(args: Bundle?, showFlags: Int) {
        super.onPrepareShow(args, showFlags)
        setUiEnabled(false)
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val intent = Intent(context, AssistActivity::class.java)
            .putExtra(AssistActivity.EXTRA_SOURCE, "power")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            // Works over the lock screen and is allowed from the background.
            startAssistantActivity(intent)
        } catch (e: Exception) {
            context.startActivity(intent)
        }
        hide()
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
