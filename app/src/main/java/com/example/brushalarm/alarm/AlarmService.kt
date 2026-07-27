package com.example.brushalarm.alarm

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.brushalarm.BrushAlarmApp
import com.example.brushalarm.R
import com.example.brushalarm.data.AlarmMode
import com.example.brushalarm.ui.VerificationActivity
import kotlinx.coroutines.*

class AlarmService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ringtone: Ringtone? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var alarmId = -1L
    private var mode = AlarmMode.CONTINUOUS
    private var repeatJob: Job? = null
    private var alarmLoaded = false
    private var alarmLoading = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_VERIFIED -> finishAlarm()
            ACTION_QUIET -> if (mode == AlarmMode.ROOMMATE) quietForOneMinute()
            ACTION_START -> beginAlarm(intent.getLongExtra(AlarmReceiver.EXTRA_ID, -1))
            null -> {
                val activeId = getSharedPreferences(STATE_FILE, MODE_PRIVATE)
                    .getLong(ACTIVE_ALARM_ID, -1)
                if (activeId >= 0) beginAlarm(activeId) else stopSelf()
            }
        }
        return START_STICKY
    }

    private fun beginAlarm(id: Long) {
        if (id < 0) {
            stopSelf()
            return
        }
        if (alarmId == id && (alarmLoading || alarmLoaded)) {
            if (alarmLoaded) ring()
            return
        }
        alarmId = id
        alarmLoading = true
        getSharedPreferences(STATE_FILE, MODE_PRIVATE)
            .edit().putLong(ACTIVE_ALARM_ID, id).apply()
        // A cold process must enter the foreground immediately. Room is opened
        // afterwards; waiting for it here can exceed Android's deadline.
        startForeground(NOTIFICATION_ID, notification("起床刷牙"))
        start(id)
    }

    private fun start(id: Long) {
        scope.launch {
            val alarm = withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms().get(id)
            } ?: run {
                alarmLoading = false
                clearActiveAlarm()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return@launch
            }
            mode = alarm.mode
            alarmLoading = false
            alarmLoaded = true
            // Refresh the placeholder with the configured label and roommate action.
            startForeground(NOTIFICATION_ID, notification(alarm.label))
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "BrushAlarm:ringing")
                .apply { acquire(30 * 60_000L) }
            ring()
            val scheduled = AlarmScheduler.schedule(this@AlarmService, alarm)
            withContext(Dispatchers.IO) {
                (application as BrushAlarmApp).database.alarms()
                    .updateNextTrigger(alarm.id, scheduled.triggerAt)
            }
        }
    }

    private fun ring() {
        if (ringtone?.isPlaying == true) return
        ringtone = RingtoneManager.getRingtone(
            this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        )?.apply {
            audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
            if (android.os.Build.VERSION.SDK_INT >= 28) isLooping = true
            play()
        }
    }

    private fun quietForOneMinute() {
        ringtone?.stop()
        repeatJob?.cancel()
        repeatJob = scope.launch {
            delay(60_000)
            ring()
        }
    }

    private fun finishAlarm() {
        clearActiveAlarm()
        ringtone?.stop()
        repeatJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun clearActiveAlarm() {
        getSharedPreferences(STATE_FILE, MODE_PRIVATE)
            .edit().remove(ACTIVE_ALARM_ID).apply()
    }

    private fun notification(label: String): Notification {
        val verify = PendingIntent.getActivity(
            this, 1,
            Intent(this, VerificationActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AlarmReceiver.EXTRA_ID, alarmId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val quietIntent = PendingIntent.getService(
            this, 2, Intent(this, AlarmService::class.java).setAction(ACTION_QUIET),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(label)
            .setContentText("完成刷牙验证后闹钟才会停止")
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setFullScreenIntent(verify, true)
            .addAction(0, "开始验证", verify)
            .apply { if (mode == AlarmMode.ROOMMATE) addAction(0, "安静 1 分钟", quietIntent) }
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "正在响铃", NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "刷牙闹钟响铃通知"
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    override fun onDestroy() {
        ringtone?.stop()
        repeatJob?.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        scope.cancel()
        super.onDestroy()
    }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "brushalarm.START"
        const val ACTION_QUIET = "brushalarm.QUIET"
        const val ACTION_VERIFIED = "brushalarm.VERIFIED"
        private const val CHANNEL_ID = "active_alarm"
        private const val NOTIFICATION_ID = 4201
        private const val STATE_FILE = "active_alarm_state"
        private const val ACTIVE_ALARM_ID = "active_alarm_id"

        fun activeAlarmId(context: Context): Long =
            context.getSharedPreferences(STATE_FILE, MODE_PRIVATE)
                .getLong(ACTIVE_ALARM_ID, -1)

        fun clearActiveAlarmState(context: Context) {
            context.getSharedPreferences(STATE_FILE, MODE_PRIVATE)
                .edit().remove(ACTIVE_ALARM_ID).apply()
        }
    }
}
