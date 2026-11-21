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
import kotlinx.coroutines.launch
import com.proxai.hermes.data.baseUrl

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
    /** Images and PDFs ProxAI sends into the chat (Phase 2, C6). */
    lateinit var media: com.proxai.hermes.data.MediaCache
    /** Sites Nathan trusts (Phase 2, C9). */
    lateinit var trusted: com.proxai.hermes.data.TrustedSites
    /** The app lock (Phase 3, S1). */
    lateinit var lock: com.proxai.hermes.data.AppLock

    /** ProxMobile's screens that are up. WAKE-PHONE round 2: a wake overlay nobody has touched is up (replies need
     *  no notification) but doesn't count as on screen for the app lock's timer. */
    private val screens = com.proxai.hermes.data.OnScreen<Activity> {
        (it as? com.proxai.hermes.ui.AssistActivity)?.countsOnScreen != false
    }
    val inForeground: Boolean get() = screens.anyVisible
    /** VAULT-UNDO-APP: the once-a-minute vault-change check while ProxMobile is on screen. */
    private var vaultPoll: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        hermes = HermesClient(settings)
        apps = AppStore(this)
        pcMode = PcModeMonitor(settings)
        reminders = ReminderStore(this)
        pcApps = PcAppsRepo(this, settings, scope)
        media = com.proxai.hermes.data.MediaCache(this, settings)
        trusted = com.proxai.hermes.data.TrustedSites(settings)
        lock = com.proxai.hermes.data.AppLock({ settings.appLock }, { settings.lockAfterMs })
        lock.onUnlocked = ::sendWaitingReply
        // P4 (0.9.7): proxDocs follows the app lock's time away (DocsTimer: ProxAI on screen, or since 0.9.8 a
        // proxDocs app in use) and locks on "Lock now". Not on every app-lock flip: an app in use flips the app
        // lock without being stopped (AppLock.appsAllowed), and proxDocs in that app has to keep working too.
        com.proxai.hermes.data.ProxDocs.client = {
            if (settings.isConfigured) com.proxai.hermes.data.ProxDocsClient(settings.baseUrl, settings.apiKey) else null
        }
        com.proxai.hermes.data.ProxDocs.relockMs = { settings.lockAfterMs }
        lock.onLockedNow = { com.proxai.hermes.data.ProxDocs.lock("proxDocs locked with ProxAI.") }
        // ProxAI line (0.9.9): calls hold the app lock while "No PIN while unlocked" is on and allowed.
        com.proxai.hermes.notify.CallMonitor.sync(this)
        chat = ChatRepository(this, settings, hermes, apps, pcMode, reminders, scope) { inForeground }
        Notifier.createChannels(this)
        // Alarms are dropped when the app is updated; set them again.
        reminders.rescheduleAll()
        com.proxai.hermes.data.PcAlertWorker.schedule(this)
        com.proxai.hermes.notify.VaultYesWorker.schedule(this)

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (screens.start(a)) firstOnScreen(a)
                Notifier.clearReply(a)
            }
            override fun onActivityStopped(a: Activity) {
                if (screens.stop(a)) {
                    vaultPoll?.cancel(); vaultPoll = null
                    lock.offScreen()
                    com.proxai.hermes.data.ProxDocs.offScreen()
                }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
    }

    /** The first counted ProxMobile screen came up. */
    private fun firstOnScreen(a: Activity) {
        lock.onScreen()   // S1: locks here if ProxMobile was out of sight too long
        com.proxai.hermes.data.ProxDocs.onScreen()
        com.proxai.hermes.data.PcAlertWorker.checkNow(a)
        // VAULT-UNDO-APP (0.9.18): approved vault changes, now and every minute while ProxMobile is open
        vaultPoll?.cancel()
        vaultPoll = scope.launch {
            while (true) {
                runCatching { com.proxai.hermes.notify.VaultYesSync.pull(this@ProxApp) }
                kotlinx.coroutines.delay(60_000)
            }
        }
        // WAKE-PHONE: Android only lets a microphone service start while ProxAI is on screen.
        com.proxai.hermes.voice.WakeWordService.sync(a)
    }

    /** WAKE-PHONE round 2: he touched a wake overlay (or unlocked in it): from now on it counts as on screen. */
    fun screenUsed(a: Activity) {
        if (screens.promote(a)) firstOnScreen(a)
    }

    /** S1: a reply typed into a notification while locked goes out right after the unlock. */
    private fun sendWaitingReply() {
        val text = settings.pendingReply
        if (text.isBlank()) return
        settings.pendingReply = ""
        Notifier.clearReplyWaiting(this)
        com.proxai.hermes.notify.ChatSendWorker.enqueue(this, text, settings.pendingReplyChannel)
    }

    companion object {
        lateinit var instance: ProxApp
            private set
    }
}
