package io.github.hcw3643cyber.brushalarm.data

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class AlarmMode { CONTINUOUS, ROOMMATE }

@Entity(tableName = "alarms")
data class AlarmEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val hour: Int,
    val minute: Int,
    /** Monday is bit 0, Sunday is bit 6. */
    val weekdays: Int = 0b1111111,
    val enabled: Boolean = true,
    val mode: AlarmMode = AlarmMode.CONTINUOUS,
    val label: String = "起床刷牙",
    val nextTriggerAt: Long = 0
)
