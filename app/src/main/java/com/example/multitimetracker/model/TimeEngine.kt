// v1
package com.example.multitimetracker.model

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

data class EngineResult(
    val tasks: List<Task>,
    val tags: List<Tag>
)

/**
 * Regola chiave:
 * - un Tag "corre" se activeChildrenCount > 0
 * - parte quando passa da 0 -> 1
 * - si ferma quando passa da 1 -> 0
 */
class TimeEngine {

    private val idGen = AtomicLong(1L)

    fun createTask(name: String, tagIds: Set<Long>): Task =
        Task(
            id = idGen.getAndIncrement(),
            name = name,
            tagIds = tagIds,
            isRunning = false,
            totalMs = 0L,
            lastStartedAtMs = null
        )

    fun createTag(name: String): Tag =
        Tag(
            id = idGen.getAndIncrement(),
            name = name,
            activeChildrenCount = 0,
            totalMs = 0L,
            lastStartedAtMs = null
        )

    fun uiTickerFlow(periodMs: Long = 1000L): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    fun toggleTask(tasks: List<Task>, tags: List<Tag>, taskId: Long, nowMs: Long): EngineResult {
        val task = tasks.firstOrNull { it.id == taskId } ?: return EngineResult(tasks, tags)
        return if (task.isRunning) stopTask(tasks, tags, taskId, nowMs) else startTask(tasks, tags, taskId, nowMs)
    }

    private fun startTask(tasks: List<Task>, tags: List<Tag>, taskId: Long, nowMs: Long): EngineResult {
        val task = tasks.firstOrNull { it.id == taskId } ?: return EngineResult(tasks, tags)
        if (task.isRunning) return EngineResult(tasks, tags)

        val updatedTask = task.copy(isRunning = true, lastStartedAtMs = nowMs)

        // Aggiorna tags: per ciascun tag del task, count++ e se era 0 parte il tag.
        val updatedTags = tags.map { tag ->
            if (!updatedTask.tagIds.contains(tag.id)) tag
            else {
                val newCount = tag.activeChildrenCount + 1
                val newLastStart = if (tag.activeChildrenCount == 0) nowMs else tag.lastStartedAtMs
                tag.copy(activeChildrenCount = newCount, lastStartedAtMs = newLastStart)
            }
        }

        val updatedTasks = tasks.map { if (it.id == taskId) updatedTask else it }
        return EngineResult(updatedTasks, updatedTags)
    }

    private fun stopTask(tasks: List<Task>, tags: List<Tag>, taskId: Long, nowMs: Long): EngineResult {
        val task = tasks.firstOrNull { it.id == taskId } ?: return EngineResult(tasks, tags)
        if (!task.isRunning) return EngineResult(tasks, tags)

        val startedAt = task.lastStartedAtMs ?: nowMs
        val delta = max(0L, nowMs - startedAt)
        val updatedTask = task.copy(
            isRunning = false,
            totalMs = task.totalMs + delta,
            lastStartedAtMs = null
        )

        val updatedTags = tags.map { tag ->
            if (!task.tagIds.contains(tag.id)) tag
            else {
                val newCount = max(0, tag.activeChildrenCount - 1)
                // Se passa a 0, accumula tempo sul tag e fermalo
                if (tag.activeChildrenCount == 1) {
                    val tagStartedAt = tag.lastStartedAtMs ?: nowMs
                    val tagDelta = max(0L, nowMs - tagStartedAt)
                    tag.copy(
                        activeChildrenCount = newCount,
                        totalMs = tag.totalMs + tagDelta,
                        lastStartedAtMs = null
                    )
                } else {
                    tag.copy(activeChildrenCount = newCount)
                }
            }
        }

        val updatedTasks = tasks.map { if (it.id == taskId) updatedTask else it }
        return EngineResult(updatedTasks, updatedTags)
    }

    /**
     * Cambiare i tag mentre un task è running:
     * - rimuovere un tag equivale a "stop" su quel tag
     * - aggiungere un tag equivale a "start" su quel tag
     */
    fun reassignTaskTags(
        tasks: List<Task>,
        tags: List<Tag>,
        taskId: Long,
        newTagIds: Set<Long>,
        nowMs: Long
    ): EngineResult {
        val task = tasks.firstOrNull { it.id == taskId } ?: return EngineResult(tasks, tags)
        val oldTagIds = task.tagIds
        if (oldTagIds == newTagIds) return EngineResult(tasks, tags)

        val removed = oldTagIds - newTagIds
        val added = newTagIds - oldTagIds

        var updatedTags = tags

        if (task.isRunning) {
            // Rimozione
            removed.forEach { tagId ->
                updatedTags = updatedTags.map { tag ->
                    if (tag.id != tagId) tag
                    else {
                        val newCount = max(0, tag.activeChildrenCount - 1)
                        if (tag.activeChildrenCount == 1) {
                            val startedAt = tag.lastStartedAtMs ?: nowMs
                            val delta = max(0L, nowMs - startedAt)
                            tag.copy(activeChildrenCount = newCount, totalMs = tag.totalMs + delta, lastStartedAtMs = null)
                        } else {
                            tag.copy(activeChildrenCount = newCount)
                        }
                    }
                }
            }
            // Aggiunta
            added.forEach { tagId ->
                updatedTags = updatedTags.map { tag ->
                    if (tag.id != tagId) tag
                    else {
                        val newCount = tag.activeChildrenCount + 1
                        val newLast = if (tag.activeChildrenCount == 0) nowMs else tag.lastStartedAtMs
                        tag.copy(activeChildrenCount = newCount, lastStartedAtMs = newLast)
                    }
                }
            }
        }

        val updatedTask = task.copy(tagIds = newTagIds)
        val updatedTasks = tasks.map { if (it.id == taskId) updatedTask else it }
        return EngineResult(updatedTasks, updatedTags)
    }

    fun displayMs(totalMs: Long, lastStartedAtMs: Long?, nowMs: Long): Long {
        return if (lastStartedAtMs == null) totalMs else totalMs + max(0L, nowMs - lastStartedAtMs)
    }
}
