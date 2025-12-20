// v7
package com.example.multitimetracker.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
        private const val EXTRA_FROM_ACTIVITY = "com.example.multitimetracker.widget.EXTRA_FROM_ACTIVITY"

        // Dynamic receiver inside the app uses this to refresh UI when the widget writes a new snapshot.
        const val ACTION_SNAPSHOT_CHANGED = "com.example.multitimetracker.ACTION_SNAPSHOT_CHANGED"

        private fun buildClickIntent(context: Context): PendingIntent {
            val intent = Intent(context, QuickTaskWidgetClickActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }
            return PendingIntent.getActivity(
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
        }

        private fun vibrateClick(context: Context) {
            runCatching {
                val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (vibrator?.hasVibrator() == true) {
                    // Prefer a predefined haptic effect when available.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Slightly longer than before; some devices ignore ultra-short pulses.
                        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(80)
                    }
                }
            }
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
            val appCtx = context.applicationContext
            val fromActivity = intent.getBooleanExtra(EXTRA_FROM_ACTIVITY, false)
            if (!fromActivity) {
                vibrateClick(appCtx)
            }
            runCatching { runQuickTask(appCtx) }
                .onSuccess {
                    if (!fromActivity) {
                        Toast.makeText(appCtx, "Quick task avviato", Toast.LENGTH_SHORT).show()
                    }
                }
                .onFailure { e ->
                    // If the Activity is being used, it will already have shown a toast.
                    if (!fromActivity) {
                        Toast.makeText(appCtx, "Quick task fallito: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
        }
    }
}
