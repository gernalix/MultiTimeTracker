// v10
package com.example.multitimetracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.multitimetracker.MainViewModel
import com.example.multitimetracker.persistence.UiPrefsStore
import com.example.multitimetracker.ui.screens.TagsScreen
import com.example.multitimetracker.ui.screens.TasksScreen

private enum class Tab { TASKS, TAGS }

@Composable
fun AppRoot(
    vm: MainViewModel,
    focusTaskId: Long? = null,
    onFocusConsumed: () -> Unit = {}
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()
    val showSeconds = rememberSaveable { mutableStateOf(UiPrefsStore.getShowSeconds(context)) }

    VarTabScaffold(
        state = state,
        vm = vm,
        focusTaskId = focusTaskId,
        onFocusConsumed = onFocusConsumed,
        showSeconds = showSeconds.value,
        onShowSecondsChange = {
            showSeconds.value = it
            UiPrefsStore.setShowSeconds(context, it)
        }
    )
}

@Composable
private fun VarTabScaffold(
    state: com.example.multitimetracker.model.UiState,
    vm: MainViewModel,
    focusTaskId: Long?,
    onFocusConsumed: () -> Unit,
    showSeconds: Boolean,
    onShowSecondsChange: (Boolean) -> Unit
) {
    remember { mutableStateOf(Tab.TASKS) }.let { tabState ->
        val tab = tabState.value
        val stateHolder = rememberSaveableStateHolder()

        Scaffold(
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = tab == Tab.TASKS,
                        onClick = { tabState.value = Tab.TASKS },
                        icon = { Icon(Icons.Filled.Timer, contentDescription = "Tasks") },
                        label = { Text("Tasks") }
                    )
                    NavigationBarItem(
                        selected = tab == Tab.TAGS,
                        onClick = { tabState.value = Tab.TAGS },
                        icon = { Icon(Icons.AutoMirrored.Filled.Label, contentDescription = "Tags") },
                        label = { Text("Tags") }
                    )
                }
            }
        ) { inner ->
            when (tab) {
                Tab.TASKS -> stateHolder.SaveableStateProvider("tasks") {
                    TasksScreen(
                        modifier = Modifier.padding(inner),
                        state = state,
                        onToggleTask = vm::toggleTask,
                        onAddTask = vm::addTask,
                        onAddTag = vm::addTag,
                        onEditTask = vm::updateTask,
                        onDeleteTask = vm::deleteTask,
                        onRestoreTask = vm::restoreTask,
                        onPurgeTask = vm::purgeTask,
                        onExport = vm::exportBackup,
                        onImport = vm::importBackup,
                        onSetBackupRootFolder = vm::setBackupRootFolder,
                        externalFocusTaskId = focusTaskId,
                        onExternalFocusConsumed = onFocusConsumed,
                        showSeconds = showSeconds,
                        onShowSecondsChange = onShowSecondsChange
                    )
                }
                Tab.TAGS -> stateHolder.SaveableStateProvider("tags") {
                    TagsScreen(
                        modifier = Modifier.padding(inner),
                        state = state,
                        onAddTag = vm::addTag,
                        onRenameTag = vm::renameTag,
                        onDeleteTag = vm::deleteTag,
                        onRestoreTag = vm::restoreTag,
                        onPurgeTag = vm::purgeTag,
                        showSeconds = showSeconds
                    )
                }
            }
        }
    }
}
