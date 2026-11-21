package com.proxai.hermes.voice

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.app.KeyguardManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.proxai.hermes.ProxApp
import com.proxai.hermes.R
import com.proxai.hermes.assist.ProxVoiceInteractionService
import com.proxai.hermes.data.MicGate
import com.proxai.hermes.data.PcPresenceFeed
import com.proxai.hermes.data.PhoneCalls
import com.proxai.hermes.data.PhoneUnlocks
import com.proxai.hermes.data.WakeHold
import com.proxai.hermes.data.WakeInputs
import com.proxai.hermes.data.WakeLockScreen
import com.proxai.hermes.data.WakeRules
import com.proxai.hermes.data.WakeSettings
import com.proxai.hermes.ui.AssistActivity
import com.proxai.hermes.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.concurrent.thread

/**
 * WAKE-PHONE: "Hey Prox" on the phone. A foreground service of type microphone, so Android shows its green mic
 * dot while the mic is open, with a fixed notification that says what it's doing the whole time.
 *
 * It runs while Settings > Voice > Listen for "Hey Prox" is on. Once a second (and at once when a call, the PC's
 * desk word or ProxAI's own mic use changes) it asks [WakeRules.decide] whether to listen, and opens or closes
 * the mic to match. The service itself keeps running while paused, because Android only lets a microphone
 * service start while ProxAI is on screen.
 *
 * The sound goes mic -> [WakeLoop] -> the engine, in memory only. On the word: the mic closes, a short buzz,
 * then the same overlay as the power-button hold ([WakeFlow]); speech is turned into text by the phone's
 * on-device recognizer only (AssistActivity, [com.proxai.hermes.data.VoicePolicy]).
 */
