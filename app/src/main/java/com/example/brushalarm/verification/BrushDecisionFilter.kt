package com.example.brushalarm.verification

import kotlin.math.max
import kotlin.math.min

internal data class BrushDecision(
    val progress: Float,
    val filteredConfidence: Float,
    val brushing: Boolean,
    val accumulating: Boolean,
    val passed: Boolean
)

/**
 * Converts a noisy sequence of model confidences into a time-based decision.
 *
 * A median filter removes isolated spikes, EMA reduces remaining jitter, and
 * hysteresis plus dwell times prevent rapid switching around one threshold.
 * Progress is measured in milliseconds so faster phones are not easier to pass.
 */
internal class BrushDecisionFilter {
    private val recent = ArrayDeque<Float>(MEDIAN_WINDOW)
    private var filtered = 0f
    private var initialized = false
    private var brushing = false
    private var highDurationMs = 0L
    private var lowDurationMs = 0L
    private var accumulatedMs = 0f
    private var lastTimestampMs: Long? = null

    fun update(confidence: Float, timestampMs: Long): BrushDecision {
        val bounded = confidence.coerceIn(0f, 1f)
        if (recent.size == MEDIAN_WINDOW) recent.removeFirst()
        recent.addLast(bounded)
        val median = median(recent)
        filtered = if (initialized) {
            EMA_ALPHA * median + (1f - EMA_ALPHA) * filtered
        } else {
            initialized = true
            median
        }

        val previousTimestamp = lastTimestampMs
        val elapsedMs = if (previousTimestamp == null) {
            0L
        } else {
            (timestampMs - previousTimestamp).coerceIn(0L, MAX_UPDATE_GAP_MS)
        }
        lastTimestampMs = timestampMs

        if (brushing) {
            lowDurationMs = if (filtered < EXIT_THRESHOLD) {
                lowDurationMs + elapsedMs
            } else {
                0L
            }
            if (lowDurationMs >= EXIT_DWELL_MS) {
                brushing = false
                lowDurationMs = 0L
                highDurationMs = 0L
            }
        } else {
            highDurationMs = if (filtered >= ENTER_THRESHOLD) {
                highDurationMs + elapsedMs
            } else {
                0L
            }
            if (highDurationMs >= ENTER_DWELL_MS) {
                brushing = true
                highDurationMs = 0L
                lowDurationMs = 0L
            }
        }

        // Hysteresis keeps the state stable, but it must not create evidence.
        // Stop progress on the first unsupported raw window even while the exit
        // debounce is still holding the visual "brushing" state.
        val accumulating = brushing && bounded >= CURRENT_EVIDENCE_THRESHOLD
        accumulatedMs = if (accumulating) {
            min(REQUIRED_BRUSHING_MS.toFloat(), accumulatedMs + elapsedMs)
        } else if (!brushing) {
            max(0f, accumulatedMs - elapsedMs * INACTIVE_DECAY_RATE)
        } else {
            accumulatedMs
        }
        return BrushDecision(
            progress = accumulatedMs / REQUIRED_BRUSHING_MS,
            filteredConfidence = filtered,
            brushing = brushing,
            accumulating = accumulating,
            passed = accumulatedMs >= REQUIRED_BRUSHING_MS
        )
    }

    private fun median(values: Collection<Float>): Float {
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2f
        }
    }

    private companion object {
        const val MEDIAN_WINDOW = 3
        const val EMA_ALPHA = .50f
        const val ENTER_THRESHOLD = .45f
        const val EXIT_THRESHOLD = .20f
        const val CURRENT_EVIDENCE_THRESHOLD = .30f
        const val ENTER_DWELL_MS = 500L
        const val EXIT_DWELL_MS = 1_400L
        const val REQUIRED_BRUSHING_MS = 3_000f
        const val INACTIVE_DECAY_RATE = .10f
        const val MAX_UPDATE_GAP_MS = 2_000L
    }
}
