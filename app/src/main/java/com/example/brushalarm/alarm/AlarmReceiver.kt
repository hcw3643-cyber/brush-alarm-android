package com.example.brushalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra(EXTRA_ID, -1)
        AlarmDiagnosticLog.record(context, "receiver_fired", id)
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
    companion object { const val EXTRA_ID = "alarm_id" }
}
