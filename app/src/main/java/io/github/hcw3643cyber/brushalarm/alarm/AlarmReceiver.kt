package io.github.hcw3643cyber.brushalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        val scheduledFor = intent.getLongExtra(EXTRA_TRIGGER_AT, -1)
        val delay = if (scheduledFor > 0) {
            System.currentTimeMillis() - scheduledFor
        } else {
            Long.MIN_VALUE
        }
        val source = when (intent.action) {
            AlarmScheduler.ACTION_ELAPSED_WATCHDOG -> "elapsed_watchdog"
            AlarmScheduler.ACTION_ALARM_CLOCK -> "alarm_clock"
            else -> "legacy"
        }
        if (!AlarmOccurrenceGate.claim(context, id, scheduledFor)) {
            AlarmDiagnosticLog.record(
                context,
                "receiver_duplicate_ignored",
                id,
                "source=$source scheduled_for=$scheduledFor delay_ms=$delay"
            )
            return
        }
        AlarmDiagnosticLog.record(
            context,
            "receiver_fired",
            id,
            "source=$source scheduled_for=$scheduledFor delay_ms=$delay"
        )
        // Re-arm the next occurrence before starting any app process work.
        // Even if an OEM later blocks/kills the foreground service, one failed
        // ringing attempt must not silently erase tomorrow's alarm.
        DirectBootAlarmStore.get(context, id)
            ?.takeIf { it.enabled }
            ?.let { AlarmScheduler.schedule(context, it) }
        val service = Intent(context, AlarmService::class.java)
            .setAction(AlarmService.ACTION_START)
            .putExtra(EXTRA_ID, id)
        ContextCompat.startForegroundService(context, service)
        AlarmDiagnosticLog.record(context, "foreground_service_requested", id)
    }
    companion object {
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_TRIGGER_AT = "alarm_trigger_at"
    }
}
