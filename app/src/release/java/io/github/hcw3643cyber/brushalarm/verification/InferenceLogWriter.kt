// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

package io.github.hcw3643cyber.brushalarm.verification

import android.content.Context

/**
 * Release implementation: inference diagnostics are unavailable and no file is
 * created. The matching Debug implementation contains the test-only logger.
 */
@Suppress("UNUSED_PARAMETER")
internal class InferenceLogWriter(context: Context) : AutoCloseable {
    fun markGroundTruth(brushing: Boolean) = Unit

    fun record(
        windowSpanMs: Double,
        sampleFps: Double,
        inferenceMs: Double,
        logit: Float,
        confidence: Float,
        decision: String,
        progress: Float
    ) = Unit

    override fun close() = Unit
}
