// v24
package com.example.multitimetracker.ui.screens
import androidx.compose.material3.MaterialTheme

import android.content.Context
import android.widget.Toast
import android.net.Uri
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(
    modifier: Modifier,
    state: UiState,
    onToggleTask: (Long) -> Unit,
    onAddTask: (String, Set<Long>, String) -> Unit,
    onAddTag: (String) -> Unit,
    onEditTask: (Long, String, Set<Long>, String) -> Unit,
    onDeleteTask: (Long) -> Unit,
    onRestoreTask: (Long) -> Unit,
    onPurgeTask: (Long) -> Unit,
    onExport: (Context) -> Unit,
    onImport: (Context) -> Unit,
    onSetBackupRootFolder: (Context, android.net.Uri) -> Unit,
    externalFocusTaskId: Long? = null,
    onExternalFocusConsumed: () -> Unit = {}
) {
    var showAdd by remember { mutableStateOf(false) }
    var editingTaskId by remember { mutableStateOf<Long?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var openedTaskId by remember { mutableStateOf<Long?>(null) }

    // Smooth removal animation for swipe-to-trash.
    var removingTaskIds by remember { mutableStateOf(setOf<Long>()) }

    // Used to animate the item just created.
    var highlightTaskId by remember { mutableStateOf<Long?>(null) }
    var lastSeenMaxTaskId by remember { mutableStateOf(0L) }

    var query by remember { mutableStateOf("") }
    var selectedTagFilters by remember { mutableStateOf(setOf<Long>()) }

    val context = LocalContext.current
    val engine = remember { TimeEngine() }
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val visibleTags = remember(state.tags) { state.tags.filter { !it.isDeleted } }
    val visibleTasks = remember(state.tasks) { state.tasks.filter { !it.isDeleted } }

    val tagColors = remember(visibleTags) { assignDistinctTagColors(visibleTags) }

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

    val filteredTasks = visibleTasks.filter { task ->
        val q = query.trim()
        val matchesQuery =
            q.isBlank() ||
                task.name.contains(q, ignoreCase = true) ||
                visibleTags.filter { task.tagIds.contains(it.id) }.any { it.name.contains(q, ignoreCase = true) }

        val matchesTags =
            selectedTagFilters.isEmpty() ||
                selectedTagFilters.all { task.tagIds.contains(it) }

        matchesQuery && matchesTags
    }

    // Requirements:
    // - running tasks on top
    // - among running: most recently started first
    // - among not running: newest to oldest
    val orderedTasks = remember(filteredTasks) {
        filteredTasks.sortedWith(
            compareByDescending<com.example.multitimetracker.model.Task> { it.isRunning }
                .thenByDescending { it.lastStartedAtMs ?: 0L }
                .thenByDescending { it.id }
        )
    }

    // Focus request coming from outside (e.g. widget).
    LaunchedEffect(externalFocusTaskId) {
        val id = externalFocusTaskId
        if (id != null) {
            highlightTaskId = id
            runCatching { listState.animateScrollToItem(0) }
            delay(1200)
            if (highlightTaskId == id) highlightTaskId = null
            onExternalFocusConsumed()
        }
    }

    // When a new task is created, scroll to top and briefly highlight it.
    LaunchedEffect(state.tasks) {
        val maxId = state.tasks
            .asSequence()
            .filter { !it.isDeleted }
            .maxOfOrNull { it.id } ?: 0L

        if (maxId > lastSeenMaxTaskId) {
            lastSeenMaxTaskId = maxId
            highlightTaskId = maxId
            runCatching { listState.animateScrollToItem(0) }
            delay(1200)
            if (highlightTaskId == maxId) highlightTaskId = null
        } else if (lastSeenMaxTaskId == 0L) {
            lastSeenMaxTaskId = maxId
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text("Tasks") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showTrash = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Trash")
                    }
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
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = {
                        // Hide keyboard when the user taps outside inputs.
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val activeCount = state.tasks.count { it.isRunning }
            Text(
                text = "$activeCount task attivi",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

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
                if (visibleTags.isEmpty()) {
                    AssistChip(onClick = { }, label = { Text("Nessun tag") })
                } else {
                    visibleTags.forEach { tag ->
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
                state = listState,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(orderedTasks, key = { it.id }) { task ->
                    val dismissState = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            when (value) {
                                SwipeToDismissBoxValue.StartToEnd -> {
                                    // Swipe right -> edit
                                    editingTaskId = task.id
                                }
                                SwipeToDismissBoxValue.EndToStart -> {
                                    // Swipe left -> trash
                                    // Smooth removal before moving to trash.
                                    val id = task.id
                                    val taskName = task.name
                                    if (!removingTaskIds.contains(id)) {
                                        removingTaskIds = removingTaskIds + id
                                        scope.launch {
                                            delay(600)
                                            onDeleteTask(id)
                                            Toast.makeText(context, "Task $taskName eliminato", Toast.LENGTH_SHORT).show()
                                            removingTaskIds = removingTaskIds - id
                                        }
                                    }
                                }
                                else -> Unit
                            }
                            // Keep the item in-place; state changes will drive recomposition.
                            false
                        }
                    )

                    AnimatedVisibility(
                        visible = !removingTaskIds.contains(task.id),
                        enter = expandVertically(animationSpec = tween(220, easing = FastOutSlowInEasing)) +
                            fadeIn(animationSpec = tween(220, easing = FastOutSlowInEasing)),
                        exit = slideOutHorizontally(
                            animationSpec = tween(600, easing = FastOutSlowInEasing),
                            targetOffsetX = { -it }
                        ) + fadeOut(animationSpec = tween(600, easing = FastOutSlowInEasing))
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
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.padding(horizontal = 14.dp)
                                    )
                                }
                            }
                            },
                            content = {
                            // Highlight the most recently created task.
                            val highlightThis = highlightTaskId == task.id
                            TaskRow(
                                tagColors = tagColors,
                                task = task,
                                tags = visibleTags,
                                nowMs = state.nowMs,
                                highlightRunning = highlightThis || task.isRunning,
                                onToggle = {
                                    onToggleTask(task.id)
                                    scope.launch { listState.animateScrollToItem(0) }
                                },
                                onLongPress = { openedTaskId = task.id },
                                linkText = if (task.link.isNotBlank()) "🔗" else "",
                                onOpenLink = { openLink(context, task.link) }
                            )
                            }
                        )
                    }
                }
            }
        }
    }

    // Add task
    if (showAdd) {
        AddTaskDialog(
            tags = visibleTags,
            onDismiss = { showAdd = false },
            onAddTag = onAddTag,
            onConfirm = { name, tagIds, link ->
                onAddTask(name, tagIds, link)
                focusManager.clearFocus()
                keyboardController?.hide()
                showAdd = false
            }
        )
    }

    // Task history
    val openId = openedTaskId
    if (openId != null) {
        val task = state.tasks.firstOrNull { it.id == openId && !it.isDeleted }
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

    // Settings (minimal stats).
    if (showSettings) {
        val intervals = ArrayList<Pair<Long, Long>>(state.taskSessions.size + state.tasks.size)
        state.taskSessions.forEach { s ->
            val st = s.startTs
            val en = s.endTs
            if (en > st) intervals.add(st to en)
        }
        state.tasks
            .asSequence()
            .filter { it.isRunning && it.lastStartedAtMs != null }
            .forEach { t ->
                val st = t.lastStartedAtMs ?: state.nowMs
                val en = state.nowMs
                if (en > st) intervals.add(st to en)
            }
        val totalTracked = unionTotalMs(intervals)

        val tasksTotal = state.tasks.count { !it.isDeleted }
        val tagsTotal = state.tags.count { !it.isDeleted }

        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Impostazioni") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Statistiche", style = MaterialTheme.typography.titleMedium)
                    Text("Tempo totale tracciato: ${formatDuration(totalTracked)}")
                    val appUsageShown = state.appUsageMs + (state.appUsageRunningSinceMs?.let { (state.nowMs - it).coerceAtLeast(0L) } ?: 0L)
                    Text("Tempo trascorso sull'app: ${formatDuration(appUsageShown)}")
                    Text("Task totali: $tasksTotal")
                    Text("Tag totali: $tagsTotal")

                    Spacer(Modifier.height(6.dp))
                    Text("Suggerimento", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Se per un periodo ti servono solo pochi task, puoi creare un tag ⭐ e tappare sul chip filtro ⭐ per vedere solo quelli."
                    )
                }
            },
            confirmButton = {
                Button(onClick = { showSettings = false }) { Text("Chiudi") }
            }
        )
    }

    // Trash (soft-deleted tasks)
    if (showTrash) {
        val trashed = state.tasks
            .filter { it.isDeleted }
            .sortedByDescending { it.deletedAtMs ?: 0L }

        AlertDialog(
            onDismissRequest = { showTrash = false },
            title = { Text("Cestino (Tasks)") },
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
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                                        Text("id=${t.id}", style = MaterialTheme.typography.labelSmall)
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Button(onClick = { onRestoreTask(t.id) }) { Text("Ripristina") }
                                        Button(onClick = { onPurgeTask(t.id) }) { Text("Elimina") }
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

    // Edit tags
    val editId = editingTaskId
    if (editId != null) {
        val task = state.tasks.firstOrNull { it.id == editId }
        if (task != null) {
            EditTaskDialog(
                title = "Modifica task",
                tags = visibleTags,
                initialName = task.name,
                initialSelection = task.tagIds,
                initialLink = task.link,
                onDismiss = { editingTaskId = null },
                onConfirm = { newName, newTags, newLink ->
                    onEditTask(editId, newName, newTags, newLink)
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
    onConfirm: (String, Set<Long>, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var newTagName by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<Long>()) }

    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current

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

                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Link (opzionale)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Tag")
                LazyColumn(
                    modifier = Modifier.heightIn(max = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(tags, key = { it.id }) { tag ->
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
                                // After adding a tag, keep the UI clean.
                                focusManager.clearFocus()
                                keyboardController?.hide()
                            }
                        }
                    ) { Text("Aggiungi") }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                focusManager.clearFocus()
                keyboardController?.hide()
                onConfirm(name, selected, link)
            }) { Text("Crea") }
        },
        dismissButton = {
            Button(onClick = {
                focusManager.clearFocus()
                keyboardController?.hide()
                onDismiss()
            }) { Text("Annulla") }
        }
    )
}

