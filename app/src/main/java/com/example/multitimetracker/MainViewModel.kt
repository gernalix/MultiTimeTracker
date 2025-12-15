// v3
package com.example.multitimetracker

import android.content.Context
import android.widget.Toast
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multitimetracker.export.CsvExporter
import com.example.multitimetracker.export.ShareUtils
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.UiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val engine = TimeEngine()

    private val _state = MutableStateFlow(
        UiState(
            tasks = emptyList(),
            tags = emptyList(),
            nowMs = System.currentTimeMillis()
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        // Demo data (puoi cancellarlo quando vuoi)
        val logseq = engine.createTag("Logseq")
        val coding = engine.createTag("coding")
        val siti = engine.createTag("siti")
        val faccende = engine.createTag("faccende")

        val t1 = engine.createTask("Logseq FAQ monitor", setOf(logseq.id, coding.id))
        val t2 = engine.createTask("lavatrice", setOf(faccende.id))
        val t3 = engine.createTask("site monitor", setOf(coding.id, siti.id))

        _state.update {
            it.copy(tasks = listOf(t1, t2, t3), tags = listOf(logseq, coding, siti, faccende))
        }

        // Tick per aggiornare SOLO la UI (non salva nulla ogni secondo).
        viewModelScope.launch {
            engine.uiTickerFlow().collect { now ->
                _state.update { it.copy(nowMs = now) }
            }
        }
    }

    fun toggleTask(taskId: Long) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val result = engine.toggleTask(
                tasks = current.tasks,
                tags = current.tags,
                taskId = taskId,
                nowMs = now
            )
            current.copy(tasks = result.tasks, tags = result.tags, nowMs = now)
        }
    }

    fun addTask(name: String, tagIds: Set<Long>) {
        if (name.isBlank()) return
        _state.update { current ->
            val task = engine.createTask(name.trim(), tagIds)
            current.copy(tasks = current.tasks + task)
        }
    }

    fun addTag(name: String) {
        if (name.isBlank()) return
        _state.update { current ->
            val tag = engine.createTag(name.trim())
            current.copy(tags = current.tags + tag)
        }
    }

    fun updateTaskTags(taskId: Long, newTagIds: Set<Long>) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val result = engine.reassignTaskTags(
                tasks = current.tasks,
                tags = current.tags,
                taskId = taskId,
                newTagIds = newTagIds,
                nowMs = now
            )
            current.copy(tasks = result.tasks, tags = result.tags, nowMs = now)
        }
    }

    

    fun deleteTask(taskId: Long) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val res = engine.deleteTask(
                tasks = current.tasks,
                tags = current.tags,
                taskId = taskId,
                nowMs = now
            )
            current.copy(tasks = res.tasks, tags = res.tags, nowMs = now)
        }
    }

    fun deleteTag(tagId: Long) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val res = engine.deleteTag(
                tasks = current.tasks,
                tags = current.tags,
                tagId = tagId,
                nowMs = now
            )
            current.copy(tasks = res.tasks, tags = res.tags, nowMs = now)
        }
    }
fun exportCsv(context: Context) {
        val taskSessions = engine.getTaskSessions()
        val tagSessions = engine.getTagSessions()

        if (taskSessions.isEmpty() && tagSessions.isEmpty()) {
            Toast.makeText(
                context,
                "Nessuna sessione da esportare (avvia e ferma almeno un task)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val files = listOf(
            CsvExporter.exportTaskSessions(context, taskSessions),
            CsvExporter.exportTaskTotals(context, taskSessions),
            CsvExporter.exportTagSessions(context, tagSessions),
            CsvExporter.exportTagTotals(context, tagSessions)
        )

        ShareUtils.shareFiles(context, files, title = "MultiTimeTracker export")
    }

}
