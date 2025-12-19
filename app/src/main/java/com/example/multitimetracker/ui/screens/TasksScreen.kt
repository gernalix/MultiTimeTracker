// v14
package com.example.multitimetracker.ui.screens
import androidx.compose.material3.MaterialTheme

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import com.example.multitimetracker.export.TaskSession
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.ui.components.TaskRow
import com.example.multitimetracker.ui.theme.tagColorFromSeed
import com.example.multitimetracker.ui.theme.assignDistinctTagColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    modifier: Modifier,
    state: UiState,
    onToggleTask: (Long) -> Unit,
    onAddTask: (String, Set<Long>) -> Unit,
    onAddTag: (String) -> Unit,
    onEditTaskTags: (Long, Set<Long>) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onExport: (Context) -> Unit,
    onImport: (Context) -> Unit,
    onSetBackupRootFolder: (Context, android.net.Uri) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editingTaskId by remember { mutableStateOf<Long?>(null) }
    var deletingTaskId by remember { mutableStateOf<Long?>(null) }
    var openedTaskId by remember { mutableStateOf<Long?>(null) }

    var query by remember { mutableStateOf("") }
    var selectedTagFilters by remember { mutableStateOf(setOf<Long>()) }

    val context = LocalContext.current
    val engine = remember { TimeEngine() }

    val tagColors = remember(state.tags) { assignDistinctTagColors(state.tags) }

    var pendingAfterFolderPick by remember { mutableStateOf<(() -> Unit)?>(null) }

    val treeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { treeUri ->
        if (treeUri != null) {
            onSetBackupRootFolder(context, treeUri)
            pendingAfterFolderPick?.invoke()
            pendingAfterFolderPick = null
        }
    }

    val filteredTasks = state.tasks.filter { task ->
        val q = query.trim()
        val matchesQuery =
            q.isBlank() ||
                task.name.contains(q, ignoreCase = true) ||
                state.tags.filter { task.tagIds.contains(it.id) }.any { it.name.contains(q, ignoreCase = true) }

        val matchesTags =
            selectedTagFilters.isEmpty() ||
                selectedTagFilters.all { task.tagIds.contains(it) }

        matchesQuery && matchesTags
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                actions = {
                    IconButton(onClick = {
                        // If backup folder not configured yet, ask once for a root folder.
                        if (BackupFolderStore.getTreeUri(context) == null) {
                            pendingAfterFolderPick = { onImport(context) }
                            treeLauncher.launch(null)
                        } else {
                            onImport(context)
                        }
                    }) {
                        Icon(Icons.Filled.FileOpen, contentDescription = "Import")
                    }
                    IconButton(onClick = {
                        if (BackupFolderStore.getTreeUri(context) == null) {
                            pendingAfterFolderPick = { onExport(context) }
                            treeLauncher.launch(null)
                        } else {
                            onExport(context)
                        }
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Export")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add task")
            }
        }
    ) { inner ->
        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Search
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Ricerca task o tag") },
                singleLine = true,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxWidth()
            )

            // Tag filters
            Row(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.tags.isEmpty()) {
                    AssistChip(onClick = { }, label = { Text("Nessun tag") })
                } else {
                    state.tags.forEach { tag ->
                        val selected = selectedTagFilters.contains(tag.id)
                        val base = tagColors[tag.id] ?: tagColorFromSeed(tag.name)
                        val bg = if (selected) base.copy(alpha = 0.55f) else base.copy(alpha = 0.28f)
                        FilterChip(
                            selected = selected,
                            onClick = {
                                selectedTagFilters =
                                    if (selected) selectedTagFilters - tag.id else selectedTagFilters + tag.id
                            },
                            label = { Text(tag.name) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = bg,
                                selectedContainerColor = bg
                            )
                        )
                    }
                }
            }

            LazyColumn(
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredTasks, key = { it.id }) { task ->
                    TaskRow(
                        tagColors = tagColors,
                        task = task,
                        tags = state.tags,
                        nowMs = state.nowMs,
                        onToggle = { onToggleTask(task.id) },
                        onOpenHistory = { openedTaskId = task.id },
                        trailing = {
                            IconButton(onClick = { editingTaskId = task.id }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit tags")
                            }
                            IconButton(onClick = { deletingTaskId = task.id }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete task")
                            }
                        }
                    )
                }
            }
        }
    }

    // Add task
    if (showAdd) {
        AddTaskDialog(
            tags = state.tags,
            onDismiss = { showAdd = false },
            onAddTag = onAddTag,
            onConfirm = { name, tagIds ->
                onAddTask(name, tagIds)
                showAdd = false
            }
        )
    }

    // Task history
    val openId = openedTaskId
    if (openId != null) {
        val task = state.tasks.firstOrNull { it.id == openId }
        if (task != null) {
            TaskHistoryDialog(
                taskName = task.name,
                isRunning = task.isRunning,
                runningStartTs = task.lastStartedAtMs,
                nowMs = state.nowMs,
                sessions = state.taskSessions.filter { it.taskId == openId },
                onDismiss = { openedTaskId = null }
            )
        } else {
            openedTaskId = null
        }
    }

    // Delete task
    val delId = deletingTaskId
    if (delId != null) {
        val task = state.tasks.firstOrNull { it.id == delId }
        if (task != null) {
            AlertDialog(
                onDismissRequest = { deletingTaskId = null },
                title = { Text("Elimina task") },
                text = { Text("Vuoi eliminare '${task.name}'?") },
                confirmButton = {
                    Button(onClick = {
                        onDeleteTask(delId)
                        deletingTaskId = null
                    }) { Text("Elimina") }
                },
                dismissButton = {
                    Button(onClick = { deletingTaskId = null }) { Text("Annulla") }
                }
            )
        } else {
            deletingTaskId = null
        }
    }

    // Edit tags
    val editId = editingTaskId
    if (editId != null) {
        val task = state.tasks.firstOrNull { it.id == editId }
        if (task != null) {
            EditTagsDialog(
                title = "Modifica tag",
                tags = state.tags,
                initialSelection = task.tagIds,
                onDismiss = { editingTaskId = null },
                onConfirm = { newTags ->
                    onEditTaskTags(editId, newTags)
                    editingTaskId = null
                }
            )
        }
    }
}

