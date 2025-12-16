// v6
package com.example.multitimetracker.model

import com.example.multitimetracker.export.TaskSession
import com.example.multitimetracker.export.TagSession
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * TimeEngine = "motore" puro che gestisce:
 * - start/stop dei task (TaskSession = da PLAY a STOP)
 * - sessioni dei tag (TagSession) che iniziano/finiscono quando:
 *   - il task parte/si ferma
 *   - un tag viene aggiunto/rimosso mentre il task è running
 *
 * Modello timing: NOW - START_TIME (stile timecop)
 * Quindi il tempo "live" è calcolato, non incrementato in RAM.
 */
class TimeEngine {

    private var nextTaskId: Long = 1L
    private var nextTagId: Long = 1L

    private val activeTaskStart = mutableMapOf<Long, Long>() // taskId -> startTs

    private data class TagKey(val taskId: Long, val tagId: Long)
    private val activeTagStart = mutableMapOf<TagKey, Long>() // (taskId, tagId) -> startTs

    private val taskSessions = mutableListOf<TaskSession>()
    private val tagSessions = mutableListOf<TagSession>()

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

    /**
     * Aggiorna i tag di un task.
     *
     * Regole (coerenti):
     * - se il task NON è running: aggiorna solo la relazione task<tag>
     * - se il task È running:
     *   - per ogni tag rimosso: chiude la TagSession (end=now) e decrementa activeChildrenCount del tag
     *   - per ogni tag aggiunto: apre una nuova TagSession (start=now) e incrementa activeChildrenCount del tag
     *   - NON chiude la TaskSession (la sessione del task resta da PLAY a STOP)
     */
    fun reassignTaskTags(
        tasks: List<Task>,
        tags: List<Tag>,
        taskId: Long,
        newTagIds: Set<Long>,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) return EngineResult(tasks, tags)

        val task = tasks[idx]
        val old = task.tagIds
        val removed = old - newTagIds
        val added = newTagIds - old

        var newTags = tags

        if (task.isRunning) {
            removed.forEach { tagId -> newTags = stopTagForTask(task, newTags, tagId, nowMs) }
            added.forEach { tagId -> newTags = startTagForTask(task, newTags, tagId, nowMs) }
        }

        val updatedTask = task.copy(tagIds = newTagIds)
        val newTasks = tasks.toMutableList().also { it[idx] = updatedTask }.toList()

        return EngineResult(newTasks, newTags)
    }

    fun getTaskSessions(): List<TaskSession> = taskSessions.toList()

    fun getTagSessions(): List<TagSession> = tagSessions.toList()

    fun clearSessions() {
        taskSessions.clear()
        tagSessions.clear()
        activeTaskStart.clear()
        activeTagStart.clear()
    }

    fun deleteTask(
        tasks: List<Task>,
        tags: List<Tag>,
        taskId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        val idx = tasks.indexOfFirst { it.id == taskId }
        if (idx < 0) return EngineResult(tasks, tags)

        val t = tasks[idx]
        val stopped = if (t.isRunning) stopTask(tasks, tags, t, nowMs) else EngineResult(tasks, tags)

        val newTasks = stopped.tasks.filterNot { it.id == taskId }
        return EngineResult(newTasks, stopped.tags)
    }

    fun deleteTag(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        var curTasks = tasks
        var curTags = tags

        // Rimuove il tag da ogni task; se un task è running, chiude le TagSession coerentemente.
        curTasks.filter { it.tagIds.contains(tagId) }.forEach { task ->
            val res = reassignTaskTags(
                tasks = curTasks,
                tags = curTags,
                taskId = task.id,
                newTagIds = task.tagIds - tagId,
                nowMs = nowMs
            )
            curTasks = res.tasks
            curTags = res.tags
        }

        // Elimina il tag dalla lista.
        curTags = curTags.filterNot { it.id == tagId }
        return EngineResult(curTasks, curTags)
    }



    fun renameTag(
        tags: List<Tag>,
        tagId: Long,
        newName: String
    ): List<Tag> {
        val n = newName.trim()
        if (n.isEmpty()) return tags
        return tags.map { tag ->
            if (tag.id == tagId) tag.copy(name = n) else tag
        }
    }
    private fun startTask(tasks: List<Task>, tags: List<Tag>, task: Task, nowMs: Long): EngineResult {
        activeTaskStart[task.id] = nowMs

        val newTask = task.copy(
            isRunning = true,
            lastStartedAtMs = nowMs
        )

        // Avvia tag-session per tutti i tag associati al task in questo momento
        var newTags = tags
        newTask.tagIds.forEach { tagId ->
            newTags = startTagForTask(newTask, newTags, tagId, nowMs)
        }

        val newTasks = tasks.map { if (it.id == task.id) newTask else it }
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

        // log task-session per export
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

        // chiudi tutte le tag-session attive per questo task
        var newTags = tags
        task.tagIds.forEach { tagId ->
            newTags = stopTagForTask(task, newTags, tagId, nowMs)
        }

        val newTasks = tasks.map { if (it.id == task.id) newTask else it }
        return EngineResult(newTasks, newTags)
    }

    private fun startTagForTask(task: Task, tags: List<Tag>, tagId: Long, nowMs: Long): List<Tag> {
        val key = TagKey(taskId = task.id, tagId = tagId)
        if (activeTagStart.containsKey(key)) return tags // già attivo

        activeTagStart[key] = nowMs

        return tags.map { tag ->
            if (tag.id != tagId) return@map tag
            tag.copy(activeChildrenCount = tag.activeChildrenCount + 1)
        }
    }

    private fun stopTagForTask(task: Task, tags: List<Tag>, tagId: Long, nowMs: Long): List<Tag> {
        val key = TagKey(taskId = task.id, tagId = tagId)
        val start = activeTagStart.remove(key) ?: return tags

        if (nowMs > start) {
            val tagNameById = tags.associate { it.id to it.name }
            val tagName = tagNameById[tagId] ?: ""
            tagSessions.add(
                TagSession(
                    tagId = tagId,
                    tagName = tagName,
                    taskId = task.id,
                    taskName = task.name,
                    startTs = start,
                    endTs = nowMs
                )
            )
        }

        return tags.map { tag ->
            if (tag.id != tagId) return@map tag
            tag.copy(activeChildrenCount = (tag.activeChildrenCount - 1).coerceAtLeast(0))
        }
    }
}
