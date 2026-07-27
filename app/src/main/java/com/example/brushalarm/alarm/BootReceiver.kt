package com.example.brushalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.brushalarm.BrushAlarmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AlarmService.clearActiveAlarmState(context)
        }
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = (context.applicationContext as BrushAlarmApp).database.alarms()
                dao.enabled().forEach {
                    val result = AlarmScheduler.schedule(context, it)
                    dao.updateNextTrigger(it.id, result.triggerAt)
                }
            } finally { result.finish() }
        }
    }
}
