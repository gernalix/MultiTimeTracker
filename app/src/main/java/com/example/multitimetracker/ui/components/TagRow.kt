// v7
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag

data class GroupBorder(
    val enabled: Boolean,
    val isFirst: Boolean,
    val isLast: Boolean
)

@Composable
fun TagRow(
    color: Color,
    tag: Tag,
    shownMs: Long,
    runningText: String,
    highlightRunning: Boolean,
    sharedCount: Int,
    groupBorder: GroupBorder = GroupBorder(enabled = false, isFirst = false, isLast = false),
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val runningBg = remember { Color(0xFFCCFFCC) }

    val borderStrokePx = with(androidx.compose.ui.platform.LocalDensity.current) { 4.dp.toPx() }
    val borderColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!groupBorder.enabled) return@drawBehind

                // Draw a thick rectangle-like border around consecutive related tags.
                // Left/Right always, Top only for first item, Bottom only for last.
                val w = size.width
                val h = size.height

                // Left
                drawLine(
                    color = borderColor,
                    start = Offset(0f, 0f),
                    end = Offset(0f, h),
                    strokeWidth = borderStrokePx,
                    cap = StrokeCap.Square
                )
                // Right
                drawLine(
                    color = borderColor,
                    start = Offset(w, 0f),
                    end = Offset(w, h),
                    strokeWidth = borderStrokePx,
                    cap = StrokeCap.Square
                )

                if (groupBorder.isFirst) {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, 0f),
                        end = Offset(w, 0f),
                        strokeWidth = borderStrokePx,
                        cap = StrokeCap.Square
                    )
                }
                if (groupBorder.isLast) {
                    drawLine(
                        color = borderColor,
                        start = Offset(0f, h),
                        end = Offset(w, h),
                        strokeWidth = borderStrokePx,
                        cap = StrokeCap.Square
                    )
                }
            }
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onOpen() }
                .padding(if (groupBorder.enabled) 2.dp else 0.dp),
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
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Filled.Edit, contentDescription = "Edit tag")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete tag")
                    }
                }
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
