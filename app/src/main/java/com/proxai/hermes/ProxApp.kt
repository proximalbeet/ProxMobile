package com.proxai.hermes

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.proxai.hermes.data.AppStore
import com.proxai.hermes.data.ChatRepository
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.PcAppsRepo
import com.proxai.hermes.data.PcModeMonitor
import com.proxai.hermes.data.ReminderStore
import com.proxai.hermes.data.Settings
import com.proxai.hermes.notify.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the singletons shared by the main app, the voice overlay and generated apps. */
class ProxApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings
    lateinit var hermes: HermesClient
    lateinit var apps: AppStore
    lateinit var pcMode: PcModeMonitor
    lateinit var reminders: ReminderStore
    lateinit var chat: ChatRepository
    /** Apps ProxAI built on the PC as APKs (Phase 15). */
    lateinit var pcApps: PcAppsRepo

    /** Activities currently on screen; 0 = the app is in the background. */
    private var started = 0
    val inForeground: Boolean get() = started > 0

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        hermes = HermesClient(settings)
        apps = AppStore(this)
        pcMode = PcModeMonitor(settings)
        reminders = ReminderStore(this)
        pcApps = PcAppsRepo(this, settings, scope)
        chat = ChatRepository(this, settings, hermes, apps, pcMode, reminders, scope) { inForeground }
        Notifier.createChannels(this)
        // Alarms are dropped when the app is updated; set them again.
        reminders.rescheduleAll()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                started++
                Notifier.clearReply(a)
            }
            override fun onActivityStopped(a: Activity) { started = maxOf(0, started - 1) }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    companion object {
        lateinit var instance: ProxApp
            private set
    }
}
