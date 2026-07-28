// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

package io.github.hcw3643cyber.brushalarm.alarm

import android.content.Context

/**
 * Release implementation: alarm diagnostics are unavailable and no file is
 * created. The matching Debug implementation contains the test-only logger.
 */
@Suppress("UNUSED_PARAMETER")
object AlarmDiagnosticLog {
    fun record(
        context: Context,
        event: String,
        alarmId: Long = -1,
        details: String = ""
    ) = Unit
}
