package com.proxai.hermes.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.R
import com.proxai.hermes.ui.MainActivity

/** Notification channels and the two kinds of notifications: reminders and finished replies. */
object Notifier {
    const val CH_REMINDERS = "reminders"
    const val CH_REPLIES = "replies"
    private const val REPLY_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Reminders you asked ProxAI for" })
        nm.createNotificationChannel(NotificationChannel(CH_REPLIES, "Replies", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Hermes finished answering while the app was closed" })
    }

    fun canPost(context: Context): Boolean =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openChat(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CHAT, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("MissingPermission")
    fun reply(context: Context, text: String) {
        if (!canPost(context) || text.isBlank()) return
        val n = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Hermes replied")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(openChat(context, 0))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REPLY_ID, n)
    }

    fun clearReply(context: Context) = NotificationManagerCompat.from(context).cancel(REPLY_ID)

    @Suppress("MissingPermission")
    fun reminder(context: Context, id: Int, text: String) {
        if (!canPost(context)) return
        fun action(action: String) = PendingIntent.getBroadcast(
            context, id * 10 + action.length,
            Intent(context, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id)
                .putExtra(ReminderReceiver.EXTRA_TEXT, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, CH_REMINDERS)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Reminder")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(openChat(context, 100_000 + id))
            .addAction(0, "Snooze 10 min", action(ReminderReceiver.ACTION_SNOOZE))
            .addAction(0, "Done", action(ReminderReceiver.ACTION_DONE))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(100_000 + id, n)
    }

    fun cancelReminder(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(100_000 + id)
}

/** Alarm went off (or a notification button was tapped). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ProxApp.instance.reminders
        val id = intent.getIntExtra(EXTRA_ID, -1)
        when (intent.action) {
            ACTION_FIRE -> {
                val r = store.get(id) ?: return
                Notifier.reminder(context, r.id, r.text)
                store.remove(r.id)
            }
            ACTION_SNOOZE -> {
                Notifier.cancelReminder(context, id)
                val text = intent.getStringExtra(EXTRA_TEXT) ?: return
                store.add(System.currentTimeMillis() + 10 * 60_000L, text)
            }
            ACTION_DONE -> Notifier.cancelReminder(context, id)
        }
    }

    companion object {
        const val ACTION_FIRE = "com.proxai.hermes.REMINDER"
        const val ACTION_SNOOZE = "com.proxai.hermes.REMINDER_SNOOZE"
        const val ACTION_DONE = "com.proxai.hermes.REMINDER_DONE"
        const val EXTRA_ID = "id"
        const val EXTRA_TEXT = "text"
    }
}

/** Alarms don't survive a reboot or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ProxApp.instance.reminders.rescheduleAll()
    }
}
