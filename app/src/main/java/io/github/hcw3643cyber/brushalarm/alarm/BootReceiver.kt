// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

package io.github.hcw3643cyber.brushalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.UserManager
import io.github.hcw3643cyber.brushalarm.BrushAlarmApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in SUPPORTED_ACTIONS) return
        val unlocked = context.getSystemService(UserManager::class.java).isUserUnlocked
        val calendarChanged = intent.action == Intent.ACTION_TIME_CHANGED || intent.action == Intent.ACTION_TIMEZONE_CHANGED
        AlarmDiagnosticLog.record(
            context,
            event = "reschedule_broadcast",
            details = "action=${intent.action} unlocked=$unlocked"
        )
        val result = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Boot broadcasts only re-register real reminders; never launch mediaPlayback FGS here.
                AlarmSessionCoordinator.reconcile(context, forceSchedule = true)
                if (!unlocked) {
                    DirectBootAlarmStore.enabled(context).forEach {
                        runCatching { AlarmScheduler.restore(context, it, recomputeFuture = calendarChanged) }
                            .onFailure { AlarmSessionCoordinator.failure(context, "boot_normal_schedule_failed", it) }
                    }
                    return@launch
                }
                val dao = (context.applicationContext as BrushAlarmApp).database.alarms()
                val enabled = dao.enabled()
                // Room owns editable configuration. DP owns the latest registration occurrence.
                val merged = enabled.map { alarm ->
                    DirectBootAlarmStore.get(context, alarm.id)?.takeIf {
                        AlarmScheduler.sameConfiguration(it, alarm)
                    } ?: alarm.copy(nextTriggerAt = 0)
                }
                DirectBootAlarmStore.replaceAll(context, merged)
                enabled.forEach {
                    runCatching {
                        val result = AlarmScheduler.restore(context, it, recomputeFuture = calendarChanged)
                        dao.updateNextTrigger(it.id, result.triggerAt)
                    }.onFailure { AlarmSessionCoordinator.failure(context, "boot_normal_schedule_failed", it) }
                }
            } catch (error: Exception) {
                AlarmSessionCoordinator.failure(context, "reschedule_failed", error)
            } finally { result.finish() }
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"
        )
    }
}
