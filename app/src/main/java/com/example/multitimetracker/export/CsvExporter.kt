// v4
package com.example.multitimetracker.export

import android.content.Context
import java.io.File
import java.io.FileWriter

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
    // Safe escaping without relying on String.replace overload resolution
    val out = StringBuilder(s.length + 8)
    for (c in s) {
        if (c == '"') out.append("""") else out.append(c)
    }
    return ""${out}""
}

object CsvExporter {

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
        val totals = tagSessions
            .groupBy { it.tagId to it.tagName }
            .mapValues { (_, v) -> v.sumOf { it.endTs - it.startTs } }

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
