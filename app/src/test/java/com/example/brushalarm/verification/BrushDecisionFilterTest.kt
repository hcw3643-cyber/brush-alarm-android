package com.example.brushalarm.verification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
class BrushDecisionFilterTest {
    @Test
    fun isolatedConfidenceSpikeAddsOnlyOneInterval() {
        val filter = BrushDecisionFilter()
        val values = listOf(0f, 0f, 1f, 0f)
        var decision: BrushDecision? = null
        values.forEachIndexed { index, confidence -> decision = filter.update(confidence, index * 500L) }
        assertFalse(decision!!.brushing)
        assertFalse(decision!!.passed)
        assertTrue(decision!!.progress < .15f)
    }

    @Test
    fun uncertainConfidenceHoldsProgressWithoutGrowing() {
        val filter = BrushDecisionFilter()
        filter.update(.9f, 0)
        val positive = filter.update(.9f, 500)
        val uncertain = filter.update(.4f, 1_000)
        assertFalse(uncertain.brushing)
        assertFalse(uncertain.accumulating)
        assertTrue(positive.progress == uncertain.progress)
    }

    @Test
    fun lowConfidenceImmediatelyReversesEvidence() {
        val filter = BrushDecisionFilter()
        filter.update(.9f, 0)
        val positive = filter.update(.9f, 500)
        val low = filter.update(.05f, 1_000)
        assertFalse(low.accumulating)
        assertTrue(low.progress < positive.progress)
    }

    @Test
    fun sustainedBrushingPassesAfterThreeSecondsOfElapsedEvidence() {
        val filter = BrushDecisionFilter()
        var timestamp = 0L
        while (timestamp < 3_000L) {
            assertFalse(filter.update(.9f, timestamp).passed)
            timestamp += 500L
        }
        assertTrue(filter.update(.9f, timestamp).passed)
    }
}
