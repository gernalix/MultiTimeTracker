// v3
package com.example.multitimetracker.model

import com.example.multitimetracker.export.TaskSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * TimeEngine = "motore" puro (quasi) che aggiorna tasks/tags in base a start/stop.
 *
 * Note:
 * - Il ViewModel mantiene lo stato (liste tasks/tags).
 * - L'engine conserva solo:
 *   - la sequenza di id (per createTask/createTag)
 *   - le sessioni (start/end) per export
 */
class TimeEngine {

    private var nextTaskId: Long = 1L
    private var nextTagId: Long = 1L

    private val activeTaskStart = mutableMapOf<Long, Long>() // taskId -> startTs
    private val taskSessions = mutableListOf<TaskSession>()

    data class EngineResult(
        val tasks: List<Task>,
        val tags: List<Tag>
    )

    fun createTag(name: String): Tag = Tag(
        id = nextTagId++,
        name = name,
        activeChildrenCount = 0,
        totalMs = 0L,
        lastStartedAtMs = null
    )

    fun createTask(name: String, tagIds: Set<Long>): Task = Task(
        id = nextTaskId++,
        name = name,
        tagIds = tagIds,
        isRunning = false,
        totalMs = 0L,
        lastStartedAtMs = null
    )

    /**
     * "Ticker" per aggiornare l'orologio della UI senza toccare lo stato ogni frame.
     */
    fun uiTickerFlow(periodMs: Long = 250L): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    /**
     * Mostra un tempo "live" se l'elemento è in esecuzione.
     */
    fun displayMs(totalMs: Long, lastStartedAtMs: Long?, nowMs: Long): Long {
        return if (lastStartedAtMs == null) totalMs else totalMs + (nowMs - lastStartedAtMs).coerceAtLeast(0L)
    }

    fun toggleTask(
        tasks: List<Task>,
        tags: List<Tag>,
        taskId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) return EngineResult(tasks, tags)

        val t = tasks[idx]
        return if (t.isRunning) {
            stopTask(tasks, tags, t, nowMs)
        } else {
            startTask(tasks, tags, t, nowMs)
        }
    }

    fun reassignTaskTags(
        tasks: List<Task>,
        tags: List<Tag>,
        taskId: Long,
        newTagIds: Set<Long>,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) return EngineResult(tasks, tags)

        val t = tasks[idx]
        // Se il task è running, "chiudiamo" la sessione corrente e la riapriamo con i nuovi tag.
        val (tasksStopped, tagsStopped) = if (t.isRunning) {
            val stopped = stopTask(tasks, tags, t, nowMs)
            stopped.tasks to stopped.tags
        } else {
            tasks to tags
        }

        val updatedTask = tasksStopped[idx].copy(tagIds = newTagIds)
        val tasksReassigned = tasksStopped.toMutableList().also { it[idx] = updatedTask }.toList()

        return if (t.isRunning) {
            // riapriamo con stessi millisecondi (splitta correttamente)
            startTask(tasksReassigned, tagsStopped, updatedTask.copy(isRunning = false, lastStartedAtMs = null), nowMs)
        } else {
            EngineResult(tasksReassigned, tagsStopped)
        }
    }

    fun getTaskSessions(): List<TaskSession> = taskSessions.toList()

    fun clearSessions() {
        taskSessions.clear()
        activeTaskStart.clear()
    }

    private fun startTask(tasks: List<Task>, tags: List<Tag>, task: Task, nowMs: Long): EngineResult {
        activeTaskStart[task.id] = nowMs

        val newTask = task.copy(
            isRunning = true,
            lastStartedAtMs = nowMs
        )

        val newTasks = tasks.map { if (it.id == task.id) newTask else it }
        val newTags = tags.map { tag ->
            if (!newTask.tagIds.contains(tag.id)) return@map tag

            val wasRunning = tag.activeChildrenCount > 0
            tag.copy(
                activeChildrenCount = tag.activeChildrenCount + 1,
                lastStartedAtMs = if (!wasRunning) nowMs else tag.lastStartedAtMs
            )
        }

        return EngineResult(newTasks, newTags)
    }

    private fun stopTask(tasks: List<Task>, tags: List<Tag>, task: Task, nowMs: Long): EngineResult {
        val start = activeTaskStart.remove(task.id) ?: task.lastStartedAtMs
        val delta = if (start != null && nowMs > start) (nowMs - start) else 0L

        val newTask = task.copy(
            isRunning = false,
            totalMs = task.totalMs + delta,
            lastStartedAtMs = null
        )

        // log session per export
        if (start != null && nowMs > start) {
            taskSessions.add(
                TaskSession(
                    taskId = task.id,
                    taskName = task.name,
                    startTs = start,
                    endTs = nowMs
                )
            )
        }

        val newTasks = tasks.map { if (it.id == task.id) newTask else it }
        val newTags = tags.map { tag ->
            if (!task.tagIds.contains(tag.id)) return@map tag

            val newCount = (tag.activeChildrenCount - 1).coerceAtLeast(0)
            val tagDelta = if (tag.lastStartedAtMs != null && nowMs > tag.lastStartedAtMs) {
                // attributo l'intero delta ai tag correnti del task.
                // Nota: se più task con lo stesso tag sono running, questo modello semplifica.
                // Va bene per la demo/primissima versione.
                if (newCount == 0) nowMs - tag.lastStartedAtMs else 0L
            } else 0L

            tag.copy(
                activeChildrenCount = newCount,
                totalMs = tag.totalMs + tagDelta,
                lastStartedAtMs = if (newCount == 0) null else tag.lastStartedAtMs
            )
        }

        return EngineResult(newTasks, newTags)
    }
}
