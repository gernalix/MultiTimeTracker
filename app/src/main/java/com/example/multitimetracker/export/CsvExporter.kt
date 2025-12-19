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


private fun unionDurationMs(intervals: List<Pair<Long, Long>>): Long {
    if (intervals.isEmpty()) return 0L
    val sorted = intervals
        .filter { (s, e) -> e > s }
        .sortedBy { it.first }

    if (sorted.isEmpty()) return 0L

    var curStart = sorted[0].first
    var curEnd = sorted[0].second
    var total = 0L

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
    fun exportAllToDirectory(
        context: Context,
        dir: DocumentFile,
        tasks: List<Task>,
        tags: List<Tag>,
        taskSessions: List<TaskSession>,
        tagSessions: List<TagSession>,
        activeTagStart: List<Triple<Long, Long, Long>>,
        nowMs: Long
    ) {

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
            // Totale tag = UNION cronologica delle sessioni dei task figli (overlap conta una volta).
            val intervalsByTag = mutableMapOf<Pair<Long, String>, MutableList<Pair<Long, Long>>>()

            // Sessioni chiuse (storiche)
            tagSessions.forEach { s ->
                val key = s.tagId to s.tagName
                intervalsByTag.getOrPut(key) { mutableListOf() }.add(s.startTs to s.endTs)
            }

            // Sessioni ancora aperte (task/tag running)
            activeTagStart.forEach { (_, tagId, startTs) ->
                val tagName = tags.firstOrNull { it.id == tagId }?.name ?: ""
                val key = tagId to tagName
                intervalsByTag.getOrPut(key) { mutableListOf() }.add(startTs to nowMs)
            }

            w.appendLine("tag_id,tag_name,total_ms,total_hhmmss")
            intervalsByTag.entries
                .sortedBy { it.key.first }
                .forEach { (k, intervals) ->
                    val total = unionDurationMs(intervals)
                    w.appendLine("${k.first},${csvEscape(k.second)},$total,${formatHhMmSs(total)}")
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

    fun exportTagTotals(context: Context, tagSessions: List<TagSession>): File {
        // Totale tag = SUM tra i task che lo alimentano.
        // 1) somma per (tag, task)
        val perTask = tagSessions
            .groupBy { Triple(it.tagId, it.tagName, it.taskId to it.taskName) }
            .mapValues { (_, v) -> v.sumOf { it.endTs - it.startTs } }

        // 2) sum per tag
        val totals = perTask
            .entries
            .groupBy({ it.key.first to it.key.second }, { it.value })
            .mapValues { (_, v) -> v.sumOf { it } }

        val file = File(context.getExternalFilesDir(null), "tag_totals.csv")
        FileWriter(file).use { w ->
            w.appendLine("tag_id,tag_name,total_ms")
            totals.forEach { (k, total) ->
                w.appendLine("${k.first},${csvEscape(k.second)},$total")
            }
        }
        return file
    }
}