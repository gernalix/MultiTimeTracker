// v17
package com.example.multitimetracker.ui.screens
import com.example.multitimetracker.ui.theme.tagColorFromSeed

import com.example.multitimetracker.ui.theme.assignDistinctTagColors
import android.widget.Toast
// NOTE: inside LazyColumn item scopes AnimatedVisibility can resolve to the ColumnScope overload.
// We call it fully qualified where needed to avoid implicit receiver ambiguity.
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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

    // Smooth removal animation for swipe-to-trash.
    var removingTagIds by remember { mutableStateOf(setOf<Long>()) }

    val scope = rememberCoroutineScope()

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
        data class TagMeta(
            val tag: Tag,
            val runningCount: Int
        )

        val tagMeta = remember(visibleTags, visibleTasks) {
            visibleTags.map { tag ->
                val feeding = visibleTasks.filter { it.tagIds.contains(tag.id) }
                TagMeta(tag = tag, runningCount = feeding.count { it.isRunning })
            }
        }

        val orderedTagMeta = remember(tagMeta) {
            tagMeta.sortedWith(
                compareByDescending<TagMeta> { it.runningCount > 0 }
                    .thenBy { it.tag.name.lowercase() }
            )
        }

        val listState = rememberLazyListState()

        Box(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
            state = listState,
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(orderedTagMeta, key = { it.tag.id }) { meta ->
                val tag = meta.tag
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

                val dismissState = rememberSwipeToDismissBoxState(
                    confirmValueChange = { value ->
                        when (value) {
                            SwipeToDismissBoxValue.StartToEnd -> {
                                // Swipe right -> edit
                                editingTagId = tag.id
                            }
                            SwipeToDismissBoxValue.EndToStart -> {
                                // Swipe left -> trash (with semantics prompt)
                                deletingTagId = tag.id
                            }
                            else -> Unit
                        }
                        // Keep the item in-place; state changes will drive recomposition.
                        false
                    }
                )

                androidx.compose.animation.AnimatedVisibility(
                    visible = !removingTagIds.contains(tag.id),
                    enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                        fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                    exit = shrinkVertically(animationSpec = tween(800, easing = FastOutSlowInEasing)) +
                        fadeOut(animationSpec = tween(800, easing = FastOutSlowInEasing))
                ) {
                    SwipeToDismissBox(
                        state = dismissState,
                        backgroundContent = {
                        val bgColor = when (dismissState.dismissDirection) {
                            SwipeToDismissBoxValue.StartToEnd -> Color(0xFFFFF59D) // giallo
                            SwipeToDismissBoxValue.EndToStart -> Color(0xFFFFCDD2) // rosso
                            else -> Color.Transparent
                        }
                        val label = when (dismissState.dismissDirection) {
                            SwipeToDismissBoxValue.StartToEnd -> "MODIFICA"
                            SwipeToDismissBoxValue.EndToStart -> "TRASH"
                            else -> ""
                        }
                        val alignment = when (dismissState.dismissDirection) {
                            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                            else -> Alignment.Center
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .background(bgColor),
                            contentAlignment = alignment
                        ) {
                            if (label.isNotBlank()) {
                                Text(
                                    text = label,
                                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.padding(horizontal = 14.dp)
                                )
                            }
                        }
                        },
                        content = {
                        TagRow(
                            color = tagColors[tag.id] ?: tagColorFromSeed(tag.id.toString()),
                            tag = tag,
                            shownMs = shownMs,
                            runningText = runningText,
                            highlightRunning = runningCount > 0,
                            sharedCount = sharedCount,
                            onOpen = { openedTagId = tag.id }
                        )
                        }
                    )
                }
            }
        }

            val showScrollToTop = listState.firstVisibleItemIndex > 0
            androidx.compose.animation.AnimatedVisibility(
                visible = showScrollToTop,
                enter = fadeIn(animationSpec = tween(150)),
                exit = fadeOut(animationSpec = tween(150)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
            ) {
                FloatingActionButton(
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.size(44.dp),
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Scroll to top")
                }
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
                            if (!removingTagIds.contains(delId)) {
                                removingTagIds = removingTagIds + delId
                                scope.launch {
                                    delay(800)
                                    onDeleteTag(delId, false)
                                    removingTagIds = removingTagIds - delId
                                }
                            }
                            deletingTagId = null
                        }) { Text("Solo tag") }
                        Button(onClick = {
                            if (!removingTagIds.contains(delId)) {
                                removingTagIds = removingTagIds + delId
                                scope.launch {
                                    delay(800)
                                    onDeleteTag(delId, true)
                                    removingTagIds = removingTagIds - delId
                                }
                            }
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