class WakeWordService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val main = Handler(Looper.getMainLooper())
    private lateinit var wake: WakeSettings
    private var controlJob: Job? = null
    private var engine: WakeEngine? = null
    private var engineTried = false
    @Volatile private var want = false
    @Volatile private var listener: Thread? = null
    private var destroyed = false
    /** Which mic thread is the current one (main thread only). */
    private var listenGen = 0
    private var shown: WakeHold? = null
    private var shownWords: Pair<String, String>? = null
    private var coolUntil = 0L
    private var batteryPct = -1
    private var charging = false
    private var wakeLock: PowerManager.WakeLock? = null

    private val battery = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else -1
            charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            tick()
        }
    }

    /** Round 3 (shape C): his phone unlocks, for the hands-free window on the lock screen. */
    private val unlocks = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_USER_PRESENT) PhoneUnlocks.seen(System.currentTimeMillis())
        }
    }

    private val flow by lazy { WakeFlow(Hands()) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        PhoneUnlocks.reset()   // shape C's clock counts only unlocks this listener saw itself
        wake = WakeSettings(this)
        channels(this)
        ContextCompat.registerReceiver(this, battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, unlocks, IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_NOT_EXPORTED)
        wakeLock = getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ProxAI:HeyProx")?.apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Round 2: the fixed notification was swiped away (Android 14+ allows it): put it straight back.
        if (intent?.action == ACTION_REPOST && controlJob?.isActive == true) {
            shownWords = null
            tick()
            return START_STICKY
        }
        // Android needs startForeground() after startForegroundService(), even when it stops right away.
        val (title, text) = shownWords ?: ("“Hey Prox” is starting" to "The mic opens in a moment; this stays here the whole time.")
        try {
            ServiceCompat.startForeground(this, NOTICE_ID, notice(title, text),
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        } catch (e: Exception) {
            // Not allowed now (started from the background, or the mic was taken back): it starts the next time
            // ProxAI is opened.
            Log.i(TAG, "not started: ${e.javaClass.simpleName}")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!wake.enabled || !canListen(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (controlJob?.isActive != true) controlJob = scope.launch { control() }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        destroyed = true
        want = false
        scope.cancel()
        runCatching { unregisterReceiver(battery) }
        runCatching { unregisterReceiver(unlocks) }
        PhoneUnlocks.reset()
        val t = listener
        val e = engine
        engine = null
        // The engine is closed after the mic thread has let go of it (it stops within ~100 ms).
        thread(name = "hey-prox-close") {
            runCatching { t?.join(2_000) }
            runCatching { e?.close() }
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    private suspend fun control() {
        if (!engineTried) {
            engineTried = true
            engine = withContext(Dispatchers.IO) { WakeEngines.create(applicationContext) }
        }
        scope.launch { MicGate.holders.collect { tick() } }
        scope.launch { PhoneCalls.active.collect { tick() } }
        scope.launch { PcPresenceFeed.state.collect { tick() } }
        while (scope.isActive) {
            tick()
            delay(TICK_MS)
        }
    }

    /** Asks the rules, then opens or closes the mic and updates the notification to match. Main thread. */
    @SuppressLint("MissingPermission")   // the notification is best effort (runCatching)
    private fun tick() {
        if (!::wake.isInitialized) return
        val now = System.currentTimeMillis()
        val hold = WakeRules.decide(inputs(now, cooling = now < coolUntil))
        if (hold == WakeHold.OFF) {
            want = false
            stopSelf()
            return
        }
        want = hold == WakeHold.NONE
        if (want && listener == null) listen()
        if (want) wakeLock?.acquire(WAKE_LOCK_MS)   // refreshed every tick while listening
        else wakeLock?.let { if (it.isHeld) it.release() }
        val words = WakeRules.notice(hold, wake.muteUntil, now, wake.wakesOn(today()))
        if (words != shownWords) {   // a new reason, or the mute's minutes went down
            shown = hold
            shownWords = words
            runCatching { NotificationManagerCompat.from(this).notify(NOTICE_ID, notice(words.first, words.second)) }
        }
    }

    /** Opens the mic on its own thread; the thread closes it when [want] goes false or the word is heard. */
    @SuppressLint("MissingPermission")   // canListen() checked in tick()
    private fun listen() {
        val e = engine ?: return
        val gen = ++listenGen
        // Assigned before it starts, so a thread that ends at once can't leave a dead one behind (it never
        // listened again).
        val t = thread(start = false, name = "hey-prox") {
            var heard = false
            var opened = false
            try {
                val mic = AndroidMic.open(e.sampleRate)
                if (mic != null) {
                    opened = true
                    heard = try { WakeLoop(mic, e).runUntilWord { want } } finally { mic.close() }   // mic closed first
                }
            } catch (x: Throwable) {
                Log.w(TAG, "mic loop ended: ${x.javaClass.simpleName}")
            }
            main.post {
                if (gen == listenGen) listener = null   // this thread is done with the mic
                if (destroyed) return@post              // switched off meanwhile: a late word opens nothing
                // The mic wouldn't open (busy, or another app holds it): try again in a few seconds, not every tick.
                if (!opened) coolUntil = maxOf(coolUntil, System.currentTimeMillis() + MIC_RETRY_MS)
                if (heard) onHeard()
            }
        }
        listener = t
        t.start()
    }

    /** What the rules read, right now. [cooling]: just after a wake, or the mic failed to open. */
    private fun inputs(now: Long, cooling: Boolean): WakeInputs {
        val audioMode = getSystemService(AudioManager::class.java)?.mode ?: 0
        return WakeInputs(
            enabled = wake.enabled,
            micAllowed = canListen(this),
            noticeVisible = noticeVisible(this),
            engineReady = engine != null,
            muteUntil = wake.muteUntil,
            now = now,
            inCall = PhoneCalls.active.value || WakeRules.callMode(audioMode),
            presence = PcPresenceFeed.state.value,
            batteryPct = batteryPct,
            charging = charging,
            micBusy = MicGate.held || cooling,
        )
    }

    private fun onHeard() {
        val now = System.currentTimeMillis()
        // Round 2 (race): the rules again, as they are now; a call, mute or "desk" that came in meanwhile wins.
        val hold = WakeRules.decide(inputs(now, cooling = false))
        val keyguard = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        coolUntil = now + COOL_MS   // the overlay takes the mic; don't reopen it under its feet
        tick()
        flow.onHeard(now, hold, lockScreen(this, keyguard, now))
    }

    private fun today(): Long = java.time.LocalDate.now().toEpochDay()

    private inner class Hands : WakeHands {
        override fun buzz() {
            runCatching {
                getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            }
        }

        override fun beep() {
            runCatching {
                val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
                main.postDelayed({ tone.release() }, 400)
            }
        }

        override fun openOverlay(source: String, unlockToTalk: Boolean): Boolean {
            // As the digital assistant, Android lets ProxAI open its overlay from the background and over the
            // lock screen (the same session as the power button, without a screenshot).
            if (ProxVoiceInteractionService.showWake(unlockToTalk)) return true
            if (ProxApp.instance.inForeground) {
                startActivity(overlayIntent(this@WakeWordService, source, unlockToTalk))
                return true
            }
            heardNotice(source, unlockToTalk)
            return false
        }

        override fun log(line: String) {
            SpeechLog.add(line)
            Log.i(TAG, line)
        }

        override fun countWake() {
            wake.countWake(today())
            shownWords = null   // the notification shows the new count
        }
    }

    /** Not the digital assistant and in the background: Android won't open the overlay, so it offers a tap. */
    @SuppressLint("MissingPermission")
    private fun heardNotice(source: String, unlockToTalk: Boolean) {
        val tap = PendingIntent.getActivity(this, HEARD_ID, overlayIntent(this, source, unlockToTalk),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(this, CH_HEARD)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Heard “Hey Prox”")
            .setContentText("Tap to talk to ProxAI")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(15_000)
            .setAutoCancel(true)
            .setContentIntent(tap)
            .build()
        runCatching { NotificationManagerCompat.from(this).notify(HEARD_ID, n) }
    }

    private fun notice(title: String, text: String): Notification {
        val open = PendingIntent.getActivity(this, NOTICE_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        // Round 2: swiped away -> this service puts it back at once (it never listens out of sight).
        val repost = PendingIntent.getService(this, NOTICE_ID,
            Intent(this, WakeWordService::class.java).setAction(ACTION_REPOST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CH_LISTENING)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Shown at once (Android 12+ may hold a service's notification back for 10 s otherwise).
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .setDeleteIntent(repost)
            .build()
    }

    /** The phone's mic, 16 kHz mono. Its own short buffer is zeroed after every read and on close. */
    private class AndroidMic(private val rec: AudioRecord) : MicSource {
        private var shorts = ShortArray(0)

        override fun read(into: FloatArray): Int {
            if (shorts.size != into.size) shorts = ShortArray(into.size)
            val n = rec.read(shorts, 0, shorts.size)
            if (n < 0) return -1
            for (i in 0 until n) into[i] = shorts[i] / 32768f
            shorts.fill(0, 0, n)
            return n
        }

        override fun close() {
            runCatching { rec.stop() }
            rec.release()
            shorts.fill(0)
        }

        companion object {
            @SuppressLint("MissingPermission")
            fun open(rate: Int): AndroidMic? {
                val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                if (min <= 0) return null
                val rec = runCatching {
                    AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
                }.getOrNull() ?: return null
                if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return null }
                return runCatching { rec.startRecording(); AndroidMic(rec) }.getOrElse { rec.release(); null }
            }
        }
    }

    companion object {
        private const val TAG = "ProxWake"
        const val NOTICE_ID = 400_010
        private const val HEARD_ID = 400_011
        const val CH_LISTENING = "wake-listening"
        private const val CH_HEARD = "wake-heard"
        private const val TICK_MS = 1_000L
        /** After a wake the mic stays closed this long, so the overlay can take it. */
        const val COOL_MS = 4_000L
        /** After the mic failed to open. */
        private const val MIC_RETRY_MS = 5_000L
        private const val ACTION_REPOST = "com.proxai.hermes.WAKE_REPOST"

        /** The service is up (the tile says "Open ProxAI to start" when it isn't). */
        @Volatile var running = false
            private set

        /** Round 2: its fixed notification can show; while it can't, it doesn't listen. */
        fun noticeVisible(context: Context): Boolean {
            val nm = context.getSystemService(NotificationManager::class.java)
            return WakeRules.noticeVisible(NotificationManagerCompat.from(context).areNotificationsEnabled(),
                nm?.getNotificationChannel(CH_LISTENING)?.importance)
        }
        private const val WAKE_LOCK_MS = 10 * 60_000L

        fun canListen(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

        /** The same overlay the power button opens, marked as a wake (on-device speech only, no screen). */
        fun overlayIntent(context: Context, source: String, unlockToTalk: Boolean = false): Intent =
            Intent(context, AssistActivity::class.java)
                .putExtra(AssistActivity.EXTRA_SOURCE, source)
                .putExtra(AssistActivity.EXTRA_UNLOCK_TO_TALK, unlockToTalk)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

        /** Round 3: what a wake does on the lock screen right now (shape C); the overlay asks the same. */
        fun lockScreen(context: Context, keyguardLocked: Boolean, now: Long): WakeLockScreen.Do {
            val w = WakeSettings(context)
            return WakeLockScreen.decide(keyguardLocked, now, appLockOn = ProxApp.instance.settings.appLock,
                lastPhoneUnlock = PhoneUnlocks.last, windowMs = w.handsFreeWindowMs)
        }

        fun channels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(NotificationChannel(CH_LISTENING, "Listening for “Hey Prox”", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown the whole time “Hey Prox” is on: listening, muted or paused" })
            nm.createNotificationChannel(NotificationChannel(CH_HEARD, "“Hey Prox” heard you", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "When Android won't open the voice overlay by itself: tap to talk" })
        }

        /**
         * Starts or stops the listener to match Settings. Safe from anywhere: a start Android refuses (from the
         * background) happens the next time ProxAI is opened. Never started without the engine or the mic.
         */
        fun sync(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            if (WakeSettings(context).enabled && WakeEngines.AVAILABLE && canListen(context)) {
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    // ForegroundServiceStartNotAllowedException or IllegalStateException: not allowed right now
                }
            } else {
                context.stopService(intent)
            }
        }
    }
}
