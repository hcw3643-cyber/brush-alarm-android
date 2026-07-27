package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context
import io.github.hcw3643cyber.brushalarm.data.AlarmEntity
import io.github.hcw3643cyber.brushalarm.data.AlarmMode
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Minimal alarm mirror in device-encrypted storage.
 *
 * AlarmManager entries are cleared by a reboot, while the Room database is
 * credential-encrypted and unavailable until the first unlock. Keeping only
 * the fields required to ring here lets LOCKED_BOOT_COMPLETED restore alarms
 * without moving the main database or any inference data out of protected
 * storage.
 */
object DirectBootAlarmStore {
    private const val FILE = "direct_boot_alarms"
    private const val PREFIX = "alarm_"

    fun put(context: Context, alarm: AlarmEntity) {
        if (!alarm.enabled || alarm.id < 0) {
            remove(context, alarm.id)
            return
        }
        preferences(context).edit()
            .putString("$PREFIX${alarm.id}", encode(alarm))
            .commit()
    }

    fun get(context: Context, id: Long): AlarmEntity? {
        val encoded = preferences(context).getString("$PREFIX$id", null) ?: return null
        return decode(id, encoded)
    }

    fun enabled(context: Context): List<AlarmEntity> =
        preferences(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX) || value !is String) return@mapNotNull null
            val id = key.removePrefix(PREFIX).toLongOrNull() ?: return@mapNotNull null
            decode(id, value)?.takeIf { it.enabled }
        }

    fun remove(context: Context, id: Long) {
        preferences(context).edit().remove("$PREFIX$id").commit()
    }

    fun replaceAll(context: Context, alarms: List<AlarmEntity>) {
        val editor = preferences(context).edit().clear()
        alarms.filter { it.enabled }.forEach {
            editor.putString("$PREFIX${it.id}", encode(it))
        }
        editor.commit()
    }

    private fun preferences(context: Context) =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun encode(alarm: AlarmEntity): String {
        val label = Base64.getEncoder().encodeToString(
            alarm.label.toByteArray(StandardCharsets.UTF_8)
        )
        return listOf(
            alarm.hour,
            alarm.minute,
            alarm.weekdays,
            alarm.enabled,
            alarm.mode.name,
            alarm.nextTriggerAt,
            label
        ).joinToString("|")
    }

    private fun decode(id: Long, encoded: String): AlarmEntity? {
        return runCatching {
            val parts = encoded.split("|", limit = 7)
            AlarmEntity(
                id = id,
                hour = parts[0].toInt(),
                minute = parts[1].toInt(),
                weekdays = parts[2].toInt(),
                enabled = parts[3].toBooleanStrict(),
                mode = AlarmMode.valueOf(parts[4]),
                nextTriggerAt = parts[5].toLong(),
                label = String(Base64.getDecoder().decode(parts[6]), StandardCharsets.UTF_8)
            )
        }.getOrNull()
    }
}
