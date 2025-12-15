    // v1
package com.example.multitimetracker.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.ui.components.TagRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(
    modifier: Modifier,
    state: UiState
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text("Tags") }) }
    ) { inner ->
        LazyColumn(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(state.tags, key = { it.id }) { tag ->
                TagRow(
                    tag = tag,
                    nowMs = state.nowMs
                )
            }
        }
    }
}
