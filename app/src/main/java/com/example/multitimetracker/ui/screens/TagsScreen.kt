// v11
package com.example.multitimetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.ui.components.AddOrRenameTagDialog
import com.example.multitimetracker.ui.components.TagRow
import com.example.multitimetracker.ui.theme.assignDistinctTagColors
import com.example.multitimetracker.ui.util.formatDuration

@Composable
fun TagsScreen(
    modifier: Modifier = Modifier,
    state: UiState,
    onAddTag: (String) -> Unit,
    onRenameTag: (Long, String) -> Unit,
    onDeleteTag: (Long) -> Unit,
    onRestoreTag: (Long) -> Unit,
    onPurgeTag: (Long) -> Unit,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean
) {
    var showAdd by remember { mutableStateOf(false) }
    var renameTagId by remember { mutableStateOf<Long?>(null) }
    var openedTagId by remember { mutableStateOf<Long?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var confirmDeleteTagId by remember { mutableStateOf<Long?>(null) }
    var confirmPurgeTagId by remember { mutableStateOf<Long?>(null) }

    val visibleTags = remember(state.tags) {
        if (showTrash) state.tags.filter { it.isDeleted } else state.tags.filter { !it.isDeleted }
    }

    val colors = remember(state.tags) {
        // Assign colors only to non-deleted tags (deleted can reuse colors too, harmless).
        assignDistinctTagColors(state.tags.filter { !it.isDeleted })
    }

    Column(modifier = modifier.fillMaxSize()) {
        // Header row (kept simple & readable)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (showTrash) "Tag nel cestino" else "Tag",
                style = MaterialTheme.typography.titleMedium
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { showTrash = !showTrash }) {
                    Icon(
                        imageVector = if (showTrash) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (showTrash) "Nascondi cestino" else "Mostra cestino"
                    )
                }
                IconButton(onClick = { showAdd = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Aggiungi tag")
                }
            }
        }

        Divider()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                items(visibleTags, key = { it.id }) { tag ->
                    val isRunning = tag.lastStartedAtMs != null || state.activeTagStart.containsKey(tag.id)
                    val color = colors[tag.id]

                    Column(modifier = Modifier.fillMaxWidth()) {
                        TagRow(
                            color = color ?: MaterialTheme.colorScheme.primary,
                            tag = tag,
                            shownMs = computeShownMs(tag, state.nowMs),
                            runningText = if (isRunning) "🟢" else "",
                            highlightRunning = isRunning,
                            sharedCount = tag.activeChildrenCount,
                            showSeconds = showSeconds,
                            hideHoursIfZero = hideHoursIfZero,
                            onOpen = {
                                openedTagId = if (openedTagId == tag.id) null else tag.id
                            }
                        )

                        if (openedTagId == tag.id) {
                            // Simple expanded area: actions + task list using this tag
                            val linkedTasks = state.tasks
                                .asSequence()
                                .filter { !it.isDeleted }
                                .filter { it.tagIds.contains(tag.id) }
                                .toList()

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AssistChip(
                                    onClick = { renameTagId = tag.id },
                                    label = { Text("Rinomina") }
                                )

                                if (!tag.isDeleted) {
                                    AssistChip(
                                        onClick = { confirmDeleteTagId = tag.id },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        label = { Text("Elimina") }
                                    )
                                } else {
                                    AssistChip(
                                        onClick = { onRestoreTag(tag.id) },
                                        leadingIcon = { Icon(Icons.Default.RestoreFromTrash, contentDescription = null) },
                                        label = { Text("Ripristina") }
                                    )
                                    AssistChip(
                                        onClick = { confirmPurgeTagId = tag.id },
                                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                                        label = { Text("Purge") }
                                    )
                                }
                            }

                            if (linkedTasks.isNotEmpty()) {
                                Text(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    text = "Task con questo tag:",
                                    style = MaterialTheme.typography.labelMedium
                                )
                                linkedTasks.forEach { t ->
                                    Text(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                                        text = "• ${t.name}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Text(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                                    text = "Nessun task associato.",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }

                            Divider(modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }

    if (showAdd) {
        AddOrRenameTagDialog(
            title = "Aggiungi tag",
            initialName = "",
            confirmText = "Aggiungi",
            onDismiss = { showAdd = false },
            onConfirm = { name ->
                onAddTag(name)
                showAdd = false
            }
        )
    }

    renameTagId?.let { id ->
        val tag = state.tags.firstOrNull { it.id == id }
        if (tag != null) {
            AddOrRenameTagDialog(
                title = "Rinomina tag",
                initialName = tag.name,
                confirmText = "Salva",
                onDismiss = { renameTagId = null },
                onConfirm = { name ->
                    onRenameTag(id, name)
                    renameTagId = null
                }
            )
        } else {
            renameTagId = null
        }
    }

    confirmDeleteTagId?.let { id ->
        val t = state.tags.firstOrNull { it.id == id }
        if (t != null) {
            AlertDialog(
                onDismissRequest = { confirmDeleteTagId = null },
                title = { Text("Eliminare tag?") },
                text = { Text("Vuoi eliminare '${t.name}'?") },
                confirmButton = {
                    TextButton(onClick = {
                        onDeleteTag(id)
                        confirmDeleteTagId = null
                    }) { Text("Elimina") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmDeleteTagId = null }) { Text("Annulla") }
                }
            )
        } else {
            confirmDeleteTagId = null
        }
    }

    confirmPurgeTagId?.let { id ->
        val t = state.tags.firstOrNull { it.id == id }
        if (t != null) {
            AlertDialog(
                onDismissRequest = { confirmPurgeTagId = null },
                title = { Text("Purge definitivo?") },
                text = { Text("Rimuovere definitivamente '${t.name}'?") },
                confirmButton = {
                    TextButton(onClick = {
                        onPurgeTag(id)
                        confirmPurgeTagId = null
                    }) { Text("Purge") }
                },
                dismissButton = {
                    TextButton(onClick = { confirmPurgeTagId = null }) { Text("Annulla") }
                }
            )
        } else {
            confirmPurgeTagId = null
        }
    }
}

private fun computeShownMs(tag: Tag, nowMs: Long): Long {
    // totalMs already includes closed sessions; if running, add the ongoing delta
    val start = tag.lastStartedAtMs
    return if (start != null) {
        tag.totalMs + (nowMs - start).coerceAtLeast(0L)
    } else {
        tag.totalMs
    }
}
