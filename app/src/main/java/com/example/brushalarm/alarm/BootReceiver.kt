package com.example.brushalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import com.example.brushalarm.BrushAlarmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            AlarmService.clearActiveAlarmState(context)
        }
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        AlarmDiagnosticLog.record(
            context,
            event = "reschedule_broadcast",
            details = "action=${intent.action} unlocked=$unlocked"
        )
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!unlocked) {
                    DirectBootAlarmStore.enabled(context).forEach {
                        AlarmScheduler.schedule(context, it)
                    }
                    return@launch
                }
                val dao = (context.applicationContext as BrushAlarmApp).database.alarms()
                val enabled = dao.enabled()
                DirectBootAlarmStore.replaceAll(context, enabled)
                enabled.forEach {
                    val result = AlarmScheduler.schedule(context, it)
                    dao.updateNextTrigger(it.id, result.triggerAt)
                }
            } finally { result.finish() }
        }
    }
}
