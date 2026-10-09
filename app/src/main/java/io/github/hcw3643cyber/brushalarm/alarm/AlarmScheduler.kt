// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

package io.github.hcw3643cyber.brushalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import io.github.hcw3643cyber.brushalarm.MainActivity
import io.github.hcw3643cyber.brushalarm.data.AlarmEntity
import java.time.ZonedDateTime

object AlarmScheduler {
    data class ScheduleResult(
        val triggerAt: Long,
        val exact: Boolean,
        val elapsedWatchdog: Boolean
    )

    fun sameConfiguration(left: AlarmEntity, right: AlarmEntity): Boolean =
        left.copy(nextTriggerAt = 0) == right.copy(nextTriggerAt = 0)

    @Synchronized
    fun restore(context: Context, alarm: AlarmEntity, recomputeFuture: Boolean = false): ScheduleResult {
        val mirror = DirectBootAlarmStore.get(context, alarm.id)
        val stored = if (mirror == null) alarm else
            if (sameConfiguration(mirror, alarm)) mirror else alarm.copy(nextTriggerAt = 0)
        val state = AlarmSessionStore.read(context)
        val key = "${alarm.id}/${stored.nextTriggerAt}"
        val accepted = state.queue.any { it.occurrenceKey == key } ||
            state.completed.any { it.occurrenceKey == key }
        val at = NormalOccurrencePolicy.restoreAt(stored.nextTriggerAt, accepted,
            nextTime(alarm.hour, alarm.minute, alarm.weekdays), System.currentTimeMillis(), recomputeFuture)
        return schedule(context, alarm, at)
    }

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

    @Synchronized
    fun schedule(
        context: Context,
        alarm: AlarmEntity,
        at: Long = nextTime(alarm.hour, alarm.minute, alarm.weekdays)
    ): ScheduleResult {
        val manager = context.getSystemService(AlarmManager::class.java)
        // Persist the registration intent first; recovery can retry this exact occurrence.
        val previous = DirectBootAlarmStore.get(context, alarm.id)
        DirectBootAlarmStore.put(context, alarm.copy(nextTriggerAt = at))
        previous?.takeIf { it.nextTriggerAt > 0 && it.nextTriggerAt != at }?.let {
            cancelOccurrence(context, it.id, it.nextTriggerAt)
        }
        // Remove the action-less PendingIntent used by 0.2.2 and older so an
        // in-place upgrade cannot leave a second legacy occurrence behind.
        listOf(ACTION_ALARM_CLOCK, ACTION_ELAPSED_WATCHDOG).forEach { action ->
            existingPendingIntent(context, alarm.id, action)?.let(manager::cancel)
        }
        legacyPendingIntent(context, alarm.id)?.let(manager::cancel)
        // Remove the previous broadcast-only primary when upgrading in place.
        PendingIntent.getBroadcast(context, alarm.id.toInt(),
            alarmIntent(context, alarm.id, at, ACTION_ALARM_CLOCK),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            manager.cancel(it); it.cancel()
        }
        val pending = PendingIntent.getForegroundService(
            context, alarm.id.toInt(), alarmServiceIntent(context, alarm.id, at),
            PendingIntent.FLAG_IMMUTABLE
        )
        // Without exact-alarm access a service start has no exact-alarm exemption.
        val fallback by lazy {
            PendingIntent.getBroadcast(context, alarm.id.toInt(),
                alarmIntent(context, alarm.id, at, ACTION_ALARM_CLOCK), PendingIntent.FLAG_IMMUTABLE)
        }
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
            } catch (failure: SecurityException) {
                AlarmSessionCoordinator.failure(context, "normal_exact_schedule_failed", failure)
                // Exact-alarm access can be revoked between the permission check
                // and this call. Keep a best-effort alarm instead of losing it.
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fallback)
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
                        PendingIntent.FLAG_IMMUTABLE
                    )
                    val delay = (at - System.currentTimeMillis()).coerceAtLeast(0L)
                    manager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        SystemClock.elapsedRealtime() + delay,
                        watchdog
                    )
                    elapsedWatchdog = true
                }.onFailure { AlarmSessionCoordinator.failure(context, "normal_elapsed_schedule_failed", it) }
            }
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, fallback)
        }
        AlarmDiagnosticLog.record(
            context,
            event = "scheduled",
            alarmId = alarm.id,
            details = "trigger_at=$at exact=$exact elapsed_watchdog=$elapsedWatchdog primary=${if (exact) "foreground_service" else "broadcast_fallback"}"
        )
        return ScheduleResult(at, exact, elapsedWatchdog)
    }

    @Synchronized
    fun cancel(context: Context, id: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        DirectBootAlarmStore.get(context, id)?.let { cancelOccurrence(context, id, it.nextTriggerAt) }
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
            .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            .setData(Uri.parse("brushalarm://occurrence/$id/$at/${Uri.encode(action)}"))
            .putExtra(AlarmReceiver.EXTRA_ID, id)
            .putExtra(AlarmReceiver.EXTRA_TRIGGER_AT, at)

    private fun alarmServiceIntent(context: Context, id: Long, at: Long) =
        alarmIntent(context, id, at, ACTION_ALARM_CLOCK)
            .setClass(context, AlarmService::class.java).setFlags(0)

    private fun cancelOccurrence(context: Context, id: Long, at: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        PendingIntent.getForegroundService(context, id.toInt(), alarmServiceIntent(context, id, at),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            manager.cancel(it); it.cancel()
        }
        listOf(ACTION_ALARM_CLOCK, ACTION_ELAPSED_WATCHDOG).forEach { action ->
            PendingIntent.getBroadcast(context, id.toInt(), alarmIntent(context, id, at, action),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
                manager.cancel(it)
                it.cancel()
            }
        }
    }

    /** Real delivered occurrence retries if session persistence failed, preserving its original identity. */
    fun retryOccurrence(context: Context, id: Long, occurrenceAt: Long) {
        val show = PendingIntent.getActivity(context, id.toInt(), Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val operation = PendingIntent.getForegroundService(context, id.toInt(),
            alarmServiceIntent(context, id, occurrenceAt), PendingIntent.FLAG_IMMUTABLE)
        context.getSystemService(AlarmManager::class.java).setAlarmClock(
            AlarmManager.AlarmClockInfo(System.currentTimeMillis() + 5_000, show), operation)
    }

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
