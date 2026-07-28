// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

package io.github.hcw3643cyber.brushalarm

import android.widget.Toast
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.hcw3643cyber.brushalarm.alarm.AlarmDiagnosticLog
import io.github.hcw3643cyber.brushalarm.verification.InferenceLogFiles

@Composable
internal fun TestHomeControls(activity: MainActivity) {
    TextButton(
        onClick = {
            if (!InferenceLogFiles.shareLatest(activity)) {
                Toast.makeText(
                    activity,
                    "还没有可导出的推理日志",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        contentPadding = PaddingValues(0.dp)
    ) { Text("导出最近一次识别日志（测试版）") }
    TextButton(
        onClick = {
            if (!AlarmDiagnosticLog.share(activity)) {
                Toast.makeText(
                    activity,
                    "还没有闹钟诊断日志",
                    Toast.LENGTH_SHORT
                ).show()
            }
        },
        contentPadding = PaddingValues(0.dp),
        modifier = Modifier.padding(bottom = 12.dp)
    ) { Text("导出闹钟诊断日志（测试版）") }
}
