package com.proxai.hermes.data

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * S2 (Phase 3): the screen behind the power-button assistant. Android hands the assistant a
 * screenshot when "Use screenshot" is on (Settings > Apps > Default apps > Digital assistant app).
 * It stays in memory only, for that one press, and goes to ProxAI only when Nathan taps
 * "Look at my screen" (Nathan, 10-30). Each press is a new [generation]; an overlay only offers
 * its own press's screen and drops it when it closes.
 */
object ScreenContext {
    data class Shot(val bitmap: Bitmap, val appName: String?, val generation: Int)

    private val _shot = MutableStateFlow<Shot?>(null)
    val shot: StateFlow<Shot?> = _shot

    @Volatile var generation = 0
        private set
    @Volatile private var appName: String? = null

    /** A new power-button press: forget the last screen. Returns this press's generation. */
    @Synchronized
    fun newLook(): Int {
        generation++
        appName = null
        _shot.value = null
        return generation
    }

    /** The app on screen (from Android's assist data); may come before or after the screenshot. */
    @Synchronized
    fun app(name: String?) {
        appName = name
        _shot.value = _shot.value?.copy(appName = name)
    }

    @Synchronized
    fun offer(bitmap: Bitmap) {
        _shot.value = Shot(bitmap, appName, generation)
    }

    /** The screen of press [gen], once (it's cleared as it's taken). */
    @Synchronized
    fun take(gen: Int): Shot? = _shot.value?.takeIf { it.generation == gen }?.also { _shot.value = null }

    /** The overlay for press [gen] closed: its screen is dropped. */
    @Synchronized
    fun drop(gen: Int) {
        if (_shot.value?.generation == gen) _shot.value = null
    }
}
