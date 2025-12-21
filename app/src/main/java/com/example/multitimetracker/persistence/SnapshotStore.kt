// v6
package com.example.multitimetracker.persistence

import android.content.Context
import com.example.multitimetracker.export.TaskSession
import com.example.multitimetracker.export.TagSession
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the *authoritative* timing model (TimeCop style): timestamps + open sessions.
 *
 * This enables "auto-resume" after process death: when the app reopens, tasks that were running
 * remain running because their start timestamps are restored.
 */
object SnapshotStore {

    private const val PREFS = "multitimetracker_snapshot"
    private const val KEY_JSON = "snapshot_json"

    data class Snapshot(
        val tasks: List<Task>,
        val tags: List<Tag>,
        val taskSessions: List<TaskSession>,
        val tagSessions: List<TagSession>,
        val appUsageMs: Long,
        val activeTaskStart: Map<Long, Long>,
        val activeTagStart: List<ActiveTag>
    )

    data class ActiveTag(
        val taskId: Long,
        val tagId: Long,
        val startTs: Long
    )

    fun save(
        context: Context,
        tasks: List<Task>,
        tags: List<Tag>,
        taskSessions: List<TaskSession>,
        tagSessions: List<TagSession>,
        appUsageMs: Long,
        activeTaskStart: Map<Long, Long>,
        activeTagStart: List<ActiveTag>
    ) {
        val root = JSONObject()

        root.put("appUsageMs", appUsageMs)

        root.put("tasks", JSONArray().apply {
            tasks.forEach { t ->
                put(
                    JSONObject()
                        .put("id", t.id)
                        .put("name", t.name)
.put("link", t.link)
.put("tagIds", JSONArray().apply { t.tagIds.forEach { put(it) } })
                        .put("isDeleted", t.isDeleted)
                        .put("deletedAtMs", t.deletedAtMs)
                        .put("isRunning", t.isRunning)
                        .put("totalMs", t.totalMs)
                        .put("lastStartedAtMs", t.lastStartedAtMs)
                )
            }
        })

        root.put("tags", JSONArray().apply {
            tags.forEach { tg ->
                put(
                    JSONObject()
                        .put("id", tg.id)
                        .put("name", tg.name)
                        .put("isDeleted", tg.isDeleted)
                        .put("deletedAtMs", tg.deletedAtMs)
                        .put("restoreTaskIds", JSONArray().apply { tg.restoreTaskIds.forEach { put(it) } })
                        .put("activeChildrenCount", tg.activeChildrenCount)
                        .put("totalMs", tg.totalMs)
                        .put("lastStartedAtMs", tg.lastStartedAtMs)
                )
            }
        })

        root.put("taskSessions", JSONArray().apply {
            taskSessions.forEach { s ->
                put(
                    JSONObject()
                        .put("taskId", s.taskId)
                        .put("taskName", s.taskName)
                        .put("startTs", s.startTs)
                        .put("endTs", s.endTs)
                )
            }
        })

        root.put("tagSessions", JSONArray().apply {
            tagSessions.forEach { s ->
                put(
                    JSONObject()
                        .put("tagId", s.tagId)
                        .put("tagName", s.tagName)
                        .put("taskId", s.taskId)
                        .put("taskName", s.taskName)
                        .put("startTs", s.startTs)
                        .put("endTs", s.endTs)
                )
            }
        })

        root.put("activeTaskStart", JSONArray().apply {
            activeTaskStart.forEach { (taskId, startTs) ->
                put(JSONObject().put("taskId", taskId).put("startTs", startTs))
            }
        })

        root.put("activeTagStart", JSONArray().apply {
            activeTagStart.forEach { a ->
                put(
                    JSONObject()
                        .put("taskId", a.taskId)
                        .put("tagId", a.tagId)
                        .put("startTs", a.startTs)
                )
            }
        })

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_JSON, root.toString())
            .apply()
    }

    fun load(context: Context): Snapshot? {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_JSON, null)
            ?: return null

        val root = JSONObject(json)

        val tasks = root.getJSONArray("tasks").toTaskList()
        val tags = root.getJSONArray("tags").toTagList()
        val taskSessions = root.getJSONArray("taskSessions").toTaskSessions()
        val tagSessions = root.getJSONArray("tagSessions").toTagSessions()

        val appUsageMs = root.optLong("appUsageMs", 0L)

        val activeTaskStart = mutableMapOf<Long, Long>()
        root.optJSONArray("activeTaskStart")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                activeTaskStart[o.getLong("taskId")] = o.getLong("startTs")
            }
        }

        val activeTagStart = mutableListOf<ActiveTag>()
        root.optJSONArray("activeTagStart")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                activeTagStart.add(
                    ActiveTag(
                        taskId = o.getLong("taskId"),
                        tagId = o.getLong("tagId"),
                        startTs = o.getLong("startTs")
                    )
                )
            }
        }

        return Snapshot(
            tasks = tasks,
            tags = tags,
            taskSessions = taskSessions,
            tagSessions = tagSessions,
            appUsageMs = appUsageMs,
            activeTaskStart = activeTaskStart.toMap(),
            activeTagStart = activeTagStart.toList()
        )
    }

    private fun JSONArray.toTaskList(): List<Task> {
        val out = ArrayList<Task>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            val tagIdsArr = o.getJSONArray("tagIds")
            val tagIds = buildSet<Long> {
                for (j in 0 until tagIdsArr.length()) add(tagIdsArr.getLong(j))
            }
            out.add(
                Task(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    link = if (o.has("link") && !o.isNull("link")) o.getString("link") else "",
                    tagIds = tagIds,
                    isDeleted = o.optBoolean("isDeleted", false),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null,
                    isRunning = o.getBoolean("isRunning"),
                    totalMs = o.getLong("totalMs"),
                    lastStartedAtMs = if (o.isNull("lastStartedAtMs")) null else o.getLong("lastStartedAtMs")
                )
            )
        }
        return out
    }

    private fun JSONArray.toTagList(): List<Tag> {
        val out = ArrayList<Tag>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)

            val restoreArr = o.optJSONArray("restoreTaskIds")
            val restoreTaskIds = buildSet<Long> {
                if (restoreArr != null) {
                    for (j in 0 until restoreArr.length()) add(restoreArr.getLong(j))
                }
            }

            out.add(
                Tag(
                    id = o.getLong("id"),
                    name = o.getString("name"),
                    isDeleted = o.optBoolean("isDeleted", false),
                    deletedAtMs = if (o.has("deletedAtMs") && !o.isNull("deletedAtMs")) o.getLong("deletedAtMs") else null,
                    restoreTaskIds = restoreTaskIds,
                    activeChildrenCount = o.getInt("activeChildrenCount"),
                    totalMs = o.getLong("totalMs"),
                    lastStartedAtMs = if (o.isNull("lastStartedAtMs")) null else o.getLong("lastStartedAtMs")
                )
            )
        }
        return out
    }

    private fun JSONArray.toTaskSessions(): List<TaskSession> {
        val out = ArrayList<TaskSession>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            out.add(
                TaskSession(
                    taskId = o.getLong("taskId"),
                    taskName = o.getString("taskName"),
                    startTs = o.getLong("startTs"),
                    endTs = o.getLong("endTs")
                )
            )
        }
        return out
    }

    private fun JSONArray.toTagSessions(): List<TagSession> {
        val out = ArrayList<TagSession>(length())
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            out.add(
                TagSession(
                    tagId = o.getLong("tagId"),
                    tagName = o.getString("tagName"),
                    taskId = o.getLong("taskId"),
                    taskName = o.getString("taskName"),
                    startTs = o.getLong("startTs"),
                    endTs = o.getLong("endTs")
                )
            )
        }
        return out
    }
}
