// v3
package com.example.multitimetracker.widget

import android.content.Context
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.persistence.SnapshotStore
import kotlin.random.Random

/**
 * Shared logic used by the home-screen widget to create + start a new random task tagged #temp.
 * Returns the created task ID.
 */
object QuickTaskRunner {

    private fun randomTaskName(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // no 0/O/1/I
        val suffix = buildString {
            repeat(6) { append(alphabet[Random.nextInt(alphabet.length)]) }
        }
        return "Quick-$suffix"
    }

    private fun ensureTempTag(engine: TimeEngine, tags: List<Tag>): Pair<List<Tag>, Long> {
        val existing = tags.firstOrNull { it.name == "#temp" }
        return if (existing != null) {
            tags to existing.id
        } else {
            val created = engine.createTag("#temp")
            (tags + created) to created.id
        }
    }

    private fun notifySnapshotChanged(context: Context) {
        context.sendBroadcast(
            android.content.Intent(QuickTaskWidgetProvider.ACTION_SNAPSHOT_CHANGED)
                .setPackage(context.packageName)
        )
    }

    fun run(context: Context): Long {
        val snapshot = SnapshotStore.load(context)

        val appUsageMs = snapshot?.appUsageMs ?: 0L
        val installAtMs = snapshot?.installAtMs ?: System.currentTimeMillis()

        val engine = TimeEngine()
        val tasks = snapshot?.tasks ?: emptyList()
        val tags = snapshot?.tags ?: emptyList()
        val taskSessions = snapshot?.taskSessions ?: emptyList()
        val tagSessions = snapshot?.tagSessions ?: emptyList()

        val runtime = snapshot?.let {
            TimeEngine.RuntimeSnapshot(
                activeTaskStart = it.activeTaskStart,
                activeTagStart = it.activeTagStart.map { a -> Triple(a.taskId, a.tagId, a.startTs) }
            )
        } ?: TimeEngine.RuntimeSnapshot(emptyMap(), emptyList())

        engine.importRuntimeSnapshot(
            tasks = tasks,
            tags = tags,
            taskSessionsSnapshot = taskSessions,
            tagSessionsSnapshot = tagSessions,
            snapshot = runtime
        )

        val (tags2, tempTagId) = ensureTempTag(engine, tags)

        val newTask = engine.createTask(
            name = randomTaskName(),
            tagIds = setOf(tempTagId),
            link = ""
        )

        val tasks2 = tasks + newTask
        val now = System.currentTimeMillis()
        val result = engine.toggleTask(tasks2, tags2, newTask.id, now)

        val runtime2 = engine.exportRuntimeSnapshot()
        SnapshotStore.save(
            context = context,
            tasks = result.tasks,
            tags = result.tags,
            taskSessions = engine.getTaskSessions(),
            tagSessions = engine.getTagSessions(),
            installAtMs = installAtMs,
            appUsageMs = appUsageMs,
            activeTaskStart = runtime2.activeTaskStart,
            activeTagStart = runtime2.activeTagStart.map { (taskId, tagId, startTs) ->
                SnapshotStore.ActiveTag(taskId = taskId, tagId = tagId, startTs = startTs)
            }
        )

        notifySnapshotChanged(context)
        return newTask.id
    }
}