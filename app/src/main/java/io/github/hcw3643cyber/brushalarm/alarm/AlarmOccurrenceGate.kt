package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context

/**
 * Atomically claims an alarm occurrence in device-protected storage.
 *
 * AlarmScheduler deliberately registers two independent wake-up sources on
 * affected phones. Only the first delivery may start ringing.
 */
object AlarmOccurrenceGate {
    private const val FILE = "alarm_occurrence_gate"

    @Synchronized
    fun claim(context: Context, alarmId: Long, triggerAt: Long): Boolean {
        if (alarmId < 0) return false
        // Legacy alarms do not carry a trigger timestamp. They are kept
        // compatible, but cannot collide with the new dual registration.
        if (triggerAt < 0) return true
        val key = "last_$alarmId"
        val preferences = context.createDeviceProtectedStorageContext()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (preferences.getLong(key, Long.MIN_VALUE) == triggerAt) return false
        // Fail open: a storage error may cause a harmless duplicate service
        // start, but must never suppress the only wake-up that reached us.
        preferences.edit().putLong(key, triggerAt).commit()
        return true
    }
}
