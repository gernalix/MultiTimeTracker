// v3
package com.example.multitimetracker.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.ui.components.TaskRow

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
    onExport: (Context) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editingTaskId by remember { mutableStateOf<Long?>(null) }
    var deletingTaskId by remember { mutableStateOf<Long?>(null) }
    val context = LocalContext.current

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                actions = {
                    IconButton(onClick = { onExport(context) }) {
                        Icon(Icons.Filled.Share, contentDescription = "Export CSV")
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
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.tasks, key = { it.id }) { task ->
                TaskRow(
                    task = task,
                    tags = state.tags,
                    nowMs = state.nowMs,
                    onToggle = { onToggleTask(task.id) },
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

    val delId = deletingTaskId
    if (delId != null) {
        val task = state.tasks.firstOrNull { it.id == delId }
        if (task != null) {
            AlertDialog(
                onDismissRequest = { deletingTaskId = null },
                title = { Text("Elimina task") },
                text = { Text("Vuoi eliminare '${'$'}{task.name}'?") },
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
private fun AddTaskDialog(
    tags: List<com.example.multitimetracker.model.Tag>,
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
    tags: List<com.example.multitimetracker.model.Tag>,
    initialSelection: Set<Long>,
    onDismiss: () -> Unit,
    onConfirm: (Set<Long>) -> Unit
) {
    var selected by remember { mutableStateOf(initialSelection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
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
                Spacer(modifier = Modifier.padding(2.dp))
                Text("Nota: se il task è in corso, aggiungere/rimuovere tag aggiorna i timer dei tag in tempo reale.")
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
