package com.example.brushalarm.verification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class BrushDecisionFilterTest {
    @Test
    fun isolatedConfidenceSpikeDoesNotPass() {
        val filter = BrushDecisionFilter()
        val values = listOf(0f, 0f, 1f, 0f, 0f, 0f, 0f)
        var decision: BrushDecision? = null
        values.forEachIndexed { index, confidence ->
            decision = filter.update(confidence, index * 400L)
        }
        assertFalse(decision!!.brushing)
        assertFalse(decision!!.passed)
        assertTrue(decision!!.progress < .15f)
    }

    @Test
    fun briefLowConfidenceDoesNotDropBrushingState() {
        val filter = BrushDecisionFilter()
        var timestamp = 0L
        repeat(6) {
            filter.update(.9f, timestamp)
            timestamp += 400
        }
        val firstLow = filter.update(.05f, timestamp)
        timestamp += 400
        val secondLow = filter.update(.05f, timestamp)
        assertTrue(firstLow.brushing)
        assertTrue(secondLow.brushing)
        assertFalse(firstLow.accumulating)
        assertFalse(secondLow.accumulating)
        assertTrue(abs(firstLow.progress - secondLow.progress) < .0001f)
    }

    @Test
    fun sustainedBrushingPassesUsingElapsedTime() {
        val fast = completionTime(stepMs = 250)
        val slow = completionTime(stepMs = 600)
        assertTrue(fast in 3_000L..5_000L)
        assertTrue(slow in 3_000L..5_500L)
        assertTrue(abs(fast - slow) <= 750L)
    }

    private fun completionTime(stepMs: Long): Long {
        val filter = BrushDecisionFilter()
        var timestamp = 0L
        while (timestamp <= 8_000L) {
            if (filter.update(.9f, timestamp).passed) return timestamp
            timestamp += stepMs
        }
        error("Sustained brushing did not pass")
    }
}
