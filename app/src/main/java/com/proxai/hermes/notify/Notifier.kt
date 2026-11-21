package com.proxai.hermes.notify

import android.Manifest
import android.app.Notification
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
import com.proxai.hermes.data.ChatNotices
import com.proxai.hermes.data.NoticeStore
import com.proxai.hermes.data.PhoneApps
import com.proxai.hermes.data.ResearchSend
import com.proxai.hermes.data.WorkbenchLogin
import kotlinx.coroutines.flow.first
import com.proxai.hermes.ui.MainActivity

/** Notification channels and the notifications: reminders, finished replies, PC notices and alerts. */
object Notifier {
    const val CH_REMINDERS = "reminders"
    const val CH_REPLIES = "replies"
    const val CH_PC = "pc-alerts"
    const val CH_PC_CRIT = "pc-alerts-critical"
    const val CH_NOTICES = "pc-notices"
    const val CH_CALENDAR = "calendar"
    const val CH_BRIEF = "brief"
    const val CH_WORKBENCH = "workbench-logins"
    const val CH_WORKBENCH_READY = "workbench-ready"
    const val CH_SENDING = "pc-sending"
    const val LISTEN_ID = 400_000
    /** 0.9.6: an app or file going to the PC (ResearchSendService), and the "it's on the PC" note after. */
    const val SEND_ID = 400_001
    private const val SEND_DONE_ID = 400_002
    private const val TAG_WORKBENCH = "workbench-login"
    private const val REPLY_ID = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_REMINDERS, "Reminders", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "Reminders you asked ProxAI for" })
        nm.createNotificationChannel(NotificationChannel(CH_REPLIES, "Replies", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "ProxAI finished answering while the app was closed" })
        nm.createNotificationChannel(NotificationChannel(CH_PC, "PC alerts", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "The PC ran hot or short on memory (warnings)" })
        // B5 (10-30): one channel per switch in Settings, so Android's own settings match them too.
        nm.createNotificationChannel(NotificationChannel(CH_CALENDAR, "Calendar heads-ups", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A few minutes before an event on your calendar" })
        nm.createNotificationChannel(NotificationChannel(CH_BRIEF, "Brief and nudges", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "The morning brief, check-ins and the end of day" })
        nm.createNotificationChannel(NotificationChannel(CH_NOTICES, "App builds and PC updates", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "Apps ready to install, scheduled jobs' results, Study Coach, wiki, scout and pipeline" })
        nm.createNotificationChannel(NotificationChannel(CH_PC_CRIT, "PC critical alerts", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "The PC hit a critical temperature; it protects itself (unloads the local model)" })
        // 0.9.4: Workbench logins pop up (high); the listener's own notification stays out of the way (min).
        nm.createNotificationChannel(NotificationChannel(CH_WORKBENCH, "Workbench logins", NotificationManager.IMPORTANCE_HIGH)
            .apply { description = "A browser is asking to log in to the Workbench on your PC: tap to answer" })
        nm.createNotificationChannel(NotificationChannel(CH_WORKBENCH_READY, "Connected to your PC", NotificationManager.IMPORTANCE_MIN)
            .apply { description = "Shown while ProxAI listens for Workbench logins; you can hide it" })
        nm.createNotificationChannel(NotificationChannel(CH_SENDING, "Sending to the PC", NotificationManager.IMPORTANCE_LOW)
            .apply { description = "Progress while an app or file goes to your PC for ProxAI to look inside" })
        nm.createNotificationChannel(NotificationChannel(CH_VAULT, "Vault changes", NotificationManager.IMPORTANCE_DEFAULT)
            .apply { description = "A change to your notes you approved, with Undo" })
    }

    /** 0.9.6: the send's own notification (Android needs one while it runs in the background), with its progress. */
    fun researchSending(context: Context, title: String, done: Long, total: Long): Notification =
        NotificationCompat.Builder(context, CH_SENDING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Sending $title to the PC")
            .setContentText(when {
                total <= 0 -> "Getting it ready…"
                done >= total -> "Finishing on the PC…"   // all handed to the network; the PC is still taking it in
                else -> "${PhoneApps.mb(done)} of ${PhoneApps.mb(total)}"
            })
            .setProgress(100, if (total > 0) (done * 100 / total).toInt() else 0, total <= 0)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openChat(context, SEND_ID))
            .build()

    @Suppress("MissingPermission")
    fun showResearchSending(context: Context, title: String, done: Long, total: Long) {
        if (canPost(context)) NotificationManagerCompat.from(context).notify(SEND_ID, researchSending(context, title, done, total))
    }

    /** The send ended while ProxAI wasn't on screen: says how; tapping opens the chat, where it waits in the tray. */
    @Suppress("MissingPermission")
    fun researchDone(context: Context, s: ResearchSend) {
        if (!canPost(context)) return
        val ok = s.error == null
        NotificationManagerCompat.from(context).notify(SEND_DONE_ID, NotificationCompat.Builder(context, CH_NOTICES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (ok) "${s.title} is on the PC" else "Couldn't send ${s.title} to the PC")
            .setContentText(if (ok) "Ask ProxAI about it: it's waiting in the chat" else s.error)
            .setAutoCancel(true)
            .setContentIntent(openChat(context, SEND_DONE_ID))
            .build())
    }

    /** The listener's own notification (Android requires one while it keeps its connection open). */
    fun pcListening(context: Context, workbench: Boolean, notices: Boolean): Notification =
        NotificationCompat.Builder(context, CH_WORKBENCH_READY)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Connected to your PC")
            .setContentText(when {
                notices && workbench -> "PC notices and Workbench logins arrive right away"
                notices -> "PC notices arrive right away"
                else -> "A Workbench login you ask for on your PC pops up here"
            })
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(if (notices) openChat(context, LISTEN_ID) else openWorkbench(context, LISTEN_ID))
            .build()

    /** A browser is asking to log in: pops up; tapping it opens ProxAI's three-number prompt. */
    @Suppress("MissingPermission")
    fun workbenchLogin(context: Context, login: WorkbenchLogin) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_WORKBENCH)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Workbench login")
            .setContentText("${login.label} is asking to log in. Tap to answer.")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setTimeoutAfter(120_000)   // the Workbench drops the request after two minutes too
            .setAutoCancel(true)
            .setContentIntent(openWorkbench(context, login.id.hashCode()))
            .build()
        NotificationManagerCompat.from(context).notify(TAG_WORKBENCH, login.id.hashCode(), n)
    }

    /** Takes down the login notifications the Workbench no longer lists (answered elsewhere, cancelled, expired). */
    fun keepWorkbenchLogins(context: Context, waiting: List<String>) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val keep = waiting.map { it.hashCode() }.toSet()
        nm.activeNotifications.filter { it.tag == TAG_WORKBENCH && it.id !in keep }.forEach { nm.cancel(TAG_WORKBENCH, it.id) }
    }

    fun cancelWorkbenchLogin(context: Context, id: String) =
        NotificationManagerCompat.from(context).cancel(TAG_WORKBENCH, id.hashCode())

    private fun openWorkbench(context: Context, requestCode: Int): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    @Suppress("MissingPermission")
    fun pcNotice(context: Context, n: com.proxai.hermes.data.PcNotice) {
        if (!canPost(context) || n.title.isBlank()) return
        val id = 300_000 + n.id
        val channel = when (com.proxai.hermes.data.NoticeBuzz.group(n.kind, n.title)) {
            com.proxai.hermes.data.Buzz.CALENDAR -> CH_CALENDAR
            com.proxai.hermes.data.Buzz.BRIEF -> CH_BRIEF
            com.proxai.hermes.data.Buzz.UPDATES -> CH_NOTICES
        }
        val b = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(n.title)
            .setContentText(n.text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.text.take(1500)))
            // C2: tapping puts the whole notice in the chat.
            .setContentIntent(openNotice(context, id, ChatNotices.key(n)))
            .setAutoCancel(true)
        if (n.kind in ChatNotices.CHAT_KINDS) b.addAction(replyAction(context, id, ChatNotices.key(n)))
        NotificationManagerCompat.from(context).notify(id, b.build())
    }

    // ---- VAULT-UNDO-APP (0.9.18): approved vault changes, tagged so their ids never meet the others' ----
    const val CH_VAULT = "vault-changes"
    private const val TAG_VAULT = "vault-change"

    /** One approved vault change: its line only (path, how, who approved it), never a note's text. An Undo button
     *  when [VaultChange.undoable]; the button's intent carries only the entry's key. */
    @Suppress("MissingPermission")
    fun vaultChange(context: Context, c: com.proxai.hermes.data.VaultChange) {
        if (!canPost(context)) return
        val text = if (c.mark.isEmpty()) c.line else c.line + "\n" + c.mark
        val b = vaultBase(context, c, text)
        if (c.undoable) b.addAction(undoAction(context, c.key))
        NotificationManagerCompat.from(context).notify(TAG_VAULT, c.key.hashCode(), b.build())
    }

    /** The Undo was tapped: say so, without the button, until the PC answers. */
    @Suppress("MissingPermission")
    fun vaultUndoing(context: Context, key: String) {
        if (!canPost(context)) return
        val c = ProxApp.instance.settings.vaultYesPending.firstOrNull { it.key == key } ?: return
        NotificationManagerCompat.from(context).notify(TAG_VAULT, key.hashCode(),
            vaultBase(context, c, c.line + "\nUndoing…").setOnlyAlertOnce(true).build())
    }

    /** The PC's answer to an Undo, said plainly (done, 409 the note changed since, 503 not set up...). [retry]: the
     *  Undo button comes back (the PC was away, or not set up yet). */
    @Suppress("MissingPermission")
    fun vaultUndone(context: Context, c: com.proxai.hermes.data.VaultChange, message: String, retry: Boolean) {
        if (!canPost(context)) return
        val b = vaultBase(context, c, c.line + "\n" + message).setOnlyAlertOnce(true)
        if (retry && c.undoable) b.addAction(undoAction(context, c.key))
        NotificationManagerCompat.from(context).notify(TAG_VAULT, c.key.hashCode(), b.build())
    }

    /** The Undo was tapped while ProxMobile is locked: nothing was sent; the notification says to unlock first. */
    @Suppress("MissingPermission")
    fun vaultNeedsUnlock(context: Context, c: com.proxai.hermes.data.VaultChange) {
        if (!canPost(context)) return
        val b = vaultBase(context, c, c.line + "\n" + com.proxai.hermes.data.VaultYes.UNLOCK_TO_UNDO).setOnlyAlertOnce(true)
        if (c.undoable) b.addAction(undoAction(context, c.key))
        NotificationManagerCompat.from(context).notify(TAG_VAULT, c.key.hashCode(), b.build())
    }

    /** Undo's button: on Android 12+ the system asks for the phone to be unlocked before it runs (#47). */
    private fun undoAction(context: Context, key: String) =
        NotificationCompat.Action.Builder(0, "Undo", undoIntent(context, key)).setAuthenticationRequired(true).build()

    private fun vaultBase(context: Context, c: com.proxai.hermes.data.VaultChange, text: String) =
        NotificationCompat.Builder(context, CH_VAULT)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Vault change")
            .setContentText(c.line)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openWorkbench(context, c.key.hashCode()))   // just opens ProxMobile
            .setAutoCancel(true)

    private fun undoIntent(context: Context, key: String): PendingIntent = PendingIntent.getBroadcast(
        context, key.hashCode(),
        Intent(context, VaultUndoReceiver::class.java).setAction(VaultUndoReceiver.ACTION)
            .putExtra(VaultUndoReceiver.EXTRA_KEY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun pcAlert(context: Context, a: com.proxai.hermes.data.PcAlert) {
        if (!canPost(context)) return
        val title = a.title
        val time = a.time.substringAfter('T').take(5)
        val n = NotificationCompat.Builder(context, if (a.critical) CH_PC_CRIT else CH_PC)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText("$time · ${a.msg}")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$time · ${a.msg}"))
            .setContentIntent(openNotice(context, 200_000 + a.id, ChatNotices.key(a)))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(200_000 + a.id, n)
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

    /** Round 4 (0.9.15): a notice's intent carries only its key, a pointer into [NoticeStore]; [addNoticeFrom] never
     *  reads a notice's text, title or flags from an intent. */
    private fun Intent.notice(key: String): Intent = putExtra(EXTRA_NOTICE_KEY, key)

    /** C2: opens the chat with this notice in it as a ProxAI message. */
    private fun openNotice(context: Context, requestCode: Int, key: String): PendingIntent = PendingIntent.getActivity(
        context, requestCode,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_CHAT, true).notice(key)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /**
     * C3: "Reply" typed right in the notification. The answer goes into the chat (with the notice
     * before it, so ProxAI knows what it answers) and is sent in the background ([ChatSendWorker]).
     * A RemoteInput needs a mutable PendingIntent; it's explicit (only ReplyReceiver gets it).
     */
    private fun replyAction(context: Context, notificationId: Int, key: String? = null,
                            channel: String = "dm"): NotificationCompat.Action {
        val intent = Intent(context, ReplyReceiver::class.java).setAction(ReplyReceiver.ACTION_REPLY)
            .putExtra(ReplyReceiver.EXTRA_NOTIFICATION, notificationId).putExtra(ReplyReceiver.EXTRA_CHANNEL, channel)
        if (key != null) intent.notice(key)
        val pi = PendingIntent.getBroadcast(context, notificationId, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = androidx.core.app.RemoteInput.Builder(ReplyReceiver.KEY_TEXT).setLabel("Reply to ProxAI").build()
        return NotificationCompat.Action.Builder(0, "Reply", pi)
            .addRemoteInput(input)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAllowGeneratedReplies(false)
            .build()
    }

    @Suppress("MissingPermission")
    fun reply(context: Context, text: String, channelId: String = "dm", channelName: String? = null) {
        if (!canPost(context) || text.isBlank()) return
        val n = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(if (channelName != null) "ProxAI replied in #$channelName" else "ProxAI replied")
            .setContentText(text.take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(text.take(1000)))
            .setContentIntent(openChat(context, 0))
            .addAction(replyAction(context, REPLY_ID, channel = channelId))   // C3: keep the conversation going from here
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REPLY_ID, n)
    }

    fun clearReply(context: Context) = NotificationManagerCompat.from(context).cancel(REPLY_ID)

    private const val REPLY_WAITING_ID = 2
    private const val UNLOCK_FOR_APP_ID = 3
    @Volatile private var lastUnlockNudge = 0L

    /** S1: a reply typed into a notification while ProxAI was locked; it goes out after the unlock. */
    @Suppress("MissingPermission")
    fun replyWaiting(context: Context, text: String) {
        if (!canPost(context)) return
        val n = NotificationCompat.Builder(context, CH_REPLIES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("ProxAI is locked")
            .setContentText("Unlock it to send your reply")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Unlock ProxAI to send your reply: \"${text.take(300)}\""))
            .setContentIntent(openChat(context, REPLY_WAITING_ID))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(REPLY_WAITING_ID, n)
    }

    fun clearReplyWaiting(context: Context) = NotificationManagerCompat.from(context).cancel(REPLY_WAITING_ID)

    /** S1: a PC-built app asked for data while ProxAI was locked. At most one a minute. */
    @Suppress("MissingPermission")
    fun unlockForApp(context: Context, appName: String) {
        val now = System.currentTimeMillis()
        if (!canPost(context) || now - lastUnlockNudge < 60_000L) return
        lastUnlockNudge = now
        val n = NotificationCompat.Builder(context, CH_NOTICES)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Unlock ProxAI to use $appName")
            .setContentText("$appName asked for your data while ProxAI was locked. Tap to unlock.")
            .setContentIntent(openChat(context, UNLOCK_FOR_APP_ID))
            .setTimeoutAfter(10 * 60_000L)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(UNLOCK_FOR_APP_ID, n)
    }

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

    const val EXTRA_NOTICE_KEY = "notice_key"

    /** The notice a tap or a reply points at, put into the chat (main thread). Round 4 (0.9.15): MainActivity takes
     *  intents from any app, so the intent is only a pointer. The notice is ProxMobile's own copy from the PC
     *  ([NoticeStore], filled by NoticeSync and PcAlertWorker); a key that isn't there is dropped. */
    fun addNoticeFrom(intent: Intent?) {
        val app = ProxApp.instance
        val n = NoticeStore.of(app).get(intent?.getStringExtra(EXTRA_NOTICE_KEY)) ?: return
        app.chat.addNotice(n.key, n.kind, n.title, n.text, ChatNotices.timeOf(n.time),
            noQuote = n.noQuote, decision = n.decision, next = n.next)
    }
}

/** C3: a reply typed into a notification. */
class ReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REPLY) return
        val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_TEXT)?.toString()?.trim()
        // The notification shows a spinner until it's updated or removed; the answer comes as "ProxAI replied".
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, -1))
        if (text.isNullOrEmpty()) return
        Notifier.addNoticeFrom(intent)
        val channel = intent.getStringExtra(EXTRA_CHANNEL) ?: "dm"
        val app = ProxApp.instance
        if (app.lock.check()) {
            // S1: nothing reaches the AI while locked. The reply waits and goes out after the unlock.
            val s = app.settings
            s.pendingReply = if (s.pendingReply.isNotBlank() && s.pendingReplyChannel == channel) s.pendingReply + "\n\n" + text else text
            s.pendingReplyChannel = channel
            Notifier.replyWaiting(context, text)
            return
        }
        ChatSendWorker.enqueue(context, text, channel)
    }

    companion object {
        const val ACTION_REPLY = "com.proxai.hermes.REPLY"
        const val KEY_TEXT = "reply_text"
        const val EXTRA_NOTIFICATION = "notification_id"
        const val EXTRA_CHANNEL = "channel"
    }
}

