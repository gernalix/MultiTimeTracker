// v7
package com.example.multitimetracker.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
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
 * - dict.json (opzionale ma consigliato: anagrafica task/tag + associazioni)
 */
object CsvImporter {

    private data class DictPayload(
        val tasks: List<Task>,
        val tags: List<Tag>
    )

    data class ImportedSnapshot(
        val tasks: List<Task>,
        val tags: List<Tag>,
        val taskSessions: List<TaskSession>,
        val tagSessions: List<TagSession>
    )

    fun importFromUris(context: Context, uris: List<Uri>): ImportedSnapshot {
        if (uris.isEmpty()) throw IllegalArgumentException("Nessun file selezionato")

        val byName = uris.associateBy { uri -> displayName(context, uri) ?: uri.lastPathSegment.orEmpty() }

        val dictUri = byName["dict.json"]
        val dict: DictPayload? = dictUri?.let { uri -> parseDictJson(readText(context, uri)) }

        val taskSessions = byName["sessions.csv"]?.let { parseTaskSessions(readLines(context, it)) } ?: emptyList()
        val tagSessions = byName["tag_sessions.csv"]?.let { parseTagSessions(readLines(context, it)) } ?: emptyList()

        if (dict == null && (byName["sessions.csv"] == null || byName["tag_sessions.csv"] == null)) {
            // Legacy import: without dict.json we need both session files to reconstruct tasks/tags.
            if (byName["sessions.csv"] == null) throw IllegalArgumentException("Manca sessions.csv")
            if (byName["tag_sessions.csv"] == null) throw IllegalArgumentException("Manca tag_sessions.csv")
        }

        return buildSnapshot(
            baseTasks = dict?.tasks,
            baseTags = dict?.tags,
            taskSessions = taskSessions,
            tagSessions = tagSessions
        )
    }


    /**
     * Import directly from the persistent backup folder (MultiTimer data) without any file picker.
     */
    fun importFromBackupFolder(context: Context, dir: DocumentFile): ImportedSnapshot {
        val dictDoc = dir.findFile("dict.json")
        val dict: DictPayload? = dictDoc?.let { doc: DocumentFile ->
            if (!doc.canRead()) null else runCatching { parseDictJson(readText(context, doc.uri)) }.getOrNull()
        }

        val taskSessionsDoc = dir.findFile("sessions.csv")
        val tagSessionsDoc = dir.findFile("tag_sessions.csv")

        val taskSessions = taskSessionsDoc?.let { parseTaskSessions(readLines(context, it.uri)) } ?: emptyList()
        val tagSessions = tagSessionsDoc?.let { parseTagSessions(readLines(context, it.uri)) } ?: emptyList()

        if (dict == null && (taskSessionsDoc == null || tagSessionsDoc == null)) {
            if (taskSessionsDoc == null) throw IllegalArgumentException("Manca sessions.csv in '${dir.name ?: "backup"}'")
            if (tagSessionsDoc == null) throw IllegalArgumentException("Manca tag_sessions.csv in '${dir.name ?: "backup"}'")
        }

        return buildSnapshot(
            baseTasks = dict?.tasks,
            baseTags = dict?.tags,
            taskSessions = taskSessions,
            tagSessions = tagSessions
        )
    }


