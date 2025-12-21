// v11
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.ui.theme.tagColorFromSeed

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TaskRow(
    tagColors: Map<Long, Color>,
    task: Task,
    tags: List<Tag>,
    nowMs: Long,
    highlightRunning: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
    linkText: String,
    onOpenLink: () -> Unit
) {
    val engine = TimeEngine()
    var showTagsSheet by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    val shownMs = engine.displayMs(task.totalMs, task.lastStartedAtMs, nowMs)
    val taskTags = tags.filter { task.tagIds.contains(it.id) }

    val runningBg = remember { Color(0xFFCCFFCC) }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggle() },
                    onLongPress = { onLongPress() }
                )
            },
        colors = if (highlightRunning) {
            CardDefaults.cardColors(containerColor = runningBg)
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (task.isRunning) "In corso" else "In pausa",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }

            Text(formatDuration(shownMs), style = MaterialTheme.typography.headlineSmall)

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
            ) {
                if (linkText.isNotBlank()) {
                    AssistChip(
                        onClick = onOpenLink,
                        label = { Text(linkText) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    )
                }
                val maxInline = 2
                val visibleTags = taskTags.take(maxInline)
                val hiddenCount = (taskTags.size - visibleTags.size).coerceAtLeast(0)

                visibleTags.forEach { tag ->
                    val base = tagColors[tag.id] ?: remember(tag.id) { tagColorFromSeed(tag.id.toString()) }
                    val bg = base.copy(alpha = 0.35f)
                    AssistChip(
                        onClick = { },
                        label = { Text(tag.name) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = bg)
                    )
                }

                if (hiddenCount > 0) {
                    AssistChip(
                        onClick = { showTagsSheet = true },
                        label = { Text("+$hiddenCount") },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        )
                    )
                }
            }
        }


            if (showTagsSheet) {
                androidx.compose.material3.ModalBottomSheet(
                    onDismissRequest = { showTagsSheet = false }
                ) {
                    androidx.compose.foundation.layout.Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Tag del task", style = MaterialTheme.typography.titleMedium)

                        androidx.compose.foundation.lazy.LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(taskTags, key = { it.id }) { tag ->
                                val base = tagColors[tag.id] ?: remember(tag.id) { tagColorFromSeed(tag.id.toString()) }
                                val bg = base.copy(alpha = 0.35f)
                                AssistChip(
                                    onClick = { },
                                    label = { Text(tag.name) },
                                    colors = AssistChipDefaults.assistChipColors(containerColor = bg)
                                )
                            }
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