/**
 * Sends a notification reply through the normal chat and waits for the answer, so the app keeps
 * running until it's done (a receiver alone may be stopped mid-reply). Expedited on Android 12+.
 */
class ChatSendWorker(ctx: Context, params: androidx.work.WorkerParameters) : androidx.work.CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val text = inputData.getString(KEY_TEXT)?.takeIf { it.isNotBlank() } ?: return Result.success()
        val chat = ProxApp.instance.chat
        val channel = inputData.getString(KEY_CHANNEL) ?: "dm"
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { chat.send(text, channel = channel) }
        // Done when nothing is streaming and nothing is waiting to be sent (a reply sent mid-answer is queued).
        kotlinx.coroutines.withTimeoutOrNull(9 * 60_000L) {
            kotlinx.coroutines.flow.combine(chat.busy, chat.queuedText) { busy, queued -> !busy && queued == null }
                .first { it }
        }
        return Result.success()
    }

    companion object {
        private const val KEY_TEXT = "text"
        private const val KEY_CHANNEL = "channel"

        /** [channel]: where the reply goes (the channel the notification came from). */
        fun enqueue(ctx: Context, text: String, channel: String = "dm") {
            val b = androidx.work.OneTimeWorkRequestBuilder<ChatSendWorker>()
                .setInputData(androidx.work.workDataOf(KEY_TEXT to text, KEY_CHANNEL to channel))
            if (Build.VERSION.SDK_INT >= 31) b.setExpedited(androidx.work.OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            androidx.work.WorkManager.getInstance(ctx).enqueue(b.build())
        }
    }
}

/** Alarm went off (or a notification button was tapped). */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val store = ProxApp.instance.reminders
        val id = intent.getIntExtra(EXTRA_ID, -1)
        when (intent.action) {
            ACTION_FIRE -> {
                val r = store.get(id) ?: return
                // B5: with the Reminders switch off, it lands quietly in the chat instead of buzzing.
                if (ProxApp.instance.settings.buzzReminders) Notifier.reminder(context, r.id, r.text)
                else ProxApp.instance.chat.addNotice("reminder:${r.id}:${r.at}", "reminder", "⏰ Reminder", r.text,
                    System.currentTimeMillis())
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
        PcListener.sync(context)   // listen again after a reboot or an update (PC notices, Workbench logins)
    }
}
