// v10
package com.example.multitimetracker.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
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
    onOpen: () -> Unit
) {
    val nameBg = color.copy(alpha = 0.35f)
    val secondaryAlpha = if (highlightRunning) 1.0f else 0.85f
    val sharedSuffix = if (sharedCount > 0) " • ⛓ $sharedCount" else ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen() }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            // Riga 1: "pill" col nome
            Text(
                text = tag.name,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .background(nameBg, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )

            // Riga 2: stato + shared
            Text(
                text = runningText + sharedSuffix,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .padding(start = 2.dp),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = secondaryAlpha)
            )
        }

        // Totale a destra
        Text(
            text = formatDuration(shownMs),
            style = MaterialTheme.typography.labelLarge
        )
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
