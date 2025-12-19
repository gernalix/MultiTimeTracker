// v2
package com.example.multitimetracker.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile.provider.DocumentFile
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task

/**
 * Importa i CSV prodotti da [CsvExporter] e ricostruisce lo snapshot in RAM.
 *
 * File attesi (nomi esatti come export):
 * - sessions.csv
 * - totals.csv (opzionale, solo validazione)
 * - tag_sessions.csv
 * - tag_totals.csv (opzionale, solo validazione)
 */
object CsvImporter {

    data class ImportedSnapshot(
        val tasks: List<Task>,
        val tags: List<Tag>,
        val taskSessions: List<TaskSession>,
        val tagSessions: List<TagSession>
    )

    fun importFromUris(context: Context, uris: List<Uri>): ImportedSnapshot {
        if (uris.isEmpty()) throw IllegalArgumentException("Nessun file selezionato")

        val byName = uris.associateBy { uri -> displayName(context, uri) ?: uri.lastPathSegment.orEmpty() }

        val taskSessionsCsv = byName["sessions.csv"]
            ?: throw IllegalArgumentException("Manca sessions.csv")
        val tagSessionsCsv = byName["tag_sessions.csv"]
            ?: throw IllegalArgumentException("Manca tag_sessions.csv")

        val taskSessions = parseTaskSessions(readLines(context, taskSessionsCsv))
        val tagSessions = parseTagSessions(readLines(context, tagSessionsCsv))
        return buildSnapshot(taskSessions, tagSessions)
    }

    /**
     * Import directly from the persistent backup folder (MultiTimer data) without any file picker.
     */
    fun importFromBackupFolder(context: Context, dir: DocumentFile): ImportedSnapshot {
        val taskSessionsDoc = dir.findFile("sessions.csv")
            ?: throw IllegalArgumentException("Manca sessions.csv in '${dir.name ?: "backup"}'")
        val tagSessionsDoc = dir.findFile("tag_sessions.csv")
            ?: throw IllegalArgumentException("Manca tag_sessions.csv in '${dir.name ?: "backup"}'")

        val taskSessions = parseTaskSessions(readLines(context, taskSessionsDoc.uri))
        val tagSessions = parseTagSessions(readLines(context, tagSessionsDoc.uri))
        return buildSnapshot(taskSessions, tagSessions)
    }

    private fun buildSnapshot(taskSessions: List<TaskSession>, tagSessions: List<TagSession>): ImportedSnapshot {
        // Rebuild tags
        val tagsById = linkedMapOf<Long, String>()
        tagSessions.forEach { s ->
            tagsById.putIfAbsent(s.tagId, s.tagName)
        }
        val tags = tagsById.entries.map { (id, name) ->
            Tag(
                id = id,
                name = name,
                activeChildrenCount = 0,
                totalMs = 0L,
                lastStartedAtMs = null
            )
        }

        // Map task -> tagIds (from tag_sessions)
        val taskToTagIds = linkedMapOf<Long, MutableSet<Long>>()
        tagSessions.forEach { s ->
            taskToTagIds.getOrPut(s.taskId) { linkedSetOf() }.add(s.tagId)
        }

        // Task names: prefer sessions.csv, fallback to tag_sessions.csv
        val taskNames = linkedMapOf<Long, String>()
        taskSessions.forEach { s -> taskNames.putIfAbsent(s.taskId, s.taskName) }
        tagSessions.forEach { s -> taskNames.putIfAbsent(s.taskId, s.taskName) }

        // Totals from taskSessions (authoritative)
        val taskTotals = taskSessions
            .groupBy { it.taskId }
            .mapValues { (_, v) -> v.sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) } }

        val allTaskIds = (taskNames.keys + taskToTagIds.keys).toSortedSet()
        val tasks = allTaskIds.map { taskId ->
            Task(
                id = taskId,
                name = taskNames[taskId] ?: "task_$taskId",
                tagIds = taskToTagIds[taskId]?.toSet() ?: emptySet(),
                isRunning = false,
                totalMs = taskTotals[taskId] ?: 0L,
                lastStartedAtMs = null
            )
        }

        return ImportedSnapshot(
            tasks = tasks,
            tags = tags,
            taskSessions = taskSessions,
            tagSessions = tagSessions
        )
    }

    private fun displayName(context: Context, uri: Uri): String? {
        val cr = context.contentResolver
        val c = cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null) ?: return null
        c.use {
            if (!it.moveToFirst()) return null
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx < 0) return null
            return it.getString(idx)
        }
    }

    private fun readLines(context: Context, uri: Uri): List<String> {
        val cr = context.contentResolver
        cr.openInputStream(uri).use { input ->
            if (input == null) throw IllegalArgumentException("Impossibile leggere file: $uri")
            return input.bufferedReader(Charsets.UTF_8).readLines().filter { it.isNotBlank() }
        }
    }

    private fun parseTaskSessions(lines: List<String>): List<TaskSession> {
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().trim()
        if (header != "task_id,task_name,start_ts,end_ts") {
            throw IllegalArgumentException("Header sessions.csv non valido")
        }
        return lines.drop(1).mapNotNull { line ->
            val row = parseCsvLine(line)
            if (row.size < 4) return@mapNotNull null
            TaskSession(
                taskId = row[0].toLong(),
                taskName = row[1],
                startTs = row[2].toLong(),
                endTs = row[3].toLong()
            )
        }
    }

    private fun parseTagSessions(lines: List<String>): List<TagSession> {
        if (lines.isEmpty()) return emptyList()
        val header = lines.first().trim()
        if (header != "tag_id,tag_name,task_id,task_name,start_ts,end_ts") {
            throw IllegalArgumentException("Header tag_sessions.csv non valido")
        }
        return lines.drop(1).mapNotNull { line ->
            val row = parseCsvLine(line)
            if (row.size < 6) return@mapNotNull null
            TagSession(
                tagId = row[0].toLong(),
                tagName = row[1],
                taskId = row[2].toLong(),
                taskName = row[3],
                startTs = row[4].toLong(),
                endTs = row[5].toLong()
            )
        }
    }

    /**
     * CSV parser minimale compatibile con [CsvExporter.csvEscape]:
     * - campi separati da virgola
     * - stringhe tra virgolette con "" come escape
     */
    private fun parseCsvLine(line: String): List<String> {
        val out = ArrayList<String>(8)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    // escaped quote?
                    val next = if (i + 1 < line.length) line[i + 1] else null
                    if (next == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i++
                    continue
                } else {
                    sb.append(c)
                    i++
                    continue
                }
            } else {
                when (c) {
                    ',' -> {
                        out.add(sb.toString())
                        sb.setLength(0)
                        i++
                    }
                    '"' -> {
                        inQuotes = true
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
            }
        }
        out.add(sb.toString())
        return out
    }
}