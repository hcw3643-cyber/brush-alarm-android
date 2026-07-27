package com.example.brushalarm.verification

internal data class BrushDecision(
    val progress: Float,
    val confidence: Float,
    val brushing: Boolean,
    val accumulating: Boolean,
    val passed: Boolean
)

/**
 * Converts overlapping model windows into a time-based evidence bucket.
 *
 * The model already sees an overlapping 1.875-second window every 0.5 seconds,
 * so adding another median/EMA window here would mostly add latency. A high
 * result grows progress, a low result removes weak evidence, and an uncertain
 * result holds it. Progress uses elapsed time so phone speed cannot change the
 * amount of brushing required.
 */
internal class BrushDecisionFilter {
    private var accumulatedMs = 0f
    private var lastTimestampMs: Long? = null

    fun update(confidence: Float, timestampMs: Long): BrushDecision {
        val bounded = confidence.coerceIn(0f, 1f)
        val previousTimestamp = lastTimestampMs
        val elapsedMs = if (previousTimestamp == null) {
            0L
        } else {
            (timestampMs - previousTimestamp).coerceIn(0L, MAX_UPDATE_GAP_MS)
        }
        lastTimestampMs = timestampMs

        val accumulating = bounded >= HIGH_THRESHOLD
        val contradicted = bounded <= LOW_THRESHOLD
        accumulatedMs = when {
            accumulating -> (accumulatedMs + elapsedMs)
                .coerceAtMost(REQUIRED_BRUSHING_MS)
            contradicted -> (accumulatedMs - elapsedMs * LOW_EVIDENCE_DECAY_RATE)
                .coerceAtLeast(0f)
            else -> accumulatedMs
        }
        return BrushDecision(
            progress = accumulatedMs / REQUIRED_BRUSHING_MS,
            confidence = bounded,
            brushing = accumulating,
            accumulating = accumulating,
            passed = accumulatedMs >= REQUIRED_BRUSHING_MS
        )
    }

    companion object {
        const val HIGH_THRESHOLD = .70f
        const val LOW_THRESHOLD = .10f
        const val REQUIRED_BRUSHING_MS = 6_000f
        // REQUIRED_BRUSHING_MS doubled to slow positive progress. A 1.0 decay
        // preserves the previous percentage drop for contradicted windows.
        const val LOW_EVIDENCE_DECAY_RATE = 1.0f
        private const val MAX_UPDATE_GAP_MS = 750L
    }
}
