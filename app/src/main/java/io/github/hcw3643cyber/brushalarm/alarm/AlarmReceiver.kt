// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.*

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val result = goAsync()
        val handoff = AlarmHandoff.acquire(context)
        CoroutineScope(Dispatchers.IO).launch {
            val id = intent.getLongExtra(EXTRA_ID, -1)
            val at = intent.getLongExtra(EXTRA_TRIGGER_AT, -1)
            var transferred = false
            try {
                val token = intent.getStringExtra(AlarmSessionScheduler.EXTRA_TOKEN)
                recordDelivery(context, "receiver_delivered", intent)
                val state = if (intent.action == AlarmSessionScheduler.ACTION_REPAIR_NORMAL) {
                    AlarmSessionCoordinator.reconcile(context, forceSchedule = true)
                } else if (intent.action in SESSION_ACTIONS) {
                    // Late actions cannot mutate or revive another session.
                    val current = AlarmSessionStore.read(context).current
                    if (token == null || token != current?.token) return@launch
                    if (intent.action == AlarmSessionScheduler.ACTION_FAST_RECOVERY)
                        AlarmSessionScheduler.cancelFastRecovery(context, token)
                    if (intent.action == AlarmSessionScheduler.ACTION_QUIET)
                        AlarmSessionCoordinator.quiet(context, token)
                    else AlarmSessionCoordinator.reconcile(context, forceSchedule = true)
                } else {
                    if (id < 0) return@launch
                    AlarmSessionCoordinator.receive(context, id, at)
                }
                if (state.current != null) transferred = AlarmSessionCoordinator.requestService(context, handoff)
                // Never open Room, cameras or an Activity before foreground handoff.
                AlarmSessionCoordinator.repairNormal(context)
            } catch (error: Exception) {
                AlarmSessionCoordinator.failure(context, "receiver_failed", error)
                if (intent.action !in SESSION_ACTIONS && intent.action != AlarmSessionScheduler.ACTION_REPAIR_NORMAL && id >= 0) {
                    runCatching { AlarmScheduler.retryOccurrence(context, id, at) }
                        .onFailure { AlarmSessionCoordinator.failure(context, "occurrence_retry_failed", it) }
                }
            } finally {
                if (!transferred) AlarmHandoff.release(handoff)
                result.finish()
            }
        }
    }
    companion object {
        const val EXTRA_ID = "alarm_id"
        const val EXTRA_TRIGGER_AT = "alarm_trigger_at"
        const val EXTRA_ELAPSED_AT = "alarm_elapsed_at"

        fun recordDelivery(context: Context, event: String, intent: Intent) {
            val at = intent.getLongExtra(EXTRA_TRIGGER_AT, -1)
            val elapsedAt = intent.getLongExtra(EXTRA_ELAPSED_AT, -1)
            val delay = when {
                elapsedAt >= 0 -> (android.os.SystemClock.elapsedRealtime() - elapsedAt).toString()
                at >= 0 -> (System.currentTimeMillis() - at).toString()
                else -> "unknown"
            }
            val power = context.getSystemService(android.os.PowerManager::class.java)
            AlarmDiagnosticLog.record(context, event, intent.getLongExtra(EXTRA_ID, -1),
                "action=${intent.action} token=${intent.getStringExtra(AlarmSessionScheduler.EXTRA_TOKEN)} " +
                    "at=$at elapsed_at=$elapsedAt delay_ms=$delay pid=${android.os.Process.myPid()} interactive=${power.isInteractive} " +
                    "idle=${power.isDeviceIdleMode} battery_unrestricted=${power.isIgnoringBatteryOptimizations(context.packageName)}")
        }
        private val SESSION_ACTIONS = setOf(AlarmSessionScheduler.ACTION_RECOVERY,
            AlarmSessionScheduler.ACTION_FAST_RECOVERY,
            AlarmSessionScheduler.ACTION_QUIET_END, AlarmSessionScheduler.ACTION_REBOOT_REMINDER,
            AlarmSessionScheduler.ACTION_QUIET)
    }
}
