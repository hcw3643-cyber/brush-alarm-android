// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.hcw3643cyber.brushalarm.data.AlarmMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** All entry points serialize durable decisions and their idempotent system handoff here. */
object AlarmSessionCoordinator {
    private val mutex = Mutex()

    suspend fun receive(context: Context, id: Long, at: Long): SessionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val state = AlarmSessionStore.read(context)
            val alarm = DirectBootAlarmStore.get(context, id)
            val known = state.queue.any { it.occurrenceKey == "$id/$at" } ||
                state.completed.any { it.occurrenceKey == "$id/$at" }
            if (!known && (alarm == null || !alarm.enabled || (at >= 0 && alarm.nextTriggerAt != at))) {
                return@withLock repairLocked(context)
            }
            if (!known && alarm != null) {
                AlarmSessionStore.update(context) {
                    AlarmSessionCore.enqueue(it, AlarmSession(UUID.randomUUID().toString(), id, at,
                        alarm.mode == AlarmMode.ROOMMATE, alarm.label), AlarmSessionStore.time(context))
                }
            }
            repairLocked(context)
        }
    }

    suspend fun reconcile(context: Context, forceSchedule: Boolean = false): SessionState =
        withContext(Dispatchers.IO) { mutex.withLock { repairLocked(context, forceSchedule) } }

    suspend fun quiet(context: Context, token: String): SessionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            val before = AlarmSessionStore.read(context)
            val candidate = AlarmSessionCore.quiet(before, token, AlarmSessionStore.time(context))
            if (candidate != before) {
                // Do not publish silence unless the system already owns its real end reminder.
                AlarmSessionScheduler.armQuiet(context, candidate.current!!)
                try {
                    AlarmSessionStore.update(context) { candidate }
                } catch (failure: Exception) {
                    runCatching { AlarmSessionScheduler.cancelQuiet(context, token) }
                        .onFailure { failure(context, "quiet_rollback_cancel_failed", it) }
                    throw failure
                }
            }
            repairLocked(context)
        }
    }

    suspend fun complete(context: Context, token: String): SessionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            AlarmSessionStore.update(context) {
                AlarmSessionCore.complete(it, token, AlarmSessionStore.time(context))
            }
            repairLocked(context)
        }
    }

    suspend fun running(context: Context, token: String): SessionState = withContext(Dispatchers.IO) {
        mutex.withLock {
            AlarmSessionStore.update(context) { state ->
                val current = state.current
                if (current == null || current.token != token || current.rebootReminderElapsed == 0L) state else
                    state.copy(revision = state.revision + 1,
                        queue = listOf(current.copy(rebootReminderElapsed = 0)) + state.queue.drop(1))
            }
            repairLocked(context)
        }
    }

    private fun repairLocked(context: Context, forceSchedule: Boolean = false): SessionState {
        val state = AlarmSessionStore.update(context) {
            AlarmSessionCore.reconcile(it, AlarmSessionStore.time(context))
        }
        // Register active recovery before any slower normal-calendar work.
        runCatching { AlarmSessionScheduler.reconcile(context, state, forceSchedule) }
            .onFailure { failure(context, "session_schedule_failed", it) }
        return state
    }

    suspend fun repairNormal(context: Context) = withContext(Dispatchers.IO) { mutex.withLock {
        val state = AlarmSessionStore.read(context)
        state.pendingNext.forEach { (id, occurrence) ->
            runCatching {
                val alarm = DirectBootAlarmStore.get(context, id)
                if (alarm != null && alarm.enabled) {
                    val at = if (alarm.nextTriggerAt > occurrence) alarm.nextTriggerAt
                        else AlarmScheduler.nextTime(alarm.hour, alarm.minute, alarm.weekdays)
                    AlarmScheduler.schedule(context, alarm, at)
                }
                AlarmSessionStore.update(context) { latest ->
                    AlarmSessionCore.reconcile(latest.copy(revision = latest.revision + 1,
                        pendingNext = latest.pendingNext - id), AlarmSessionStore.time(context))
                }
            }.onFailure { failure(context, "next_schedule_failed", it) }
        }
        repairLocked(context)
    } }

    fun requestService(context: Context, handoffId: String? = null): Boolean {
        return runCatching {
            ContextCompat.startForegroundService(context,
                Intent(context, AlarmService::class.java).setAction(AlarmService.ACTION_RECOVER)
                    .putExtra(AlarmHandoff.EXTRA_ID, handoffId))
            AlarmDiagnosticLog.record(context, "foreground_service_requested", details = "handoff=$handoffId")
            true
        }.onFailure { failure(context, "service_request_failed", it); AlarmHandoff.release(handoffId) }.getOrDefault(false)
    }

    fun failure(context: Context, event: String, error: Throwable) {
        AlarmDiagnosticLog.record(context, event, details = "${error.javaClass.simpleName}: ${error.message}")
    }
}
