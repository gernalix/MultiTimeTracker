// v37
@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)
package com.example.multitimetracker.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.export.TaskSession
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.ui.components.TaskRow
import com.example.multitimetracker.ui.theme.assignDistinctTagColors
import com.example.multitimetracker.ui.theme.tagColorFromSeed
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    onSetBackupRootFolder: (Context, Uri) -> Unit,
    externalFocusTaskId: Long? = null,
    onExternalFocusConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    var showAdd by remember { mutableStateOf(false) }
    var editingTaskId by remember { mutableStateOf<Long?>(null) }
    var showTrash by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var openedTaskId by remember { mutableStateOf<Long?>(null) }

    // Filter UI is hidden by default and appears only via the top button.
    var showFilters by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedTagFilters by rememberSaveable { mutableStateOf(setOf<Long>()) }

    // View prefs for inactive tasks (persisted).
    var hideInactiveTime by rememberSaveable { mutableStateOf(UiPrefsStore.getHideInactiveTime(context)) }
    var hideInactiveTags by rememberSaveable { mutableStateOf(UiPrefsStore.getHideInactiveTags(context)) }
    var showSeconds by rememberSaveable { mutableStateOf(UiPrefsStore.getShowSeconds(context)) }

    // Smooth removal animation for swipe-to-trash.
    var removingTaskIds by remember { mutableStateOf(setOf<Long>()) }

    // Used to animate the item just created.
    var highlightTaskId by remember { mutableStateOf<Long?>(null) }
    var lastSeenMaxTaskId by rememberSaveable { mutableStateOf(-1L) }

    // Local UI ticker used for stats that must update even when no task is running.
    var localNowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(showSettings) {
        if (!showSettings) return@LaunchedEffect
        while (showSettings) {
            localNowMs = System.currentTimeMillis()
            delay(250)
        }
    }

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

    val filteredTasks = remember(visibleTasks, query, selectedTagFilters, visibleTags) {
        visibleTasks.filter { task ->
            val q = query.trim()
            val matchesQuery =
                q.isBlank() ||
                    task.name.contains(q, ignoreCase = true) ||
                    visibleTags.filter { task.tagIds.contains(it.id) }
                        .any { it.name.contains(q, ignoreCase = true) }

            val matchesTags =
                selectedTagFilters.isEmpty() || selectedTagFilters.all { task.tagIds.contains(it) }

            matchesQuery && matchesTags
        }
    }

    // Running tasks on top; among running: most recently started first; among others: newest first.
    val orderedTasks = remember(filteredTasks) {
        filteredTasks.sortedWith(
            compareByDescending<Task> { it.isRunning }
                .thenByDescending { it.lastStartedAtMs ?: 0L }
                .thenByDescending { it.id }
        )
    }

    val runningTasks = remember(orderedTasks) { orderedTasks.filter { it.isRunning } }
    val inactiveTasks = remember(orderedTasks) { orderedTasks.filter { !it.isRunning } }

    // Focus request coming from outside (e.g. widget).
    LaunchedEffect(externalFocusTaskId) {
        val id = externalFocusTaskId
        if (id != null) {
            highlightTaskId = id
            runCatching { listState.animateScrollToItem(0) }
            delay(2000)
            if (highlightTaskId == id) highlightTaskId = null
            onExternalFocusConsumed()
        }
    }

    // When a new task is created, scroll to top and briefly highlight it.
    LaunchedEffect(state.tasks) {
        val maxId = state.tasks.asSequence().filter { !it.isDeleted }.maxOfOrNull { it.id } ?: 0L
        if (lastSeenMaxTaskId < 0L) {
            lastSeenMaxTaskId = maxId
            return@LaunchedEffect
        }
        if (maxId > lastSeenMaxTaskId) {
            lastSeenMaxTaskId = maxId
            highlightTaskId = maxId
            runCatching { listState.animateScrollToItem(0) }
            delay(2000)
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
                    IconButton(onClick = { showFilters = !showFilters }) {
                        Icon(Icons.Filled.FilterList, contentDescription = "Filtri")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                    IconButton(onClick = { showTrash = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Trash")
                    }
                    IconButton(onClick = {
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
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    })
                },
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (showFilters) {
                FilterPanel(
                    query = query,
                    onQueryChange = { query = it },
                    tags = visibleTags,
                    selectedTagIds = selectedTagFilters,
                    tagColors = tagColors,
                    onToggleTag = { id ->
                        selectedTagFilters =
                            if (selectedTagFilters.contains(id)) selectedTagFilters - id else selectedTagFilters + id
                    },
                    onClear = {
                        query = ""
                        selectedTagFilters = emptySet()
                        focusManager.clearFocus()
                        keyboardController?.hide()
                    }
                )
            }

            val activeCount = state.tasks.count { it.isRunning }
            Text(
                text = "$activeCount task attivi",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(horizontal = 12.dp)
            )

            if (runningTasks.isNotEmpty()) {
                ActiveTasksMinimalPanel(
                    tasks = runningTasks,
                    allTags = visibleTags,
                    tagColors = tagColors,
                    showSeconds = showSeconds,
                    nowMs = state.nowMs,
                    onToggleTaskById = onToggleTask,
                    onLongPressTaskById = { openedTaskId = it },
                    onOpenLinkForTask = { link -> openLink(context, link) }
                )
            }

            Box(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .padding(horizontal = 12.dp)
                        .fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(inactiveTasks, key = { it.id }) { task ->
                        val dismissState = rememberSwipeToDismissBoxState(
                            confirmValueChange = { value ->
                                when (value) {
                                    SwipeToDismissBoxValue.StartToEnd -> {
                                        editingTaskId = task.id
                                    }
                                    SwipeToDismissBoxValue.EndToStart -> {
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
                                false
                            }
                        )

                        androidx.compose.animation.AnimatedVisibility(
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
                                        SwipeToDismissBoxValue.StartToEnd -> Color(0xFFFFF59D)
                                        SwipeToDismissBoxValue.EndToStart -> Color(0xFFFFCDD2)
                                        else -> Color.Transparent
                                    }
                                    val label = when (dismissState.dismissDirection) {
                                        SwipeToDismissBoxValue.StartToEnd -> "Edit"
                                        SwipeToDismissBoxValue.EndToStart -> "Delete"
                                        else -> ""
                                    }
                                    val alignment = when (dismissState.dismissDirection) {
                                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                        SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
                                        else -> Alignment.Center
                                    }

                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
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
                                    val highlightThis = highlightTaskId == task.id
                                    TaskRow(
                                        tagColors = tagColors,
                                        task = task,
                                        tags = visibleTags,
                                        nowMs = state.nowMs,
                                        highlightRunning = false,
                                        highlightJustCreated = highlightThis,
                                        showTime = !hideInactiveTime,
                                        showTags = !hideInactiveTags,
                                        showSeconds = showSeconds,
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

                val showScrollToTop by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 } }
                if (showScrollToTop) {
                    FloatingActionButton(
                        onClick = { scope.launch { listState.animateScrollToItem(0) } },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 18.dp)
                            .size(42.dp),
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.62f),
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 0.dp)
                    ) {
                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Back to top")
                    }
                }
            }
        }
    }

    // Add / Edit / History / Settings / Trash dialogs remain unchanged.
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

    if (showSettings) {
        val intervals = ArrayList<Pair<Long, Long>>(state.taskSessions.size + state.tasks.size)
        state.taskSessions.forEach { s ->
            val st = s.startTs
            val en = s.endTs
            if (en > st) intervals.add(st to en)
        }
        state.tasks.asSequence().filter { it.isRunning && it.lastStartedAtMs != null }.forEach { t ->
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
                Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Statistiche", style = MaterialTheme.typography.titleMedium)
                    Text("Tempo totale tracciato: ${formatDuration(totalTracked, showSeconds)}")
                    val appUsageShown = state.appUsageMs + (state.appUsageRunningSinceMs?.let { (localNowMs - it).coerceAtLeast(0L) } ?: 0L)
                    Text("Tempo trascorso sull'app: ${formatDuration(appUsageShown, showSeconds)}")
                    Text("Task totali: $tasksTotal")
                    Text("Tag totali: $tagsTotal")

                    Spacer(Modifier.height(4.dp))
                    Text("Vista", style = MaterialTheme.typography.titleMedium)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Nascondi tempi nei task inattivi")
                        Checkbox(
                            checked = hideInactiveTime,
                            onCheckedChange = { checked ->
                                hideInactiveTime = checked
                                UiPrefsStore.setHideInactiveTime(context, checked)
                            }
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Nascondi tag nei task inattivi")
                        Checkbox(
                            checked = hideInactiveTags,
                            onCheckedChange = { checked ->
                                hideInactiveTags = checked
                                UiPrefsStore.setHideInactiveTags(context, checked)
                            }
                        )
                    }


                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Mostra secondi")
                        Checkbox(
                            checked = showSeconds,
                            onCheckedChange = { checked ->
                                showSeconds = checked
                                UiPrefsStore.setShowSeconds(context, checked)
                            }
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("Suggerimento", style = MaterialTheme.typography.titleMedium)
                    Text("Se per un periodo ti servono solo pochi task, crea un tag ⭐ e usa il filtro ⭐.")
                }
            },
            confirmButton = { Button(onClick = { showSettings = false }) { Text("Chiudi") } }
        )
    }

    if (showTrash) {
        val trashed = state.tasks.filter { it.isDeleted }.sortedByDescending { it.deletedAtMs ?: 0L }
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
private fun FilterPanel(
    query: String,
    onQueryChange: (String) -> Unit,
    tags: List<Tag>,
    selectedTagIds: Set<Long>,
    tagColors: Map<Long, Color>,
    onToggleTag: (Long) -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Filtri", style = MaterialTheme.typography.labelLarge)
            Button(onClick = onClear) { Text("Reset") }
        }

        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            label = { Text("Ricerca task o tag") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (tags.isEmpty()) {
                AssistChip(onClick = { }, label = { Text("Nessun tag") })
            } else {
                tags.forEach { tag ->
                    val selected = selectedTagIds.contains(tag.id)
                    val base = tagColors[tag.id] ?: tagColorFromSeed(tag.name)
                    val bg = if (selected) base.copy(alpha = 0.55f) else base.copy(alpha = 0.28f)
                    FilterChip(
                        selected = selected,
                        onClick = { onToggleTag(tag.id) },
                        label = { Text(tag.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = bg,
                            selectedContainerColor = bg
                        )
                    )
                }
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

@Composable
private fun ActiveTasksMinimalPanel(
    tasks: List<Task>,
    allTags: List<Tag>,
    tagColors: Map<Long, Color>,
    showSeconds: Boolean,
    nowMs: Long,
    onToggleTaskById: (Long) -> Unit,
    onLongPressTaskById: (Long) -> Unit,
    onOpenLinkForTask: (String) -> Unit
) {
    val engine = remember { TimeEngine() }
    val runningBg = remember { Color(0xFFCCFFCC) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tasks.forEach { task ->
            val shownMs = engine.displayMs(task.totalMs, task.lastStartedAtMs, nowMs)
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .pointerInput(task.id) {
                        detectTapGestures(
                            onTap = { onToggleTaskById(task.id) },
                            onLongPress = { onLongPressTaskById(task.id) }
                        )
                    },
                color = runningBg,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = task.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (task.link.isNotBlank()) Text("🔗")
                        }
                        val taskTags = remember(task.tagIds, allTags) {
                            allTags.filter { task.tagIds.contains(it.id) }
                        }
                        if (taskTags.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                taskTags.forEach { tag ->
                                    CompactTagChip(
                                        label = tag.name,
                                        color = tagColors[tag.id]
                                            ?: MaterialTheme.colorScheme.surfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = formatDuration(shownMs, showSeconds),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/* === Below: existing dialogs + helpers from the original file === */

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
                        text = "Totale: ${formatDuration(grandTotal, showSeconds)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.heightIn(max = 420.dp)
                ) {
                    if (isRunning && runningStartTs != null) {
                        val d = dayOf(runningStartTs)
                        item(key = "running_header_${'$'}{d}") {
                            Text(
                                text = "📅 ${'$'}{d.format(dayFmt)}",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        item(key = "running_row") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("▶️ running", style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        text = formatDuration(engine.displayMs(0L, runningStartTs, nowMs)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                Text(
                                    text = "da ${'$'}{timeOf(runningStartTs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    days.forEach { day ->
                        val list = grouped[day].orEmpty()
                        item(key = "day_${'$'}{day}") {
                            Text(
                                text = "📅 ${'$'}{day.format(dayFmt)}",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                        items(list, key = { "${it.startTs}_${it.endTs}" }) { s ->
                            val dur = (s.endTs - s.startTs).coerceAtLeast(0L)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("${'$'}{timeOf(s.startTs)} → ${'$'}{timeOf(s.endTs)}", style = MaterialTheme.typography.bodySmall)
                                Text(formatDuration(dur, showSeconds), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            }
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
    var newTag by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
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

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newTag,
                        onValueChange = { newTag = it },
                        label = { Text("Nuovo tag") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = {
                        val t = newTag.trim()
                        if (t.isNotBlank()) {
                            onAddTag(t)
                            newTag = ""
                        }
                    }) { Text("+") }
                }

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
        confirmButton = { Button(onClick = { onConfirm(name, selected, link) }) { Text("Salva") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Chiudi") } }
    )
}


@Composable
private fun CompactTagChip(
    label: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun openLink(context: Context, raw: String) {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return
    val url = if (trimmed.startsWith("http://", true) || trimmed.startsWith("https://", true)) trimmed else "https://${'$'}trimmed"
    runCatching {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

private fun formatDuration(ms: Long, showSeconds: Boolean): String {
    val totalSec = ms / 1000
    val sec = totalSec % 60
    val totalMin = totalSec / 60
    val min = totalMin % 60
    val hours = totalMin / 60
    return if (showSeconds) {
        "%02d:%02d:%02d".format(hours, min, sec)
    } else {
        "%02d:%02d".format(hours, min)
    }
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