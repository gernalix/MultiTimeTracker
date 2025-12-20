// v9
package com.example.multitimetracker.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.example.multitimetracker.MainViewModel
import com.example.multitimetracker.ui.screens.TagsScreen
import com.example.multitimetracker.ui.screens.TasksScreen

private enum class Tab { TASKS, TAGS }

@Composable
fun AppRoot(
    vm: MainViewModel,
    focusTaskId: Long? = null,
    onFocusConsumed: () -> Unit = {}
) {
    val state by vm.state.collectAsState()
    varTabScaffold(state = state, vm = vm, focusTaskId = focusTaskId, onFocusConsumed = onFocusConsumed)
}

@Composable
private fun varTabScaffold(
    state: com.example.multitimetracker.model.UiState,
    vm: MainViewModel,
    focusTaskId: Long?,
    onFocusConsumed: () -> Unit
) {
    androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(Tab.TASKS) }.let { tabState ->
        val tab = tabState.value

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
                        icon = { Icon(Icons.Filled.Label, contentDescription = "Tags") },
                        label = { Text("Tags") }
                    )
                }
            }
        ) { inner ->
            when (tab) {
                Tab.TASKS -> TasksScreen(
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
                    onExternalFocusConsumed = onFocusConsumed
                )

                Tab.TAGS -> TagsScreen(
                    modifier = Modifier.padding(inner),
                    state = state,
                    onAddTag = vm::addTag,
                    onRenameTag = vm::renameTag,
                    onDeleteTag = vm::deleteTag,
                    onRestoreTag = vm::restoreTag,
                    onPurgeTag = vm::purgeTag
                )
            }
        }
    }
}
