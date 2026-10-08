package app.ringdo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.text.DateFormat
import java.util.Date

/**
 * Rings like a real alarm clock: loops the alarm tone on the ALARM audio stream (plays even when the
 * ringer is on silent), raises a too-low alarm volume, ramps loudness, vibrates continuously and shows
 * a full-screen alarm over the lock screen. Keeps going until Done / Stop / Snooze.
 */
class AlarmService : Service() {
    companion object {
        const val ACTION_RING = "app.ringdo.RING"
        const val ACTION_SNOOZE = "app.ringdo.SNOOZE"
        const val ACTION_DONE = "app.ringdo.DONE"
        const val ACTION_STOP = "app.ringdo.STOP"
        const val EXTRA_MIN = "minutes"
        const val CHANNEL = "ringdo_alarm_v1"
        private const val NOTIF_ID = 4242
        private const val AUTO_SNOOZE_MS = 10 * 60 * 1000L

        private val _ringing = MutableStateFlow<Todo?>(null)
        val ringing: StateFlow<Todo?> = _ringing

        fun ensureChannel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) != null) return
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Full-screen alarm when a todo is due"
                setSound(null, null)             // the service plays the sound itself, looping
                enableVibration(false)           // and vibrates itself, continuously
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setBypassDnd(true)
            })
        }

        private const val FALLBACK_CHANNEL = "ringdo_fallback_v1"
        fun fallbackNotify(ctx: Context, id: String) {
            val t = TodoStore.get(ctx, id) ?: return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(FALLBACK_CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(FALLBACK_CHANNEL, "Backup alarms", NotificationManager.IMPORTANCE_HIGH).apply {
                    setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
                    vibrationPattern = longArrayOf(0, 900, 400, 900, 400, 900); enableVibration(true); setBypassDnd(true)
                })
            }
            val open = PendingIntent.getActivity(ctx, 2, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, FALLBACK_CHANNEL).setSmallIcon(R.drawable.ic_stat_alarm)
                .setContentTitle(t.title).setContentText("Due now").setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX).setFullScreenIntent(open, true).setContentIntent(open).build()
            n.flags = n.flags or Notification.FLAG_INSISTENT   // keeps repeating the sound until opened
            runCatching { nm.notify(t.code, n) }
        }

        fun action(ctx: Context, action: String, id: String, minutes: Int = 0): Intent =
            Intent(ctx, AlarmService::class.java).setAction(action)
                .putExtra(AlarmScheduler.EXTRA_ID, id).putExtra(EXTRA_MIN, minutes)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var savedAlarmVolume: Int? = null
    private val queue = ArrayDeque<String>()
    private var current: Todo? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(AlarmScheduler.EXTRA_ID)
        when (intent?.action) {
            ACTION_RING -> {
                val t = id?.let { TodoStore.get(this, it) }
                if (t == null || t.done) {
                    goForeground(Todo(title = "Ringdo", start = System.currentTimeMillis())); finishIfIdle(); return START_NOT_STICKY
                }
                if (current == null) startRinging(t) else if (current?.id != t.id && t.id !in queue) { queue.addLast(t.id); goForeground(current!!) }
            }
            ACTION_SNOOZE -> resolve(id) { t ->
                val min = intent.getIntExtra(EXTRA_MIN, 5).coerceAtLeast(1)
                TodoStore.log(this, t, EventType.SNOOZED)
                Actions.snooze(t, System.currentTimeMillis(), min).also { AlarmScheduler.schedule(this, it) }
            }
            ACTION_DONE -> resolve(id) { t ->
                TodoStore.log(this, t, EventType.DONE)
                if (t.isTest) null else Actions.complete(t, System.currentTimeMillis()).also { AlarmScheduler.schedule(this, it) }
            }
            ACTION_STOP -> resolve(id) { t ->
                TodoStore.log(this, t, if (t.nag) EventType.NAGGED else EventType.STOPPED)
                if (t.isTest) null else Actions.stop(t, System.currentTimeMillis()).also { if (!it.done && !it.fired) AlarmScheduler.schedule(this, it) }
            }
            else -> finishIfIdle()
        }
        return START_NOT_STICKY
    }

    /** Apply an outcome to the ringing todo (null result = delete it), then ring the next queued alarm or stop. */
    private fun resolve(id: String?, outcome: (Todo) -> Todo?) {
        val t = (id?.let { TodoStore.get(this, it) }) ?: current
        if (t != null) {
            val next = outcome(t)
            if (next == null) TodoStore.remove(this, t.id) else TodoStore.upsert(this, next)
        }
        if (t == null || t.id == current?.id) {
            stopRinging()
            while (queue.isNotEmpty()) {
                val n = TodoStore.get(this, queue.removeFirst())
                if (n != null && !n.done) { startRinging(n); return }
            }
            finishIfIdle()
        } else queue.remove(t.id)
    }

    private fun startRinging(t: Todo) {
        current = t
        _ringing.value = t
        goForeground(t)
        acquireWake()
        playTone(t)
        vibrate()
        handler.postDelayed(autoSnooze, AUTO_SNOOZE_MS)
        // If the screen is on and unlocked the system shows a heads-up instead of the full-screen intent;
        // try to bring the alarm screen up directly too (allowed while the app is visible).
        runCatching { startActivity(Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private val autoSnooze = Runnable {
        current?.let { t ->
            TodoStore.log(this, t, EventType.MISSED)
            resolve(t.id) { Actions.snooze(it, System.currentTimeMillis(), 10).also { n -> AlarmScheduler.schedule(this, n) } }
        }
    }

    private fun goForeground(t: Todo) {
        ensureChannel(this)
        val full = PendingIntent.getActivity(
            this, 1, Intent(this, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun svc(a: String, code: Int, min: Int = 0) = PendingIntent.getService(
            this, t.code * 10 + code, action(this, a, t.id, min), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setColor(0xFFFF6A3D.toInt())
            .setContentTitle(t.title)
            .setContentText(t.notes.ifBlank { "Ringing · due " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t.occAt)) })
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(full, true)
            .setContentIntent(full)
            .addAction(0, "Snooze 5 min", svc(ACTION_SNOOZE, 1, 5))
            .addAction(0, if (t.nag) "Stop (nags in ${t.nagMin}m)" else "Stop", svc(ACTION_STOP, 2))
            .addAction(0, "Done", svc(ACTION_DONE, 3))
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, n, type)
    }

    private fun playTone(t: Todo) {
        val am = getSystemService(AudioManager::class.java)
        // Make sure the alarm can actually be heard: raise the alarm stream to at least 70% while ringing.
        runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            val cur = am.getStreamVolume(AudioManager.STREAM_ALARM)
            val min = (max * 0.7f).toInt().coerceAtLeast(1)
            if (cur < min) { savedAlarmVolume = cur; am.setStreamVolume(AudioManager.STREAM_ALARM, min, 0) }
        }
        val candidates = listOfNotNull(
            t.toneUri?.let(Uri::parse),
            RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        )
        for (uri in candidates) {
            val mp = runCatching {
                MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@AlarmService, uri)
                    isLooping = true
                    prepare()
                    setVolume(0.35f, 0.35f)
                    start()
                }
            }.getOrNull()
            if (mp != null) { player = mp; break }
        }
        rampVolume(0)
    }

    /** Gets louder over ~25 seconds, like a gentle-to-urgent alarm clock. */
    private fun rampVolume(step: Int) {
        val v = (0.35f + step * 0.026f).coerceAtMost(1f)
        runCatching { player?.setVolume(v, v) }
        if (v < 1f && player != null) handler.postDelayed({ rampVolume(step + 1) }, 1000)
    }

    @Suppress("DEPRECATION")
    private fun vibrate() {
        val vib = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            getSystemService(VibratorManager::class.java).defaultVibrator
        else getSystemService(Vibrator::class.java)
        vibrator = vib
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 900, 400, 900, 400), 0)
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
        runCatching { vib.vibrate(effect, attrs) }
    }

    private fun acquireWake() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ringdo:alarm").apply { acquire(AUTO_SNOOZE_MS + 60_000) }
    }

    private fun stopRinging() {
        handler.removeCallbacksAndMessages(null)
        runCatching { player?.stop() }; runCatching { player?.release() }; player = null
        runCatching { vibrator?.cancel() }
        savedAlarmVolume?.let { v -> runCatching { getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_ALARM, v, 0) } }
        savedAlarmVolume = null
        current = null
        _ringing.value = null
    }

    private fun finishIfIdle() {
        if (current != null) return
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() { stopRinging(); super.onDestroy() }
}
