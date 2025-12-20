// v4
package com.example.multitimetracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import android.widget.Toast
import com.example.multitimetracker.R
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.persistence.SnapshotStore
import kotlin.random.Random

class QuickTaskWidgetProvider : AppWidgetProvider() {

    companion object {
        private const val ACTION_QUICK_TASK_CLICK = "com.example.multitimetracker.widget.ACTION_QUICK_TASK_CLICK"

        // Dynamic receiver inside the app uses this to refresh UI when the widget writes a new snapshot.
        const val ACTION_SNAPSHOT_CHANGED = "com.example.multitimetracker.ACTION_SNAPSHOT_CHANGED"

        private fun buildClickIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickTaskWidgetProvider::class.java).apply {
                action = ACTION_QUICK_TASK_CLICK
            }
            return PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        private fun updateAllWidgets(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetIds: IntArray
        ) {
            val rv = RemoteViews(context.packageName, R.layout.widget_quick_task)
            rv.setOnClickPendingIntent(R.id.widgetRoot, buildClickIntent(context))
            appWidgetManager.updateAppWidget(appWidgetIds, rv)
        }

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
            // Stays inside the app package; no UI is opened.
            context.sendBroadcast(
                Intent(ACTION_SNAPSHOT_CHANGED).setPackage(context.packageName)
            )
        }

        private fun runQuickTask(context: Context) {
            val snapshot = SnapshotStore.load(context)

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

            // Restore runtime & next IDs so IDs don't collide.
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

            // toggleTask(start) -> marks task running + activates tag session counters.
            val result = engine.toggleTask(tasks2, tags2, newTask.id, now)

            val runtime2 = engine.exportRuntimeSnapshot()
            SnapshotStore.save(
                context = context,
                tasks = result.tasks,
                tags = result.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                activeTaskStart = runtime2.activeTaskStart,
                activeTagStart = runtime2.activeTagStart.map { (taskId, tagId, startTs) ->
                    SnapshotStore.ActiveTag(taskId = taskId, tagId = tagId, startTs = startTs)
                }
            )

            // If the app is already open, this makes it reload the snapshot.
            notifySnapshotChanged(context)

            Toast.makeText(context, "Quick task avviato", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        updateAllWidgets(context, appWidgetManager, appWidgetIds)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_QUICK_TASK_CLICK) {
            runCatching { runQuickTask(context.applicationContext) }
                .onFailure { e ->
                    Toast.makeText(context, "Quick task fallito: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }
}
