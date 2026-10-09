// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import io.github.hcw3643cyber.brushalarm.ui.VerificationActivity

/** Each operation owns a distinct, immutable session identity. No OnAlarmListener. */
object AlarmSessionScheduler {
    const val ACTION_RECOVERY = "brushalarm.SESSION_RECOVERY"
    const val ACTION_QUIET_END = "brushalarm.SESSION_QUIET_END"
    const val ACTION_REBOOT_REMINDER = "brushalarm.SESSION_REBOOT_REMINDER"
    const val ACTION_QUIET = "brushalarm.SESSION_QUIET"
    const val ACTION_REPAIR_NORMAL = "brushalarm.REPAIR_NORMAL_REGISTRATION"
    const val EXTRA_TOKEN = "session_token"
    private var lastScheduled: SessionState? = null

    fun reconcile(context: Context, state: SessionState, force: Boolean = false) {
        if (!force && lastScheduled == state) return
        val manager = context.getSystemService(AlarmManager::class.java)
        val normalRepairIntent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_REPAIR_NORMAL)
            .setData(Uri.parse("brushalarm://registration/normal"))
        if (state.pendingNext.isNotEmpty()) {
            val operation = PendingIntent.getBroadcast(context, 0, normalRepairIntent, PendingIntent.FLAG_IMMUTABLE)
            bestEffort(context, state.normalRepairAtElapsed, operation)
        } else {
            PendingIntent.getBroadcast(context, 0, normalRepairIntent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let { manager.cancel(it); it.cancel() }
        }
        // Completed records also make cancellation repeatable after a crash between write and cancel.
        (state.completed.map { it.token } + state.queue.drop(1).map { it.token }).forEach {
            cancel(context, it)
        }
        state.current?.let { current ->
            val time = AlarmSessionStore.time(context)
            val quietRemaining = current.quietRemaining(time)
            if (quietRemaining > 0) {
                alarmClock(context, current, ACTION_QUIET_END, System.currentTimeMillis() + quietRemaining)
            } else cancelAction(context, current.token, ACTION_QUIET_END)
            if (current.rebootReminderElapsed > 0) {
                alarmClock(context, current, ACTION_REBOOT_REMINDER,
                    System.currentTimeMillis() + (current.rebootReminderElapsed - time.elapsed).coerceAtLeast(1))
            } else cancelAction(context, current.token, ACTION_REBOOT_REMINDER)
            val recovery = pending(context, current.token, ACTION_RECOVERY)
            bestEffort(context, current.recoveryAtElapsed, recovery)
        }
        lastScheduled = state
        AlarmDiagnosticLog.record(context, "session_scheduled", state.current?.alarmId ?: -1,
            "token=${state.current?.token} revision=${state.revision}")
    }

    private fun bestEffort(context: Context, at: Long, operation: PendingIntent) {
        val manager = context.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT < 31 || manager.canScheduleExactAlarms()) {
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
                return
            } catch (failure: SecurityException) {
                AlarmSessionCoordinator.failure(context, "recovery_exact_access_revoked", failure)
            }
        }
        // This fallback is not an exact-alarm foreground-service exemption.
        manager.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, operation)
    }

    private fun alarmClock(context: Context, current: AlarmSession, action: String, at: Long) {
        val manager = context.getSystemService(AlarmManager::class.java)
        val show = PendingIntent.getActivity(context, 0,
            VerificationActivity.intent(context, current.alarmId, current.token),
            PendingIntent.FLAG_IMMUTABLE, VerificationActivity.pendingIntentOptions())
        manager.setAlarmClock(AlarmManager.AlarmClockInfo(at, show), pending(context, current.token, action))
    }

    fun armQuiet(context: Context, current: AlarmSession) = alarmClock(context, current, ACTION_QUIET_END,
        System.currentTimeMillis() + current.quietRemaining(AlarmSessionStore.time(context)))

    fun cancelQuiet(context: Context, token: String) = cancelAction(context, token, ACTION_QUIET_END)

    fun commandIntent(context: Context, token: String, action: String): Intent =
        Intent(context, AlarmReceiver::class.java).setAction(action)
            .setData(Uri.parse("brushalarm://session/$token/${Uri.encode(action)}"))
            .putExtra(EXTRA_TOKEN, token)

    fun pending(context: Context, token: String, action: String): PendingIntent =
        PendingIntent.getBroadcast(context, 0, commandIntent(context, token, action), PendingIntent.FLAG_IMMUTABLE)

    private fun cancelAction(context: Context, token: String, action: String) {
        PendingIntent.getBroadcast(context, 0, commandIntent(context, token, action),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it)
            it.cancel()
        }
    }

    fun cancel(context: Context, token: String) {
        listOf(ACTION_RECOVERY, ACTION_QUIET_END, ACTION_REBOOT_REMINDER, ACTION_QUIET).forEach {
            cancelAction(context, token, it)
        }
    }
}
