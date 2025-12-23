// v14
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

    data class RuntimeSnapshot(
        val activeTaskStart: Map<Long, Long>,
        val activeTagStart: List<Triple<Long, Long, Long>>
    )

    data class EngineResult(
        val tasks: List<Task>,
        val tags: List<Tag>
    )

    fun createTag(name: String): Tag = Tag(
        id = nextTagId++,
        name = name,
        isDeleted = false,
        deletedAtMs = null,
        activeChildrenCount = 0,
        totalMs = 0L,
        lastStartedAtMs = null
    )

    fun createTask(name: String, tagIds: Set<Long>, link: String = ""): Task = Task(
        id = nextTaskId++,
        name = name,
        link = link,
        tagIds = tagIds,
        isDeleted = false,
        deletedAtMs = null,
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
        if (t.isDeleted) return EngineResult(tasks, tags)
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
        newName: String? = null,
        newLink: String? = null,
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

        val updatedTask = task.copy(
            name = (newName ?: task.name).trim().ifEmpty { task.name },
            tagIds = newTagIds,
            link = newLink ?: task.link
        )
        val newTasks = tasks.toMutableList().also { it[idx] = updatedTask }.toList()

        return EngineResult(newTasks, newTags)
    }

    fun getTaskSessions(): List<TaskSession> = taskSessions.toList()

    fun getTagSessions(): List<TagSession> = tagSessions.toList()

    fun exportRuntimeSnapshot(): RuntimeSnapshot {
        val activeTags = activeTagStart.entries.map { (k, startTs) ->
            Triple(k.taskId, k.tagId, startTs)
        }
        return RuntimeSnapshot(
            activeTaskStart = activeTaskStart.toMap(),
            activeTagStart = activeTags
        )
    }

    /**
     * Restores all volatile runtime structures needed to correctly STOP running items after process death.
     *
     * IMPORTANT: callers must also restore tasks/tags lists in the ViewModel.
     */
    fun importRuntimeSnapshot(
        tasks: List<Task>,
        tags: List<Tag>,
        taskSessionsSnapshot: List<TaskSession>,
        tagSessionsSnapshot: List<TagSession>,
        snapshot: RuntimeSnapshot
    ) {
        activeTaskStart.clear()
        activeTaskStart.putAll(snapshot.activeTaskStart)

        activeTagStart.clear()
        snapshot.activeTagStart.forEach { (taskId, tagId, startTs) ->
            activeTagStart[TagKey(taskId = taskId, tagId = tagId)] = startTs
        }

        taskSessions.clear()
        taskSessions.addAll(taskSessionsSnapshot)
        tagSessions.clear()
        tagSessions.addAll(tagSessionsSnapshot)

        nextTaskId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
        nextTagId = (tags.maxOfOrNull { it.id } ?: 0L) + 1L
    }

    /**
     * Carica uno snapshot importato (tipicamente da CSV) sostituendo tutto lo stato volatile.
     *
     * Nota: l'app al momento non persiste su DB, quindi questo è il modo più semplice per
     * ripristinare tasks/tags/sessioni dopo reinstallazione.
     */
    fun loadImportedSnapshot(
        tasks: List<Task>,
        tags: List<Tag>,
        importedTaskSessions: List<TaskSession>,
        importedTagSessions: List<TagSession>,
        runtimeSnapshot: RuntimeSnapshot? = null
    ) {
        // reset runtime + sessions
        activeTaskStart.clear()
        activeTagStart.clear()
        taskSessions.clear()
        taskSessions.addAll(importedTaskSessions)
        tagSessions.clear()
        tagSessions.addAll(importedTagSessions)

        // update id generators so that new entities won't collide
        nextTaskId = (tasks.maxOfOrNull { it.id } ?: 0L) + 1L
        nextTagId = (tags.maxOfOrNull { it.id } ?: 0L) + 1L

        // restore runtime snapshot if present
        if (runtimeSnapshot != null) {
            importRuntimeSnapshot(
                tasks = tasks,
                tags = tags,
                taskSessionsSnapshot = importedTaskSessions,
                tagSessionsSnapshot = importedTagSessions,
                snapshot = runtimeSnapshot
            )
        } else {
            // best-effort: rebuild runtime from imported tasks (so STOP works correctly)
            tasks.filter { it.isRunning && it.lastStartedAtMs != null }.forEach { t ->
                val start = t.lastStartedAtMs ?: return@forEach
                activeTaskStart[t.id] = start
                t.tagIds.forEach { tagId ->
                    activeTagStart[TagKey(taskId = t.id, tagId = tagId)] = start
                }
            }
        }
    }

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

        // Soft delete: keep item in list so IDs are never reused and we can offer a "Trash".
        val newTasks = stopped.tasks.map { task ->
            if (task.id == taskId) {
                task.copy(isDeleted = true, deletedAtMs = nowMs, isRunning = false, lastStartedAtMs = null)
            } else task
        }

        // When a task is deleted, it should not keep tag counters running.
        // stopTask already closed tag sessions if it was running.
        return EngineResult(newTasks, stopped.tags)
    }

    fun deleteTag(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long,
        deleteAssociatedTasks: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        var curTasks = tasks
        var curTags = tags

        val tasksWithTag = curTasks.filter { !it.isDeleted && it.tagIds.contains(tagId) }

        // For the "Solo tag" option, remember which tasks were linked to this tag.
        // This lets us re-associate automatically if the tag is restored from the trash.
        val restoreTaskIdsSnapshot = tasksWithTag.map { it.id }.toSet()

        if (deleteAssociatedTasks) {
            // Soft delete all tasks that currently have the tag.
            tasksWithTag.forEach { task ->
                val res = deleteTask(
                    tasks = curTasks,
                    tags = curTags,
                    taskId = task.id,
                    nowMs = nowMs
                )
                curTasks = res.tasks
                curTags = res.tags
            }
        } else {
            // Remove the tag from each task; if a task is running, close TagSessions coherently.
            tasksWithTag.forEach { task ->
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
        }

        // Soft delete the tag itself.
        curTags = curTags.map { tag ->
            if (tag.id == tagId) {
                if (deleteAssociatedTasks) {
                    tag.copy(isDeleted = true, deletedAtMs = nowMs, restoreTaskIds = emptySet())
                } else {
                    tag.copy(isDeleted = true, deletedAtMs = nowMs, restoreTaskIds = restoreTaskIdsSnapshot)
                }
            } else tag
        }
        return EngineResult(curTasks, curTags)
    }

    fun restoreTask(tasks: List<Task>, taskId: Long): List<Task> {
        return tasks.map { if (it.id == taskId) it.copy(isDeleted = false, deletedAtMs = null) else it }
    }

    fun restoreTag(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long,
        nowMs: Long = System.currentTimeMillis()
    ): EngineResult {
        var curTasks = tasks
        var curTags = tags

        val tag = curTags.firstOrNull { it.id == tagId } ?: return EngineResult(curTasks, curTags)
        val taskIdsToRestore = tag.restoreTaskIds

        // First restore the tag itself.
        curTags = curTags.map {
            if (it.id == tagId) it.copy(isDeleted = false, deletedAtMs = null, restoreTaskIds = emptySet()) else it
        }

        // Then re-associate it to tasks that had it before deletion ("Solo tag").
        // Use reassignTaskTags so running task/tag sessions remain coherent.
        taskIdsToRestore.forEach { taskId ->
            val t = curTasks.firstOrNull { it.id == taskId }
            if (t != null && !t.isDeleted) {
                val res = reassignTaskTags(
                    tasks = curTasks,
                    tags = curTags,
                    taskId = taskId,
                    newTagIds = t.tagIds + tagId,
                    nowMs = nowMs
                )
                curTasks = res.tasks
                curTags = res.tags
            }
        }

        return EngineResult(curTasks, curTags)
    }

    fun purgeTask(
        tasks: List<Task>,
        tags: List<Tag>,
        taskId: Long
    ): EngineResult {
        // Remove runtime tracking
        activeTaskStart.remove(taskId)
        activeTagStart.keys.filter { it.taskId == taskId }.forEach { activeTagStart.remove(it) }

        // Remove sessions
        taskSessions.removeAll { it.taskId == taskId }
        tagSessions.removeAll { it.taskId == taskId }

        val newTasks = tasks.filterNot { it.id == taskId }
        return EngineResult(newTasks, tags)
    }

    fun purgeTag(
        tasks: List<Task>,
        tags: List<Tag>,
        tagId: Long
    ): EngineResult {
        // Remove runtime tracking for that tag
        activeTagStart.keys.filter { it.tagId == tagId }.forEach { activeTagStart.remove(it) }

        // Remove sessions
        tagSessions.removeAll { it.tagId == tagId }

        // Remove the tag from all tasks (including deleted ones) to avoid dangling references
        val updatedTasks = tasks.map { t ->
            if (t.tagIds.contains(tagId)) t.copy(tagIds = t.tagIds - tagId) else t
        }
        val newTags = tags.filterNot { it.id == tagId }
        return EngineResult(updatedTasks, newTags)
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

        // Keep a tag-level "running since" for UI: earliest start among active sessions for this tag.
        val earliest = activeTagStart
            .filter { (k, _) -> k.tagId == tagId }
            .minOfOrNull { it.value }

        return tags.map { tag ->
            if (tag.id != tagId) return@map tag
            val prevStarted = tag.lastStartedAtMs
            val newStarted = when {
                earliest != null -> earliest
                prevStarted != null -> minOf(prevStarted, nowMs)
                else -> nowMs
            }
            tag.copy(
                activeChildrenCount = tag.activeChildrenCount + 1,
                lastStartedAtMs = newStarted
            )
        }
    }

    private fun stopTagForTask(task: Task, tags: List<Tag>, tagId: Long, nowMs: Long): List<Tag> {
        val key = TagKey(taskId = task.id, tagId = tagId)
        val start = activeTagStart.remove(key) ?: return tags

        val delta = if (nowMs > start) (nowMs - start) else 0L

        if (delta > 0L) {
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

        // Recompute earliest active start for this tag (if still running for other tasks).
        val earliest = activeTagStart
            .filter { (k, _) -> k.tagId == tagId }
            .minOfOrNull { it.value }

        return tags.map { tag ->
            if (tag.id != tagId) return@map tag

            val newCount = (tag.activeChildrenCount - 1).coerceAtLeast(0)
            val newStartedAt = earliest // null if no longer active

            tag.copy(
                activeChildrenCount = newCount,
                totalMs = tag.totalMs + delta,
                lastStartedAtMs = newStartedAt
            )
        }
    }
}
