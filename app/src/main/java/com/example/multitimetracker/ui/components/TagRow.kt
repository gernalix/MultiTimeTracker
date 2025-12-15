// v1
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeEngine

@Composable
fun TagRow(tag: Tag, nowMs: Long) {
    val engine = TimeEngine()
    val shownMs = engine.displayMs(tag.totalMs, tag.lastStartedAtMs, nowMs)
    val running = tag.activeChildrenCount > 0

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(14.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tag.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (running) "In corso • ${tag.activeChildrenCount} task" else "In pausa",
                    style = MaterialTheme.typography.labelMedium
                )
            }
            Text(formatDuration(shownMs), style = MaterialTheme.typography.titleMedium)
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
