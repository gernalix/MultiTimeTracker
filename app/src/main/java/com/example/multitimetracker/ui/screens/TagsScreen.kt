// v5
package com.example.multitimetracker.ui.screens

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
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
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.ui.components.TagRow

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
    var openedTagId by remember { mutableStateOf<Long?>(null) }
    var editingTagId by remember { mutableStateOf<Long?>(null) }
    var showAdd by remember { mutableStateOf(false) }

    val engine = remember { TimeEngine() }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Tags") }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAdd = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Add tag")
            }
        }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.tags, key = { it.id }) { tag ->
                val feedingTasks = state.tasks.filter { it.tagIds.contains(tag.id) }
                val shownMs = feedingTasks.maxOfOrNull {
                    engine.displayMs(it.totalMs, it.lastStartedAtMs, state.nowMs)
                } ?: 0L

                val runningCount = feedingTasks.count { it.isRunning }
                val runningText = if (runningCount > 0) "In corso • ${runningCount} task" else "In pausa"

                TagRow(
                    tag = tag,
                    shownMs = shownMs,
                    runningText = runningText,
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
                onAddTag(name)
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
