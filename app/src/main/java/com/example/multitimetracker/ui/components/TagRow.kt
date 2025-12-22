// v17
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag

/**
 * Riga tag in stile "lista compatta" (variante B: due righe).
 * Solo UI: nessuna logica/meccanismo viene cambiato.
 */
@Composable
fun TagRow(
    color: Color,
    tag: Tag,
    shownMs: Long,
    runningText: String,
    highlightRunning: Boolean,
    sharedCount: Int,
    showSeconds: Boolean = true,
    onOpen: () -> Unit
) {
    val nameBg = color.copy(alpha = 0.35f)
    // Slightly stronger than the app background so it's unmistakable.
    val runningBg = Color(0xFFCCFFCC)
    val secondaryAlpha = if (highlightRunning) 1.0f else 0.85f
    // Shows how many tasks (total) are associated with this tag.
    // Kept short to avoid visual noise.
    val sharedSuffix = if (sharedCount > 0) " • $sharedCount" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .background(if (highlightRunning) runningBg else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // Sinistra: nome + sotto-riga info (variante B)
        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(end = 88.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = tag.name + sharedSuffix,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = runningText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = secondaryAlpha)
            )
        }

        // Destra: durata totale
        Text(
            modifier = Modifier.align(Alignment.CenterEnd),
            text = formatDuration(shownMs, showSeconds, hideHoursIfZero),
            style = MaterialTheme.typography.labelLarge
        )
    }
    }
}

private fun formatDuration(ms: Long, showSeconds: Boolean, hideHoursIfZero: Boolean): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val totalMin = totalSec / 60
    val min = totalMin % 60
    val hours = totalMin / 60
    return if (showSeconds) {
        if (hideHoursIfZero && hours == 0L) {
            "${min}:${"%02d".format(sec)}"
        } else {
            "%02d:%02d:%02d".format(hours, min, sec)
        }
    } else {
        if (hideHoursIfZero && hours == 0L) {
            "${min}"
        } else {
            "%02d:%02d".format(hours, min)
        }
    }
}