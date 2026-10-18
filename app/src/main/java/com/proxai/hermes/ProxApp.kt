package com.proxai.hermes

import android.app.Application
import com.proxai.hermes.data.AppStore
import com.proxai.hermes.data.ChatRepository
import com.proxai.hermes.data.HermesClient
import com.proxai.hermes.data.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the singletons shared by the main app, the voice overlay and generated apps. */
class ProxApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: Settings
    lateinit var hermes: HermesClient
    lateinit var apps: AppStore
    lateinit var chat: ChatRepository

    override fun onCreate() {
        super.onCreate()
        instance = this
        settings = Settings(this)
        hermes = HermesClient(settings)
        apps = AppStore(this)
        chat = ChatRepository(this, settings, hermes, apps, scope)
    }

    companion object {
        lateinit var instance: ProxApp
            private set
    }
}
