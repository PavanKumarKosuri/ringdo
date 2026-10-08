package app.ringdo

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat

object AlarmScheduler {
    const val EXTRA_ID = "todo_id"

    private fun alarmIntent(ctx: Context, t: Todo): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, t.code,
            Intent(ctx, AlarmReceiver::class.java).setAction("app.ringdo.FIRE.${t.id}").putExtra(EXTRA_ID, t.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    fun canScheduleExact(ctx: Context): Boolean {
        val am = ctx.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /** Uses setAlarmClock: the same API the Clock app uses. Exact, survives Doze, shows the alarm icon in the status bar. */
    fun schedule(ctx: Context, t: Todo) {
        if (t.done) { cancel(ctx, t); return }
        val am = ctx.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val at = maxOf(t.due, System.currentTimeMillis() + 1000)
        if (canScheduleExact(ctx)) {
            am.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), alarmIntent(ctx, t))
        } else {
            // Fallback if the user revoked exact alarms: still wakes the device, may be a few minutes late.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(ctx, t))
        }
    }

    fun cancel(ctx: Context, t: Todo) {
        ctx.getSystemService(AlarmManager::class.java).cancel(alarmIntent(ctx, t))
    }

    /** Re-register every pending alarm (after boot, update, time change). Missed ones ring right away. */
    fun rescheduleAll(ctx: Context) {
        TodoStore.todos(ctx).filter { !it.done && !it.fired }.forEach { schedule(ctx, it) }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getStringExtra(AlarmScheduler.EXTRA_ID) ?: return
        val svc = Intent(ctx, AlarmService::class.java).setAction(AlarmService.ACTION_RING).putExtra(AlarmScheduler.EXTRA_ID, id)
        try {
            ContextCompat.startForegroundService(ctx, svc)
        } catch (e: Exception) {
            // Very rare (exact alarms revoked + background start blocked): fall back to a loud full-screen notification.
            AlarmService.fallbackNotify(ctx, id)
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        AlarmScheduler.rescheduleAll(ctx)
    }
}