    private fun buildSnapshot(
        baseTasks: List<Task>?,
        baseTags: List<Tag>?,
        taskSessions: List<TaskSession>,
        tagSessions: List<TagSession>
    ): ImportedSnapshot {

        // Start from dict.json if present (authoritative for structure).
        val tagsById: LinkedHashMap<Long, Tag> = linkedMapOf()
        val tasksById: LinkedHashMap<Long, Task> = linkedMapOf()

        baseTags?.forEach { t ->
            tagsById[t.id] = t.copy(activeChildrenCount = 0, totalMs = 0L, lastStartedAtMs = null)
        }
        baseTasks?.forEach { t ->
            tasksById[t.id] = t.copy(isRunning = false, totalMs = 0L, lastStartedAtMs = null)
        }

        // Fallback/merge from tag_sessions.csv (legacy support)
        tagSessions.forEach { s ->
            if (!tagsById.containsKey(s.tagId)) {
                tagsById[s.tagId] = Tag(
                    id = s.tagId,
                    name = s.tagName,
                    activeChildrenCount = 0,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
            }
            if (!tasksById.containsKey(s.taskId)) {
                tasksById[s.taskId] = Task(
                    id = s.taskId,
                    name = s.taskName,
                    tagIds = setOf(s.tagId),
                    isRunning = false,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
            } else {
                // Ensure the association is present (dict might be missing it).
                val cur = tasksById[s.taskId]!!
                if (!cur.tagIds.contains(s.tagId)) {
                    tasksById[s.taskId] = cur.copy(tagIds = cur.tagIds + s.tagId)
                }
            }
        }

        // Also merge task names from sessions.csv if needed.
        taskSessions.forEach { s ->
            val cur = tasksById[s.taskId]
            if (cur == null) {
                tasksById[s.taskId] = Task(
                    id = s.taskId,
                    name = s.taskName,
                    tagIds = emptySet(),
                    isRunning = false,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
            } else if (cur.name.startsWith("task_")) {
                tasksById[s.taskId] = cur.copy(name = s.taskName)
            }
        }

        // Totals (authoritative from sessions content)
        val taskTotals = taskSessions
            .groupBy { it.taskId }
            .mapValues { (_, v) -> v.sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) } }

        val tagTotals = tagSessions
            .groupBy { it.tagId }
            .mapValues { (_, v) -> v.sumOf { (it.endTs - it.startTs).coerceAtLeast(0L) } }

        // Apply totals
        val tasks = tasksById.values
            .sortedBy { it.id }
            .map { t -> t.copy(totalMs = taskTotals[t.id] ?: 0L, isRunning = false, lastStartedAtMs = null) }

        val tags = tagsById.values
            .sortedBy { it.id }
            .map { t -> t.copy(totalMs = tagTotals[t.id] ?: 0L, activeChildrenCount = 0, lastStartedAtMs = null) }

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

    private fun readText(context: Context, uri: Uri): String {
        val cr = context.contentResolver
        cr.openInputStream(uri).use { input ->
            if (input == null) throw IllegalArgumentException("Impossibile leggere file: $uri")
            return input.bufferedReader(Charsets.UTF_8).readText()
        }
    }

    private fun parseDictJson(text: String): DictPayload {
        val root = JSONObject(text)

        val tagsJson = root.optJSONArray("tags") ?: JSONArray()
        val tags = ArrayList<Tag>(tagsJson.length())
        for (i in 0 until tagsJson.length()) {
            val o = tagsJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val name = o.optString("name", "").trim()
            val link = o.optString("link", "").trim()
            if (id == Long.MIN_VALUE || name.isBlank()) continue
            tags.add(
                Tag(
                    id = id,
                    name = name,
                    activeChildrenCount = 0,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
            )
        }

        val tasksJson = root.optJSONArray("tasks") ?: JSONArray()
        val tasks = ArrayList<Task>(tasksJson.length())
        for (i in 0 until tasksJson.length()) {
            val o = tasksJson.optJSONObject(i) ?: continue
            val id = o.optLong("id", Long.MIN_VALUE)
            val name = o.optString("name", "").trim()
            val taskLink = o.optString("link", "").trim()
            if (id == Long.MIN_VALUE || name.isBlank()) continue

            val tagIdsJson = o.optJSONArray("tagIds")
            val tagIds = LinkedHashSet<Long>()
            if (tagIdsJson != null) {
                for (j in 0 until tagIdsJson.length()) {
                    val v = tagIdsJson.optLong(j, Long.MIN_VALUE)
                    if (v != Long.MIN_VALUE) tagIds.add(v)
                }
            }

            tasks.add(
                Task(
                    id = id,
                    name = name,
                    link = taskLink,
                    tagIds = tagIds,
                    isRunning = false,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
            )
        }

        return DictPayload(tasks = tasks, tags = tags)
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