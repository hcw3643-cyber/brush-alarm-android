package com.example.brushalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import com.example.brushalarm.MainActivity
import com.example.brushalarm.data.AlarmEntity
import java.time.ZonedDateTime

object AlarmScheduler {
    data class ScheduleResult(
        val triggerAt: Long,
        val exact: Boolean,
        val elapsedWatchdog: Boolean
    )

    fun nextTime(
        hour: Int,
        minute: Int,
        weekdays: Int = 0b1111111,
        now: ZonedDateTime = ZonedDateTime.now()
    ): Long {
        for (dayOffset in 0..7) {
            val candidate = now.plusDays(dayOffset.toLong())
                .withHour(hour).withMinute(minute).withSecond(0).withNano(0)
            val dayBit = 1 shl (candidate.dayOfWeek.value - 1)
            if (candidate.isAfter(now) && weekdays and dayBit != 0) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return now.plusDays(1).withHour(hour).withMinute(minute)
            .withSecond(0).withNano(0).toInstant().toEpochMilli()
    }

    fun schedule(
        context: Context,
        alarm: AlarmEntity,
        at: Long = nextTime(alarm.hour, alarm.minute, alarm.weekdays)
    ): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java)
        // Remove the action-less PendingIntent used by 0.2.2 and older so an
        // in-place upgrade cannot leave a second legacy occurrence behind.
        listOf(ACTION_ALARM_CLOCK, ACTION_ELAPSED_WATCHDOG).forEach { action ->
            existingPendingIntent(context, alarm.id, action)?.let(manager::cancel)
        }
        legacyPendingIntent(context, alarm.id)?.let(manager::cancel)
        val intent = alarmIntent(context, alarm.id, at, ACTION_ALARM_CLOCK)
        val pending = PendingIntent.getBroadcast(
            context, alarm.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        var exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
        var elapsedWatchdog = false
        if (exact) {
            val showAlarm = PendingIntent.getActivity(
                context,
                alarm.id.toInt(),
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                // Register as a real user-visible alarm clock. The system owns
                // this PendingIntent, so killing the app process does not remove it.
                manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, showAlarm), pending)
            } catch (_: SecurityException) {
                // Exact-alarm access can be revoked between the permission check
                // and this call. Keep a best-effort alarm instead of losing it.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
                exact = false
            }
            if (exact) {
                // Some vendor ROMs have been observed holding an RTC alarm until
                // the display turns on. Register the same occurrence against the
                // monotonic elapsed clock as a second wake-up source. The receiver
                // atomically deduplicates both PendingIntents.
                runCatching {
                    val watchdog = PendingIntent.getBroadcast(
                        context,
                        alarm.id.toInt(),
                        alarmIntent(context, alarm.id, at, ACTION_ELAPSED_WATCHDOG),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    val delay = (at - System.currentTimeMillis()).coerceAtLeast(0L)
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        watchdog
                    )
                    elapsedWatchdog = true
                }
            }
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        DirectBootAlarmStore.put(context, alarm.copy(nextTriggerAt = at))
        AlarmDiagnosticLog.record(
            context,
            event = "scheduled",
            alarmId = alarm.id,
            details = "trigger_at=$at exact=$exact elapsed_watchdog=$elapsedWatchdog"
        )
        return ScheduleResult(at, exact, elapsedWatchdog)
    }

    fun cancel(context: Context, id: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        listOf(ACTION_ALARM_CLOCK, ACTION_ELAPSED_WATCHDOG).forEach { action ->
            PendingIntent.getBroadcast(
                context,
                id.toInt(),
                Intent(context, AlarmReceiver::class.java).setAction(action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let(manager::cancel)
        }
        legacyPendingIntent(context, id)?.let(manager::cancel)
        DirectBootAlarmStore.remove(context, id)
        AlarmDiagnosticLog.record(context, "canceled", id)
    }

    private fun alarmIntent(context: Context, id: Long, at: Long, action: String) =
        Intent(context, AlarmReceiver::class.java)
            .setAction(action)
            .putExtra(AlarmReceiver.EXTRA_ID, id)
            .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, at)

    private fun legacyPendingIntent(context: Context, id: Long) =
        PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, AlarmReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    private fun existingPendingIntent(context: Context, id: Long, action: String) =
        PendingIntent.getBroadcast(
            context,
            id.toInt(),
            Intent(context, AlarmReceiver::class.java).setAction(action),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

    const val ACTION_ALARM_CLOCK = "brushalarm.ALARM_CLOCK"
    const val ACTION_ELAPSED_WATCHDOG = "brushalarm.ELAPSED_WATCHDOG"
}
