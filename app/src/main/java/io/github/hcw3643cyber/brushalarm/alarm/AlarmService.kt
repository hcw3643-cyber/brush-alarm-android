// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import io.github.hcw3643cyber.brushalarm.ui.VerificationActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterNotNull

class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var output: AlarmOutput
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeRenewAt = 0L
    private var foreground = false
    private var notificationRevision = -1L
    private var latest: SessionState? = null
    private var monitor: Job? = null
    private var appliedRevision = -1L

    override fun onCreate() {
        super.onCreate()
        output = AlarmOutput(this)
        AlarmDiagnosticLog.record(this, "service_created", details = "pid=${android.os.Process.myPid()}")
        val channel = NotificationChannel(CHANNEL_ID, "正在响铃", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "刷牙闹钟响铃通知"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            setSound(null, null)
            enableVibration(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        scope.launch {
            AlarmSessionStore.states.filterNotNull().collect { state ->
                if (foreground) applyState(state)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Even a stale cold command must meet the foreground start deadline.
        try {
            // First notification must already carry FSI on API 26/27; an update may not launch it.
            startForeground(NOTIFICATION_ID, notification(AlarmSessionStore.states.value ?: latest))
            foreground = true
            if (acquireOrRenewWakeLock(android.os.SystemClock.elapsedRealtime()))
                AlarmHandoff.release(intent?.getStringExtra(AlarmHandoff.EXTRA_ID))
            AlarmDiagnosticLog.record(this, "foreground_promoted", details = "wake_held=${wakeLock?.isHeld}")
        } catch (error: Exception) {
            AlarmSessionCoordinator.failure(this, "start_foreground_failed", error)
            AlarmHandoff.release(intent?.getStringExtra(AlarmHandoff.EXTRA_ID))
            output.stop()
            releaseWakeLock()
            stopSelf()
            return START_NOT_STICKY
        }
        // Foreground promotion precedes diagnostic file IO and async state work.
        intent?.let { AlarmReceiver.recordDelivery(this, "service_delivered", it) }
        AlarmDiagnosticLog.record(this, "service_start_command", details = "action=${intent?.action}")
        scope.launch {
            try {
                val token = intent?.getStringExtra(AlarmSessionScheduler.EXTRA_TOKEN)
                val state = when (intent?.action) {
                    AlarmScheduler.ACTION_ALARM_CLOCK -> {
                        val id = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1)
                        val at = intent.getLongExtra(AlarmReceiver.EXTRA_TRIGGER_AT, -1)
                        if (id >= 0 && at >= 0) AlarmSessionCoordinator.receive(this@AlarmService, id, at)
                        else AlarmSessionCoordinator.reconcile(this@AlarmService)
                    }
                    AlarmSessionScheduler.ACTION_FAST_RECOVERY -> {
                        token?.let { AlarmSessionScheduler.cancelFastRecovery(this@AlarmService, it) }
                        AlarmSessionCoordinator.reconcile(this@AlarmService, forceSchedule = true)
                    }
                    AlarmSessionScheduler.ACTION_RECOVERY,
                    AlarmSessionScheduler.ACTION_QUIET_END,
                    AlarmSessionScheduler.ACTION_REBOOT_REMINDER ->
                        AlarmSessionCoordinator.reconcile(this@AlarmService, forceSchedule = true)
                    ACTION_QUIET -> if (token != null) AlarmSessionCoordinator.quiet(this@AlarmService, token)
                        else AlarmSessionCoordinator.reconcile(this@AlarmService)
                    ACTION_VERIFIED -> if (token != null) AlarmSessionCoordinator.complete(this@AlarmService, token)
                        else AlarmSessionCoordinator.reconcile(this@AlarmService)
                    else -> AlarmSessionCoordinator.reconcile(this@AlarmService)
                }
                applyState(state)
                if (state.current != null && monitor?.isActive != true) startMonitor()
                runCatching { AlarmSessionCoordinator.repairNormal(this@AlarmService) }
                    .onFailure { AlarmSessionCoordinator.failure(this@AlarmService, "service_next_schedule_failed", it) }
            } catch (error: Exception) {
                AlarmSessionCoordinator.failure(this@AlarmService, "service_reconcile_failed", error)
                if (intent?.action == AlarmScheduler.ACTION_ALARM_CLOCK) {
                    val id = intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1)
                    val at = intent.getLongExtra(AlarmReceiver.EXTRA_TRIGGER_AT, -1)
                    if (id >= 0 && at >= 0) runCatching { AlarmScheduler.retryOccurrence(this@AlarmService, id, at) }
                        .onFailure { AlarmSessionCoordinator.failure(this@AlarmService, "service_occurrence_retry_failed", it) }
                }
                output.stop()
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                foreground = false
                stopSelf()
            }
        }
        return START_STICKY
    }

    private fun startMonitor() {
        monitor = scope.launch {
            while (isActive) {
                try {
                    val state = AlarmSessionCoordinator.reconcile(this@AlarmService)
                    applyState(state)
                    val current = state.current ?: break
                    AlarmSessionCoordinator.running(this@AlarmService, current.token)
                    // Normal calendar/database work must never precede foreground/output handoff.
                    AlarmSessionCoordinator.repairNormal(this@AlarmService)
                } catch (error: Exception) {
                    AlarmSessionCoordinator.failure(this@AlarmService, "active_reconcile_failed", error)
                }
                delay(500)
            }
        }
    }

    private fun applyState(state: SessionState) {
        val published = AlarmSessionStore.states.value
        if (state.revision < appliedRevision || (published != null && state.revision < published.revision)) return
        appliedRevision = state.revision
        latest = state
        val current = state.current
        if (current == null) {
            output.stop()
            releaseWakeLock()
            monitor?.cancel()
            monitor = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            foreground = false
            stopSelf()
            return
        }
        val time = AlarmSessionStore.time(this)
        output.update(current, time)
        acquireOrRenewWakeLock(time.elapsed)
        if (notificationRevision != state.revision) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(state))
            notificationRevision = state.revision
        }
    }

    private fun acquireOrRenewWakeLock(elapsed: Long): Boolean {
        return runCatching {
            if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrushAlarm:active_session")
                .apply { setReferenceCounted(false) }
            if (wakeLock?.isHeld != true || elapsed >= wakeRenewAt) {
                wakeLock?.acquire(5 * 60_000L)
                wakeRenewAt = elapsed + 4 * 60_000L
            }
            wakeLock?.isHeld == true
        }.onFailure { AlarmSessionCoordinator.failure(this, "wake_lock_failed", it) }.getOrDefault(false)
    }

    private fun releaseWakeLock() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
            .onFailure { AlarmSessionCoordinator.failure(this, "wake_unlock_failed", it) }
        wakeLock = null
        wakeRenewAt = 0
    }

    private fun notification(state: SessionState?): Notification {
        val current = state?.current
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(current?.label ?: "正在恢复起床提醒")
            .setContentText(if (current != null && current.quietRemaining(AlarmSessionStore.time(this)) > 0)
                "已使用本次静音，1 分钟结束后恢复提醒" else "完成刷牙验证后闹钟才会停止")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true).setOngoing(true)
        val verifyIntent = current?.let { VerificationActivity.intent(this, it.alarmId, it.token) }
            ?: VerificationActivity.restoreIntent(this)
        val verify = PendingIntent.getActivity(this, 0, verifyIntent, PendingIntent.FLAG_IMMUTABLE,
            VerificationActivity.pendingIntentOptions())
        builder.setContentIntent(verify).setFullScreenIntent(verify, true).addAction(0, "开始验证", verify)
        current?.let {
            if (it.roommate && !it.quietUsed) builder.addAction(0, "安静 1 分钟（仅一次）",
                AlarmSessionScheduler.pending(this, it.token, AlarmSessionScheduler.ACTION_QUIET))
        }
        return builder.build()
    }

    private fun armInterruptionRecovery(reason: String) {
        val state = AlarmSessionStore.states.value ?: latest ?: return
        runCatching { AlarmSessionScheduler.armInterruptionRecovery(this, state, reason) }
            .onFailure { AlarmSessionCoordinator.failure(this, "interruption_recovery_failed", it) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Register immediately, rather than launching a coroutine in a scope the OS may kill.
        armInterruptionRecovery("task_removed")
        AlarmDiagnosticLog.record(this, "service_task_removed", details =
            "pid=${android.os.Process.myPid()} token=${(AlarmSessionStore.states.value ?: latest)?.current?.token}")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // Completed state has no current task, so intentional completion never rearms.
        armInterruptionRecovery("service_destroyed")
        output.stop()
        releaseWakeLock()
        scope.cancel()
        AlarmDiagnosticLog.record(this, "service_destroyed", details = "pid=${android.os.Process.myPid()} token=${(AlarmSessionStore.states.value ?: latest)?.current?.token}")
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "brushalarm.START"
        const val ACTION_RECOVER = "brushalarm.RECOVER"
        const val ACTION_QUIET = "brushalarm.QUIET"
        const val ACTION_VERIFIED = "brushalarm.VERIFIED"
        private const val CHANNEL_ID = "active_alarm_sessions"
        private const val NOTIFICATION_ID = 4201

        fun activeAlarmId(context: Context): Long =
            runCatching { AlarmSessionStore.read(context).current?.alarmId ?: -1 }
                .onFailure { AlarmSessionCoordinator.failure(context, "active_read_failed", it) }.getOrDefault(-1)
    }
}
