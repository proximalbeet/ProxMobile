package com.proxai.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

object Notifications {
    private const val CHANNEL = "app"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "App notifications", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    fun show(ctx: Context, title: String, text: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, ShellActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
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
