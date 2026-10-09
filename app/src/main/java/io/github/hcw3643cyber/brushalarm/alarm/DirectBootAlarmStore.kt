// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only
package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context
import io.github.hcw3643cyber.brushalarm.data.AlarmEntity
import io.github.hcw3643cyber.brushalarm.data.AlarmMode
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Device-protected mirror of editable configuration and the normal registration intent.
 * Active/quiet/completed sessions live solely in AlarmSessionStore.
 */
object DirectBootAlarmStore {
    private const val FILE = "direct_boot_alarms"
    private const val PREFIX = "alarm_"
    private var committed: Map<Long, AlarmEntity>? = null

    @Synchronized
    fun put(context: Context, alarm: AlarmEntity) {
        if (!alarm.enabled || alarm.id < 0) { remove(context, alarm.id); return }
        write(context, read(context) + (alarm.id to alarm))
    }

    @Synchronized
    fun get(context: Context, id: Long): AlarmEntity? = read(context)[id]

    @Synchronized
    fun enabled(context: Context): List<AlarmEntity> = read(context).values.filter { it.enabled }

    @Synchronized
    fun remove(context: Context, id: Long) = write(context, read(context) - id)

    @Synchronized
    fun replaceAll(context: Context, alarms: List<AlarmEntity>) =
        write(context, alarms.filter { it.enabled }.associateBy { it.id })

    private fun read(context: Context): Map<Long, AlarmEntity> {
        committed?.let { return it }
        val loaded = preferences(context).all.mapNotNull { (key, value) ->
            if (!key.startsWith(PREFIX)) return@mapNotNull null
            check(value is String) { "Invalid normal alarm snapshot" }
            val id = key.removePrefix(PREFIX).toLong()
            id to decode(id, value)
        }.toMap()
        committed = loaded
        return loaded
    }

    private fun write(context: Context, next: Map<Long, AlarmEntity>) {
        if (next == read(context)) return
        val editor = preferences(context).edit().clear()
        next.forEach { (id, alarm) -> editor.putString("$PREFIX$id", encode(alarm)) }
        check(editor.commit()) { "Unable to persist normal alarm registration" }
        // Never read back a failed SharedPreferences write from its modified memory cache.
        committed = next
    }

    private fun preferences(context: Context) = context.createDeviceProtectedStorageContext()
        .getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun encode(alarm: AlarmEntity): String {
        val label = Base64.getEncoder().encodeToString(alarm.label.toByteArray(StandardCharsets.UTF_8))
        return listOf(alarm.hour, alarm.minute, alarm.weekdays, alarm.enabled, alarm.mode.name,
            alarm.nextTriggerAt, label).joinToString("|")
    }

    private fun decode(id: Long, encoded: String): AlarmEntity {
        val parts = encoded.split("|", limit = 7)
        return AlarmEntity(id = id, hour = parts[0].toInt(), minute = parts[1].toInt(),
            weekdays = parts[2].toInt(), enabled = parts[3].toBooleanStrict(),
            mode = AlarmMode.valueOf(parts[4]), nextTriggerAt = parts[5].toLong(),
            label = String(Base64.getDecoder().decode(parts[6]), StandardCharsets.UTF_8))
    }
}
