package com.proxai.hermes.voice

import android.content.Context

/**
 * WAKE-PHONE: this build has no wake engine (app/libs/sherpa-onnx.aar isn't there; README, "Hey Prox").
 * The switch in Settings says so and the listener never opens the mic.
 */
object WakeEngines {
    const val AVAILABLE = false
    fun create(context: Context): WakeEngine? = null
}
