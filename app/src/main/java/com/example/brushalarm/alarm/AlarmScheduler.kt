package com.example.brushalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.brushalarm.MainActivity
import com.example.brushalarm.data.AlarmEntity
import java.time.ZonedDateTime

object AlarmScheduler {
    data class ScheduleResult(val triggerAt: Long, val exact: Boolean)

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
        val intent = Intent(context, AlarmReceiver::class.java)
            .putExtra(AlarmReceiver.EXTRA_ID, alarm.id)
        val pending = PendingIntent.getBroadcast(
            context, alarm.id.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        var exact = Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()
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
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
        DirectBootAlarmStore.put(context, alarm.copy(nextTriggerAt = at))
        AlarmDiagnosticLog.record(
            context,
            event = "scheduled",
            alarmId = alarm.id,
            details = "trigger_at=$at exact=$exact"
        )
        return ScheduleResult(at, exact)
    }

    fun cancel(context: Context, id: Long) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pending = PendingIntent.getBroadcast(
            context, id.toInt(), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        pending?.let { context.getSystemService(AlarmManager::class.java).cancel(it) }
        DirectBootAlarmStore.remove(context, id)
        AlarmDiagnosticLog.record(context, "canceled", id)
    }
}