@Composable
private fun EditTaskDialog(
    title: String,
    tags: List<Tag>,
    initialName: String,
    initialSelection: Set<Long>,
    initialLink: String,
    onDismiss: () -> Unit,
    onConfirm: (String, Set<Long>, String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selected by remember { mutableStateOf(initialSelection) }
    var link by remember { mutableStateOf(initialLink) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome task") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = link,
                    onValueChange = { link = it },
                    label = { Text("Link (opzionale)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

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
            Button(onClick = { onConfirm(name, selected, link) }) { Text("Salva") }
        },
        dismissButton = {
            Button(onClick = onDismiss) { Text("Chiudi") }
        }
    )
}

private fun openLink(context: Context, raw: String) {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return
    val url = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://$trimmed"
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
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

private fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
    if (intervals.isEmpty()) return 0L
    val sorted = intervals.sortedBy { it.first }
    var total = 0L
    var curStart = sorted[0].first
    var curEnd = sorted[0].second
    for (i in 1 until sorted.size) {
        val (s, e) = sorted[i]
        if (e <= s) continue
        if (s <= curEnd) {
            if (e > curEnd) curEnd = e
        } else {
            total += (curEnd - curStart)
            curStart = s
            curEnd = e
        }
    }
    total += (curEnd - curStart)
    return total.coerceAtLeast(0L)
}