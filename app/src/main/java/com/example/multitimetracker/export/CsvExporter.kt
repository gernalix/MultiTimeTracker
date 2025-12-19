// v12
package com.example.multitimetracker.export

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileWriter
import java.io.OutputStreamWriter

data class TaskSession(
    val taskId: Long,
    val taskName: String,
    val startTs: Long,
    val endTs: Long
)

data class TagSession(
    val tagId: Long,
    val tagName: String,
    val taskId: Long,
    val taskName: String,
    val startTs: Long,
    val endTs: Long
)

private fun csvEscape(s: String): String {
    // RFC4180-ish: if the field contains a quote, escape it by doubling.
    // Always wrap in quotes (simple + safe for commas/newlines).
    val out = StringBuilder(s.length + 8)
    for (c in s) {
        if (c == '"') out.append("\"\"") else out.append(c)
    }
    return "\"$out\""
}

object CsvExporter {

    /**
     * Writes CSV content to a file inside a SAF directory (DocumentFile).
     * Uses a truncate write ("wt") so the result is always coherent.
     */
    private fun writeCsvToDir(context: Context, dir: DocumentFile, fileName: String, writeBody: (Appendable) -> Unit) {
        val cr = context.contentResolver
        val existing = dir.findFile(fileName)
        val target = existing ?: dir.createFile("text/csv", fileName)
        requireNotNull(target) { "Impossibile creare file: $fileName" }

        cr.openOutputStream(target.uri, "wt")?.use { out: java.io.OutputStream ->
            requireNotNull(out) { "Impossibile aprire output stream per: $fileName" }
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                writeBody(w)
                w.flush()
            }
        } ?: throw IllegalStateException("Impossibile aprire output stream per: $fileName")
    }


    /**
     * Writes text content to a file inside a SAF directory (DocumentFile).
     * Uses a truncate write ("wt") so the result is always coherent.
     */
    private fun writeTextToDir(context: Context, dir: DocumentFile, mime: String, fileName: String, writeBody: (Appendable) -> Unit) {
        val cr = context.contentResolver
        val existing = dir.findFile(fileName)
        val target = existing ?: dir.createFile(mime, fileName)
        requireNotNull(target) { "Impossibile creare file: $fileName" }

        cr.openOutputStream(target.uri, "wt")?.use { out: java.io.OutputStream ->
            requireNotNull(out) { "Impossibile aprire output stream per: $fileName" }
            OutputStreamWriter(out, Charsets.UTF_8).use { w ->
                writeBody(w)
                w.flush()
            }
        } ?: throw IllegalStateException("Impossibile aprire output stream per: $fileName")
    }

    /**
     * Full export into a directory called from automatic backup/import flows.
     */
    fun exportAllToDirectory(context: Context, dir: DocumentFile, tasks: List<Task>, tags: List<Tag>, taskSessions: List<TaskSession>, tagSessions: List<TagSession>) {

        // 5° file: dict.json (anagrafica task/tag + associazioni task↔tag), leggibile da umani.
        writeTextToDir(context, dir, "application/json", "dict.json") { w ->
            val root = JSONObject()
            root.put("schema_version", 1)
            root.put("exported_at", System.currentTimeMillis())

            val tagsArr = JSONArray()
            tags.sortedBy { it.id }.forEach { t ->
                val o = JSONObject()
                o.put("id", t.id)
                o.put("name", t.name)
                tagsArr.put(o)
            }
            root.put("tags", tagsArr)

            val tasksArr = JSONArray()
            tasks.sortedBy { it.id }.forEach { t ->
                val o = JSONObject()
                o.put("id", t.id)
                o.put("name", t.name)
                val tagIdsArr = JSONArray()
                t.tagIds.sorted().forEach { tagIdsArr.put(it) }
                o.put("tagIds", tagIdsArr)
                tasksArr.put(o)
            }
            root.put("tasks", tasksArr)

            w.append(root.toString(2))
            w.appendLine()
        }

        writeCsvToDir(context, dir, "sessions.csv") { w ->
            w.appendLine("task_id,task_name,start_ts,end_ts")
            taskSessions.forEach { s ->
                w.appendLine("${s.taskId},${csvEscape(s.taskName)},${s.startTs},${s.endTs}")
            }
        }

        writeCsvToDir(context, dir, "totals.csv") { w ->
            val totals = taskSessions
                .groupBy { it.taskId to it.taskName }
                .mapValues { (_, v) -> v.sumOf { it.endTs - it.startTs } }
            w.appendLine("task_id,task_name,total_ms")
            totals.forEach { (k, total) ->
                w.appendLine("${k.first},${csvEscape(k.second)},$total")
            }
        }

        writeCsvToDir(context, dir, "tag_sessions.csv") { w ->
            w.appendLine("tag_id,tag_name,task_id,task_name,start_ts,end_ts")
            tagSessions.forEach { s ->
                w.appendLine(
                    "${s.tagId},${csvEscape(s.tagName)}," +
                        "${s.taskId},${csvEscape(s.taskName)}," +
                        "${s.startTs},${s.endTs}"
                )
            }
        }

        writeCsvToDir(context, dir, "tag_totals.csv") { w ->
            // Totale tag = SUM dei totali dei task che lo alimentano.
            val perTask = tagSessions
                .groupBy { Triple(it.tagId, it.tagName, it.taskId to it.taskName) }
                .mapValues { (_, v) -> v.sumOf { it.endTs - it.startTs } }

            val totals = perTask
                .entries
                .groupBy({ it.key.first to it.key.second }, { it.value })
                .mapValues { (_, v) -> v.sumOf { it } }

            w.appendLine("tag_id,tag_name,total_ms")
            totals.forEach { (k, total) ->
                w.appendLine("${k.first},${csvEscape(k.second)},$total")
            }
        }
    }

    fun exportTaskSessions(context: Context, sessions: List<TaskSession>): File {
        val file = File(context.getExternalFilesDir(null), "sessions.csv")
        FileWriter(file).use { w ->
            w.appendLine("task_id,task_name,start_ts,end_ts")
            sessions.forEach { s ->
                w.appendLine("${s.taskId},${csvEscape(s.taskName)},${s.startTs},${s.endTs}")
            }
        }
        return file
    }

    fun exportTaskTotals(context: Context, sessions: List<TaskSession>): File {
        val totals = sessions
            .groupBy { it.taskId to it.taskName }
            .mapValues { (_, v) -> v.sumOf { it.endTs - it.startTs } }

        val file = File(context.getExternalFilesDir(null), "totals.csv")
        FileWriter(file).use { w ->
            w.appendLine("task_id,task_name,total_ms")
            totals.forEach { (k, total) ->
                w.appendLine("${k.first},${csvEscape(k.second)},$total")
            }
        }
        return file
    }

    fun exportTagSessions(context: Context, tagSessions: List<TagSession>): File {
        val file = File(context.getExternalFilesDir(null), "tag_sessions.csv")
        FileWriter(file).use { w ->
            w.appendLine("tag_id,tag_name,task_id,task_name,start_ts,end_ts")
            tagSessions.forEach { s ->
                w.appendLine(
                    "${s.tagId},${csvEscape(s.tagName)}," +
                        "${s.taskId},${csvEscape(s.taskName)}," +
                        "${s.startTs},${s.endTs}"
                )
            }
        }
        return file
    }

    fun exportTagTotals(
        context: Context,
        tags: List<Tag>,
        tasks: List<Task>,
        taskSessions: List<TaskSession>,
        nowMs: Long
    ): File {
        // Totale tag = UNION cronologica delle sessioni dei task che hanno quel tag.
        // Le sovrapposizioni contano una sola volta.

        val taskTagsById: Map<Long, Set<Long>> = tasks.associate { it.id to it.tagIds }
        val intervalsByTag = mutableMapOf<Long, MutableList<Pair<Long, Long>>>()

        fun addInterval(tagId: Long, start: Long, end: Long) {
            if (end <= start) return
            intervalsByTag.getOrPut(tagId) { mutableListOf() }.add(start to end)
        }

        // Sessioni chiuse dei task
        taskSessions.forEach { s ->
            val tagIds = taskTagsById[s.taskId] ?: return@forEach
            tagIds.forEach { tagId -> addInterval(tagId, s.startTs, s.endTs) }
        }

        // Sessioni aperte (task running)
        tasks.filter { it.isRunning && it.lastStartedAtMs != null }.forEach { t ->
            val start = t.lastStartedAtMs ?: return@forEach
            t.tagIds.forEach { tagId -> addInterval(tagId, start, nowMs) }
        }

        fun unionTotalMs(intervals: List<Pair<Long, Long>>): Long {
            if (intervals.isEmpty()) return 0L
            val sorted = intervals.sortedBy { it.first }
            var total = 0L
            var curStart = sorted[0].first
            var curEnd = sorted[0].second
            for (i in 1 until sorted.size) {
                val (s, e) = sorted[i]
                if (s <= curEnd) {
                    if (e > curEnd) curEnd = e
                } else {
                    total += (curEnd - curStart)
                    curStart = s
                    curEnd = e
                }
            }
            total += (curEnd - curStart)
            return total
        }

        val out = File(context.cacheDir, "tag_totals.csv")
        FileWriter(out).use { w ->
            w.appendLine("tag_id,tag_name,total_ms")
            tags.sortedBy { it.id }.forEach { tag ->
                val intervals = intervalsByTag[tag.id] ?: emptyList()
                val totalMs = unionTotalMs(intervals)
                w.appendLine("${tag.id},${csvEscape(tag.name)},$totalMs")
            }
        }
        return out
    }
}