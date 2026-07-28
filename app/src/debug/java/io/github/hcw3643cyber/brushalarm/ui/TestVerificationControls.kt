// SPDX-FileCopyrightText: 2026 Leo Huang
// SPDX-License-Identifier: GPL-3.0-only

package io.github.hcw3643cyber.brushalarm.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun TestVerificationControls(
    label: String,
    onBrushing: () -> Unit,
    onStopped: () -> Unit
) {
    Text(
        "测试标签：$label",
        color = Color.White.copy(alpha = .75f),
        modifier = Modifier.padding(top = 12.dp)
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        OutlinedButton(
            onClick = onBrushing,
            modifier = Modifier.weight(1f)
        ) { Text("标记开始刷牙") }
        OutlinedButton(
            onClick = onStopped,
            modifier = Modifier.weight(1f)
        ) { Text("标记已停止") }
    }
}
