// v8
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag

@Composable
fun TagRow(
    color: Color,
    tag: Tag,
    shownMs: Long,
    runningText: String,
    highlightRunning: Boolean,
    sharedCount: Int,
    onOpen: () -> Unit
) {
    val runningBg = remember { Color(0xFFCCFFCC) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() },
        colors = if (highlightRunning) CardDefaults.cardColors(containerColor = runningBg) else CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                AssistChip(
                    onClick = { onOpen() },
                    label = { Text(tag.name, style = MaterialTheme.typography.titleMedium) },
                    colors = AssistChipDefaults.assistChipColors(containerColor = color.copy(alpha = 0.35f))
                )
                val sharedSuffix = if (sharedCount > 0) " • ⛓ $sharedCount" else ""
                Text(runningText + sharedSuffix, style = MaterialTheme.typography.labelMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatDuration(shownMs), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val totalMin = totalSec / 60
    val min = totalMin % 60
    val hours = totalMin / 60
    return "%02d:%02d:%02d".format(hours, min, sec)
}
