// v6
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.ui.theme.tagColorFromSeed

@Composable
fun TaskRow(
    tagColors: Map<Long, Color>,
    task: Task,
    tags: List<Tag>,
    nowMs: Long,
    onToggle: () -> Unit,
    onOpenHistory: () -> Unit,
    trailing: @Composable () -> Unit
) {
    val engine = TimeEngine()
    val shownMs = engine.displayMs(task.totalMs, task.lastStartedAtMs, nowMs)
    val taskTags = tags.filter { task.tagIds.contains(it.id) }

    Card(modifier = Modifier.fillMaxWidth().clickable { onOpenHistory() }) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(task.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (task.isRunning) "In corso" else "In pausa",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(onClick = onToggle) {
                        if (task.isRunning) {
                            Icon(Icons.Filled.Pause, contentDescription = "Pause")
                        } else {
                            Icon(Icons.Filled.PlayArrow, contentDescription = "Play")
                        }
                    }
                    trailing()
                }
            }

            Text(formatDuration(shownMs), style = MaterialTheme.typography.headlineSmall)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                taskTags.take(4).forEach { tag ->
                    val base = tagColors[tag.id] ?: remember(tag.id) { tagColorFromSeed(tag.id.toString()) }
                    val bg = base.copy(alpha = 0.35f)
                    AssistChip(
                        onClick = { },
                        label = { Text(tag.name) },
                        colors = AssistChipDefaults.assistChipColors(containerColor = bg)
                    )
                }
                if (taskTags.size > 4) {
                    AssistChip(onClick = { }, label = { Text("+${taskTags.size - 4}") })
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