@Composable
private fun TaskHistoryDialog(
    taskName: String,
    isRunning: Boolean,
    runningStartTs: Long?,
    nowMs: Long,
    sessions: List<TaskSession>,
    onDismiss: () -> Unit
) {
    val engine = remember { TimeEngine() }

    val zone = remember { java.time.ZoneId.systemDefault() }
    val dayFmt = remember { java.time.format.DateTimeFormatter.ISO_LOCAL_DATE }
    val timeFmt = remember { java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss") }

    fun dayOf(ts: Long): java.time.LocalDate =
        java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()

    fun timeOf(ts: Long): String =
        java.time.Instant.ofEpochMilli(ts).atZone(zone).toLocalTime().format(timeFmt)

    val ordered = remember(sessions) { sessions.sortedByDescending { it.startTs } }
    val grouped = remember(ordered) { ordered.groupBy { dayOf(it.startTs) } }
    val days = remember(grouped) { grouped.keys.sortedDescending() }

    val savedTotal = remember(sessions) {
        sessions.sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) }
    }
    val runningDur = if (isRunning && runningStartTs != null) engine.displayMs(0L, runningStartTs, nowMs) else 0L
    val grandTotal = savedTotal + runningDur

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Sessioni: $taskName") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (grandTotal > 0L) {
                    Text(
                        text = "Totale: ${formatDuration(grandTotal)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    // Running session (pinned on top)
                    if (isRunning && runningStartTs != null) {
                        val d = dayOf(runningStartTs)
                        item(key = "running_header_${d}") {
                            Text(
                                text = "📅 ${d.format(dayFmt)}",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        item(key = "running_row") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(timeOf(runningStartTs))
                                    Text("→")
                                    Text("ora")
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End
                                ) {
                                    Text(
                                        text = "Durata: ${formatDuration(runningDur)}  •  IN CORSO",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    )
                                }
                            }
                        }
                        item { HorizontalDivider() }
                    }

                    if (ordered.isEmpty()) {
                        item {
                            Text("Nessuna sessione salvata (avvia e ferma almeno una volta).")
                        }
                    } else {
                        for (day in days) {
                            val list = grouped[day].orEmpty()
                            item(key = "day_${day}") {
                                Text(
                                    text = "📅 ${day.format(dayFmt)}",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                            items(list, key = { it.startTs }) { s ->
                                val dur = (s.endTs - s.startTs).coerceAtLeast(0L)
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(timeOf(s.startTs))
                                        Text("→")
                                        Text(timeOf(s.endTs))
                                    }
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Text(
                                            text = "Durata: ${formatDuration(dur)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                            item { HorizontalDivider() }
                        }
                    }
                }
            }
        },
        confirmButton = { Button(onClick = onDismiss) { Text("Chiudi") } }
    )
}

@Composable
private fun AddTaskDialog(
    tags: List<Tag>,
    onDismiss: () -> Unit,
    onAddTag: (String) -> Unit,
    onConfirm: (String, Set<Long>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var newTagName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuovo task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Tag")
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    tags.forEach { tag ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(tag.name)
                            Checkbox(
                                checked = selected.contains(tag.id),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + tag.id else selected - tag.id
                                }
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = newTagName,
                        onValueChange = { newTagName = it },
                        label = { Text("Nuovo tag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val t = newTagName.trim()
                            if (t.isNotEmpty()) {
                                onAddTag(t)
                                newTagName = ""
                            }
                        }
                    ) { Text("Aggiungi") }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(name, selected) }) { Text("Crea") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Annulla") }
        }
    )
}

@Composable
private fun EditTagsDialog(
    title: String,
    tags: List<Tag>,
    initialSelection: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit
) {
    var selected by remember { mutableStateOf(initialSelection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(tags, key = { it.id }) { tag ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(tag.name)
                            Checkbox(
                                checked = selected.contains(tag.id),
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + tag.id else selected - tag.id
                                }
                            )
                        }
                    }
                }
                Text(
                    "Nota: se il task è in corso, aggiungere/rimuovere tag non ferma il task: apre/chiude solo le sessioni del tag.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selected) }) { Text("Salva") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Chiudi") }
        }
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