package com.proxai.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

object Notifications {
    private const val CHANNEL = "app"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App notifications", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    /** Prox Hub's own channel ("hub" apps): agent questions, waiting agents, merges, the kill switch. */
    fun showHub(ctx: Context, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(HUB_CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(HUB_CHANNEL, "Questions and updates from your agents",
                NotificationManager.IMPORTANCE_HIGH))
        }
        show(ctx, title, text, HUB_CHANNEL)
    }

    private const val HUB_CHANNEL = "hub"

    fun show(ctx: Context, title: String, text: String, channel: String = CHANNEL) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, ShellActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title.take(100))
            .setContentText(text.take(300))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify((System.nanoTime() % Int.MAX_VALUE).toInt(), n) }
    }
}

/**
 * Prox Hub notices from the PC ("hub" feature, ProxMobile 0.9.11+): ProxMobile keeps the live connection to the PC
 * and hands each Prox Hub notice to the app that declared "hub", which shows it under its own name. Only accepted
 * from ProxMobile (Android 14+ tells the receiver who sent it) and only in an app that declared "hub".
 */
class HubNoticeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ShellLogic.HUB_NOTICE) return
        val from = if (Build.VERSION.SDK_INT >= 34) sentFromPackage else null
        val features = runCatching {
            ShellLogic.parseInfo(context.assets.open("proxai-app.json").bufferedReader().use { it.readText() }).features
        }.getOrNull()
        if (!ShellLogic.acceptHubNotice(from, features)) return
        Notifications.showHub(context, intent.getStringExtra("title") ?: "Prox Hub", intent.getStringExtra("text") ?: "")
    }
}

/** Fires reminders set with ProxAI.remind(). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Notifications.show(context, intent.getStringExtra(EXTRA_TITLE) ?: "Reminder", intent.getStringExtra(EXTRA_TEXT) ?: "")
    }

    companion object {
        const val EXTRA_TITLE = "title"
        const val EXTRA_TEXT = "text"
    }
}
