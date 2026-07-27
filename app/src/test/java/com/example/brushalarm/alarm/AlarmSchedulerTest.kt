package com.example.brushalarm.alarm

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class AlarmSchedulerTest {
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun fridayAlarmCanScheduleSaturday() {
        val friday = ZonedDateTime.of(2026, 7, 31, 23, 0, 0, 0, zone)
        val saturdayOnly = 1 shl 5
        val result = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(AlarmScheduler.nextTime(7, 30, saturdayOnly, friday)),
            zone
        )
        assertEquals(2026, result.year)
        assertEquals(8, result.monthValue)
        assertEquals(1, result.dayOfMonth)
        assertEquals(7, result.hour)
        assertEquals(30, result.minute)
    }

    @Test
    fun sundayAlarmWrapsToNextSunday() {
        val sundayAfterTime = ZonedDateTime.of(2026, 8, 2, 9, 0, 0, 0, zone)
        val sundayOnly = 1 shl 6
        val result = ZonedDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(
                AlarmScheduler.nextTime(8, 0, sundayOnly, sundayAfterTime)
            ), zone
        )
        assertEquals(9, result.dayOfMonth)
        assertEquals(java.time.DayOfWeek.SUNDAY, result.dayOfWeek)
    }
}
