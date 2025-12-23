// v24
@file:OptIn(ExperimentalLayoutApi::class)
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeEngine

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TaskRow(
    tagColors: Map<Long, Color>,
    task: Task,
    tags: List<Tag>,
    nowMs: Long,
    highlightRunning: Boolean,
    showTime: Boolean = true,
    showSeconds: Boolean = true,
    hideHoursIfZero: Boolean = false,
    showTags: Boolean = true,
    highlightJustCreated: Boolean = false,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    linkText: String,
    onOpenLink: () -> Unit
) {
    val engine = TimeEngine()
    val shownMs = engine.displayMs(task.totalMs, task.lastStartedAtMs, nowMs)
    val taskTags = tags.filter { task.tagIds.contains(it.id) }

    var showAllTags by remember { mutableStateOf(false) }

    val runningBg = remember { Color(0xFFCCFFCC) }
    val bg = if (highlightRunning) runningBg else Color.Transparent

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onLongPress = { onLongPress() }
                )
            },
        color = bg,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        // Compact, dense 2-column layout: main text left, metadata right.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LEFT: title + small status/link line (2 lines total)
            // Give more space to the right side so we can show multiple tag chips.
            Column(
                modifier = Modifier.fillMaxWidth(0.55f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = task.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val statusEmoji = if (task.isRunning) "▶️" else ""
                    Text(
                        text = statusEmoji,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
if (linkText.isNotBlank()) {
                        IconButton(
                            onClick = onOpenLink,
                            modifier = Modifier.size(26.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.KeyboardArrowRight,
                                contentDescription = "Open link",
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // RIGHT: time + tags (compact)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (showTime) {
                    Text(
                        text = formatDuration(shownMs, showSeconds, hideHoursIfZero),
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1
                    )
                }

                if (showTags) {
                    CompactTagLine(
                    tags = taskTags,
                    tagColors = tagColors,
                    // We show more than one chip by default; overflow still goes to the bottom sheet.
                    maxVisible = 8,
                    onOverflowClick = { showAllTags = true }
                )
                }
            }
        }
    }

    if (showAllTags) {
        ModalBottomSheet(
            onDismissRequest = { showAllTags = false }
        ) {
            Text(
                text = "Tags",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                taskTags.forEach { tag ->
                    CompactTagChip(
                        label = tag.name,
                        color = tagColors[tag.id] ?: MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CompactTagLine(
    tags: List<Tag>,
    tagColors: Map<Long, Color>,
    maxVisible: Int,
    onOverflowClick: () -> Unit
) {
    val visible = tags.take(maxVisible)
    val overflow = tags.size - visible.size

    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        visible.forEach { tag ->
            CompactTagChip(
                label = tag.name,
                color = tagColors[tag.id] ?: MaterialTheme.colorScheme.surfaceVariant
            )
        }
        if (overflow > 0) {
            // Small translucent +N chip
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .pointerInput(Unit) {
                        detectTapGestures(onTap = { onOverflowClick() })
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "+$overflow",
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun CompactTagChip(
    label: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.35f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}


private fun formatDuration(ms: Long, showSeconds: Boolean, hideHoursIfZero: Boolean): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L

    val parts = mutableListOf<String>()

    if (!hideHoursIfZero || hours > 0L) {
        if (hours > 0L) parts.add("${hours}h")
    }

    val showMinutes = (hours > 0L) || (minutes > 0L)
    if (showMinutes) {
        parts.add("${minutes}m")
    }

    if (showSeconds) {
        val showSecondsPart = seconds > 0L || parts.isEmpty()
        if (showSecondsPart) {
            parts.add("${seconds}s")
        }
    }

    if (!showSeconds && parts.isEmpty()) {
        parts.add("0m")
    }

    return parts.joinToString(" ")
}