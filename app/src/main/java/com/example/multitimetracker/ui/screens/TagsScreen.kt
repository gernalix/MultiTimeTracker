// v11
package com.example.multitimetracker.ui.screens
import com.example.multitimetracker.ui.theme.tagColorFromSeed

import com.example.multitimetracker.ui.theme.assignDistinctTagColors
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.ui.components.TagRow

private data class Interval(val start: Long, val end: Long)

/**
 * Unione cronologica degli intervalli: somma le durate senza doppio conteggio delle sovrapposizioni.
 */
private fun unionDurationMs(intervals: List<Interval>): Long {
    if (intervals.isEmpty()) return 0L
    val sorted = intervals.sortedWith(compareBy<Interval> { it.start }.thenBy { it.end })
    var curStart = sorted[0].start
    var curEnd = sorted[0].end
    var total = 0L
    for (i in 1 until sorted.size) {
        val itv = sorted[i]
        if (itv.start <= curEnd) {
            // overlap / touch
            if (itv.end > curEnd) curEnd = itv.end
        } else {
            total += (curEnd - curStart).coerceAtLeast(0L)
            curStart = itv.start
            curEnd = itv.end
        }
    }
    total += (curEnd - curStart).coerceAtLeast(0L)
    return total
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    modifier: Modifier,
    state: UiState,
    onAddTag: (String) -> Unit,
    onRenameTag: (Long, String) -> Unit,
    onDeleteTag: (Long) -> Unit
) {
    var deletingTagId by remember { mutableStateOf<Long?>(null) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedTagIds by remember { mutableStateOf(setOf<Long>()) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var openedTagId by remember { mutableStateOf<Long?>(null) }
    var editingTagId by remember { mutableStateOf<Long?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val engine = remember { TimeEngine() }

    val tagColors = remember(state.tags) { assignDistinctTagColors(state.tags) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    if (selectionMode) {
                        Text("Selezionati: ${selectedTagIds.size}")
                    } else {
                        Text("Tags")
                    }
                },
                actions = {
                    if (selectionMode) {
                        IconButton(
                            onClick = {
                                if (selectedTagIds.isNotEmpty()) showDeleteSelectedDialog = true
                            }
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete selected")
                        }
                        TextButton(
                            onClick = {
                                selectionMode = false
                                selectedTagIds = emptySet()
                            }
                        ) { Text("Fine") }
                    }
                }
            )
        },
        floatingActionButton = {
            if (!selectionMode) {
                FloatingActionButton(onClick = { showAdd = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add tag")
                }
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val totalTags = state.tags.size
            val activeTags = state.tags.count { tag ->
                state.tasks.any { it.isRunning && it.tagIds.contains(tag.id) }
            }

            item(key = "tags_header") {
                Text(
                    text = "$totalTags tag totali, $activeTags attivi",
                    style = androidx.compose.material3.MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
            }

            items(state.tags, key = { it.id }) { tag ->
                // Totale tag = UNION cronologica delle sessioni dei task che *attualmente* hanno questo tag.
                // (le sovrapposizioni contano una sola volta)
                val feedingTasks = state.tasks.filter { it.tagIds.contains(tag.id) }
                val feedingTaskIds = feedingTasks.map { it.id }.toSet()

                val closedIntervals = state.taskSessions
                    .asSequence()
                    .filter { feedingTaskIds.contains(it.taskId) }
                    .map { Interval(it.startTs, it.endTs) }
                    .toList()

                val openIntervals = feedingTasks
                    .asSequence()
                    .filter { it.isRunning && it.lastStartedAtMs != null }
                    .map { Interval(it.lastStartedAtMs!!, state.nowMs) }
                    .toList()

                val shownMs = unionDurationMs(closedIntervals + openIntervals)

                val runningCount = feedingTasks.count { it.isRunning }
                val runningText = if (runningCount > 0) "In corso • ${runningCount} task" else "In pausa"

                val isSelected = selectedTagIds.contains(tag.id)

                TagRow(
                    color = tagColors[tag.id] ?: tagColorFromSeed(tag.id.toString()),
                    tag = tag,
                    shownMs = shownMs,
                    runningText = runningText,
                    selectionMode = selectionMode,
                    selected = isSelected,
                    onClick = {
                        if (selectionMode) {
                            selectedTagIds = if (isSelected) selectedTagIds - tag.id else selectedTagIds + tag.id
                            if (selectedTagIds.isEmpty()) selectionMode = false
                        } else {
                            openedTagId = tag.id
                        }
                    },
                    onLongPress = {
                        if (!selectionMode) selectionMode = true
                        selectedTagIds = if (isSelected) (selectedTagIds - tag.id) else (selectedTagIds + tag.id)
                    },
                    onOpen = { openedTagId = tag.id },
                    onEdit = { editingTagId = tag.id },
                    onDelete = { deletingTagId = tag.id }
                )
            }
        }
    }

    // Delete selected tags
    if (showDeleteSelectedDialog) {
        val count = selectedTagIds.size
        AlertDialog(
            onDismissRequest = { showDeleteSelectedDialog = false },
            title = { Text("Elimina tag") },
            text = { Text("Eliminare $count tag selezionati?") },
            confirmButton = {
                Button(onClick = {
                    selectedTagIds.forEach { onDeleteTag(it) }
                    showDeleteSelectedDialog = false
                    selectionMode = false
                    selectedTagIds = emptySet()
                }) { Text("Elimina") }
            },
            dismissButton = {
                Button(onClick = { showDeleteSelectedDialog = false }) { Text("Annulla") }
            }
        )
    }

    // Add tag
    if (showAdd) {
        AddOrRenameTagDialog(
            title = "Nuovo tag",
            initialName = "",
            confirmText = "Crea",
            onDismiss = { showAdd = false },
            onConfirm = { name ->
                val n = name.trim()
                if (n.isEmpty()) return@AddOrRenameTagDialog
                val exists = state.tags.any { it.name.equals(n, ignoreCase = true) }
                if (exists) {
                    Toast.makeText(context, "tag già esiste", Toast.LENGTH_SHORT).show()
                    return@AddOrRenameTagDialog
                }
                onAddTag(n)
                showAdd = false
            }
        )
    }

    // Rename tag
    val editId = editingTagId
    if (editId != null) {
        val tag = state.tags.firstOrNull { it.id == editId }
        if (tag != null) {
            AddOrRenameTagDialog(
                title = "Modifica tag",
                initialName = tag.name,
                confirmText = "Salva",
                onDismiss = { editingTagId = null },
                onConfirm = { newName ->
                    onRenameTag(editId, newName)
                    editingTagId = null
                }
            )
        } else {
            editingTagId = null
        }
    }

    // Tag detail: mostra TUTTI i task che hanno quel tag (running o in pausa)
    val openId = openedTagId
    if (openId != null) {
        val tag = state.tags.firstOrNull { it.id == openId }
        if (tag != null) {
            val tasksWithTag = state.tasks
                .filter { it.tagIds.contains(openId) }
                .sortedWith(
                    compareByDescending<Task> { it.isRunning }
                        .thenBy { it.name.lowercase() }
                )

            AlertDialog(
                onDismissRequest = { openedTagId = null },
                title = { Text("Tag: ${tag.name}") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        if (tasksWithTag.isEmpty()) {
                            Text("Nessun task ha questo tag.")
                            return@Column
                        }

                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 380.dp)
                        ) {
                            items(tasksWithTag, key = { it.id }) { task ->
                                val ms = engine.displayMs(task.totalMs, task.lastStartedAtMs, state.nowMs)
                                val dot = if (task.isRunning) "🟢" else "⚪"
                                Text("$dot ${task.name} — ${formatDuration(ms)}")
                            }
                        }
                    }
                },
                confirmButton = {
                    Button(onClick = { openedTagId = null }) { Text("Chiudi") }
                }
            )
        } else {
            openedTagId = null
        }
    }

    // Delete dialog
    val delId = deletingTagId
    if (delId != null) {
        val tag = state.tags.firstOrNull { it.id == delId }
        if (tag != null) {
            AlertDialog(
                onDismissRequest = { deletingTagId = null },
                title = { Text("Elimina tag") },
                text = { Text("Vuoi eliminare '${tag.name}'? Verrà rimosso anche da tutti i task.") },
                confirmButton = {
                    Button(
                        onClick = {
                            onDeleteTag(delId)
                            deletingTagId = null
                        }
                    ) { Text("Elimina") }
                },
                dismissButton = {
                    Button(onClick = { deletingTagId = null }) { Text("Annulla") }
                }
            )
        } else {
            deletingTagId = null
        }
    }
}

@Composable
private fun AddOrRenameTagDialog(
    title: String,
    initialName: String,
    confirmText: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nome tag") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(onClick = {
                val n = name.trim()
                if (n.isNotEmpty()) onConfirm(n)
            }) { Text(confirmText) }
        },
        dismissButton = { Button(onClick = onDismiss) { Text("Chiudi") } }
    )
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val totalMin = totalSec / 60
    val min = totalMin % 60
    val hours = totalMin / 60
    return "%02d:%02d:%02d".format(hours, min, sec)
}