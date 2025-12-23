// v10
package com.example.multitimetracker.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.ui.components.TagRow
import com.example.multitimetracker.ui.dialogs.TagEditDialog
import com.example.multitimetracker.ui.util.formatDuration

@Composable
fun TagsScreen(
    state: TagsUiState,
    showSeconds: Boolean,
    hideHoursIfZero: Boolean,
    onAddTag: (String) -> Unit,
    onRenameTag: (Long, String) -> Unit,
    onDeleteTag: (Long) -> Unit
) {
    var showAdd by remember { mutableStateOf(false) }
    var editingTagId by remember { mutableStateOf<Long?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 96.dp)
        ) {
            items(state.tags, key = { it.id }) { tag ->
                TagRow(
                    tag = tag,
                    formattedTime = formatDuration(
                        tag.totalMs,
                        showSeconds = showSeconds,
                        hideHoursIfZero = hideHoursIfZero
                    ),
                    onClick = { editingTagId = tag.id }
                )
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Text("+")
        }
    }

    if (showAdd) {
        TagEditDialog(
            initialName = "",
            onConfirm = {
                onAddTag(it)
                showAdd = false
            },
            onDismiss = { showAdd = false }
        )
    }

    editingTagId?.let { id ->
        val tag = state.tags.firstOrNull { it.id == id }
        if (tag != null) {
            TagEditDialog(
                initialName = tag.name,
                onConfirm = {
                    onRenameTag(id, it)
                    editingTagId = null
                },
                onDismiss = { editingTagId = null }
            )
        }
    }
}

data class TagsUiState(
    val tags: List<TagUi>
)

data class TagUi(
    val id: Long,
    val name: String,
    val totalMs: Long
)
