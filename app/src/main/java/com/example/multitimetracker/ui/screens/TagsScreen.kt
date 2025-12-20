// v11
package com.example.multitimetracker.ui.screens
import com.example.multitimetracker.ui.theme.tagColorFromSeed

import com.example.multitimetracker.ui.theme.assignDistinctTagColors
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
    onDeleteTag: (Long, Boolean) -> Unit,
    onRestoreTag: (Long) -> Unit,
    onPurgeTag: (Long) -> Unit
) {
    var deletingTagId by remember { mutableStateOf<Long?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var openedTagId by remember { mutableStateOf<Long?>(null) }
    var editingTagId by remember { mutableStateOf<Long?>(null) }
    var showAdd by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val engine = remember { TimeEngine() }

    val visibleTags = remember(state.tags) { state.tags.filter { !it.isDeleted } }
    val visibleTasks = remember(state.tasks) { state.tasks.filter { !it.isDeleted } }

    val tagColors = remember(visibleTags) { assignDistinctTagColors(visibleTags) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Tags") },
                actions = {
                    IconButton(onClick = { showTrash = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Trash")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add tag")
            }
        }
    ) { inner ->
        val orderedTags = remember(visibleTags, visibleTasks) {
            visibleTags.sortedWith(
                compareByDescending<Tag> { tag ->
                    visibleTasks.any { it.isRunning && it.tagIds.contains(tag.id) }
                }.thenBy { it.name.lowercase() }
            )
        }

        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(orderedTags, key = { it.id }) { tag ->
                // Totale tag = UNION cronologica delle sessioni dei task che *attualmente* hanno questo tag.
                // (le sovrapposizioni contano una sola volta)
                val feedingTasks = visibleTasks.filter { it.tagIds.contains(tag.id) }
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

                val sharedTagIds = feedingTasks
                    .asSequence()
                    .flatMap { it.tagIds.asSequence() }
                    .filter { it != tag.id }
                    .toSet()
                    .intersect(visibleTags.map { it.id }.toSet())
                val sharedCount = sharedTagIds.size

                TagRow(
                    color = tagColors[tag.id] ?: tagColorFromSeed(tag.id.toString()),
                    tag = tag,
                    shownMs = shownMs,
                    runningText = runningText,
                    highlightRunning = runningCount > 0,
                    sharedCount = sharedCount,
                    onOpen = { openedTagId = tag.id },
                    onEdit = { editingTagId = tag.id },
                    onDelete = { deletingTagId = tag.id }
                )
            }
        }
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
            val tasksWithTag = visibleTasks
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

    // Trash (soft-deleted tags)
    if (showTrash) {
        val trashed = state.tags
            .filter { it.isDeleted }
            .sortedByDescending { it.deletedAtMs ?: 0L }

        AlertDialog(
            onDismissRequest = { showTrash = false },
            title = { Text("Cestino (Tags)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (trashed.isEmpty()) {
                        Text("Cestino vuoto.")
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.heightIn(max = 420.dp)
                        ) {
                            items(trashed, key = { it.id }) { t ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(t.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
                                        Text("id=${t.id}", style = androidx.compose.material3.MaterialTheme.typography.labelSmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(onClick = { onRestoreTag(t.id) }) { Text("Ripristina") }
                                        Button(onClick = { onPurgeTag(t.id) }) { Text("Elimina") }
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { Button(onClick = { showTrash = false }) { Text("Chiudi") } }
        )
    }

    // Delete dialog (choose semantics)
    val delId = deletingTagId
    if (delId != null) {
        val tag = visibleTags.firstOrNull { it.id == delId }
        if (tag != null) {
            val childrenCount = visibleTasks.count { it.tagIds.contains(delId) }
            AlertDialog(
                onDismissRequest = { deletingTagId = null },
                title = { Text("Elimina tag") },
                text = {
                    val suffix = if (childrenCount > 0) " ($childrenCount task associati)" else ""
                    Text("Cosa vuoi eliminare per '${tag.name}'?$suffix")
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            onDeleteTag(delId, false)
                            deletingTagId = null
                        }) { Text("Solo tag") }
                        Button(onClick = {
                            onDeleteTag(delId, true)
                            deletingTagId = null
                        }) { Text("Tag + task") }
                    }
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