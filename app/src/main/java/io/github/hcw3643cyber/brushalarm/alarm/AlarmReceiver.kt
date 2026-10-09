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
                AlarmDiagnosticLog.record(context, "receiver_delivered", id,
                    "action=${intent.action} token=$token at=$at delay_ms=${System.currentTimeMillis() - at}")
                val state = if (intent.action == AlarmSessionScheduler.ACTION_REPAIR_NORMAL) {
                    AlarmSessionCoordinator.reconcile(context, forceSchedule = true)
                } else if (intent.action in SESSION_ACTIONS) {
                    // Late actions cannot mutate or revive another session.
                    val current = AlarmSessionStore.read(context).current
                    if (token == null || token != current?.token) return@launch
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
        private val SESSION_ACTIONS = setOf(AlarmSessionScheduler.ACTION_RECOVERY,
            AlarmSessionScheduler.ACTION_QUIET_END, AlarmSessionScheduler.ACTION_REBOOT_REMINDER,
            AlarmSessionScheduler.ACTION_QUIET)
    }
}
