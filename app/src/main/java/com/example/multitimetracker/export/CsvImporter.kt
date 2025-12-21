// v14
package com.example.multitimetracker.export

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine

/**
 * Importa i CSV prodotti da [CsvExporter] e ricostruisce lo snapshot in RAM.
 *
 * File attesi (nomi esatti come export):
 * - sessions.csv
 * - totals.csv (opzionale, solo validazione)
 * - tag_sessions.csv
 * - tag_totals.csv (opzionale, solo validazione)
 * - app_usage.csv (opzionale)
 * - dict.json (opzionale ma consigliato: anagrafica task/tag + associazioni)
 */
object CsvImporter {
    private fun pickByNameOrPrefix(byLowerName: Map<String, Uri>, exactName: String): Uri? {
        val key = exactName.lowercase()
        byLowerName[key]?.let { return it }
        // Tolleranza: file duplicati come "app_usage (1).csv" oppure nomi case-insensitive.
        return byLowerName.entries.firstOrNull { (k, _) -> k.startsWith(key.removeSuffix(".csv")) }?.value
    }

    private fun pickDocByNameOrPrefix(byLowerName: Map<String, DocumentFile>, exactName: String): DocumentFile? {
        val key = exactName.lowercase()
        byLowerName[key]?.let { return it }
        return byLowerName.entries.firstOrNull { (k, _) -> k.startsWith(key.removeSuffix(".csv")) }?.value
    }

    private const val LOG_TAG = "MT_IMPORT"

    private fun i(msg: String) = Log.i(LOG_TAG, msg)
    private fun w(msg: String) = Log.w(LOG_TAG, msg)
    private fun e(msg: String, tr: Throwable? = null) {
        if (tr != null) Log.e(LOG_TAG, msg, tr) else Log.e(LOG_TAG, msg)
    }

    private data class DictPayload(
        val tasks: List<Task>,
        val tags: List<Tag>
    )

    data class ImportedSnapshot(
        val tasks: List<Task>,
        val tags: List<Tag>,
        val taskSessions: List<TaskSession>,
        val tagSessions: List<TagSession>,
        val appUsageMs: Long,
        val runtimeSnapshot: TimeEngine.RuntimeSnapshot?
    )

    
fun importFromUris(context: Context, uris: List<Uri>): ImportedSnapshot {
    if (uris.isEmpty()) throw IllegalArgumentException("Nessun file selezionato")

    i("importFromUris: START uris=${uris.size}")

    val byLowerName = uris.associateBy { uri -> (displayName(context, uri) ?: uri.lastPathSegment.orEmpty()).lowercase() }
    i("importFromUris: files=${byLowerName.keys.sorted().joinToString(",")}")

    // Manifest-driven import (preferred). Falls back to legacy behavior when manifest is missing.
    val manifestUri = pickByNameOrPrefix(byLowerName, BackupSchema.MANIFEST_FILE)
    val manifest = manifestUri?.let { uri ->
        runCatching { BackupSchema.parseManifestJson(readText(context, uri)) }
            .onFailure { ex -> e("importFromUris: manifest.json parse failed uri=$uri", ex) }
            .getOrNull()
    }
    val manifestFiles: List<BackupSchema.Entry> = manifest?.files?.takeIf { it.isNotEmpty() } ?: BackupSchema.entries

    var dict: DictPayload? = null
    var taskSessions: List<TaskSession> = emptyList()
    var tagSessions: List<TagSession> = emptyList()
    var appUsageMs: Long = 0L

    for (entry in manifestFiles) {
        val uri = pickByNameOrPrefix(byLowerName, entry.name) ?: run {
            if (entry.required) throw IllegalArgumentException("Manca ${entry.name}")
            continue
        }

        when (entry.handlerId) {
            "dict_v1" -> {
                dict = runCatching { parseDictJson(readText(context, uri)) }
                    .onFailure { ex -> e("importFromUris: dict.json parse failed uri=$uri", ex) }
                    .getOrNull()
            }
            "sessions_v1" -> taskSessions = parseTaskSessions(readLines(context, uri))
            "tag_sessions_v1" -> tagSessions = parseTagSessions(readLines(context, uri))
            "app_usage_v1" -> appUsageMs = parseAppUsage(readLines(context, uri))
            else -> {
                // ignore (e.g., totals.csv validation-only)
            }
        }
    }

    i(
        "importFromUris: manifest=${if (manifest == null) "NO" else "YES"} " +
            "tasks=${dict?.tasks?.size ?: 0} tags=${dict?.tags?.size ?: 0}"
    )
    i("importFromUris: sessions.csv rows=${taskSessions.size} tag_sessions.csv rows=${tagSessions.size}")

    // Extra-file diagnostics (only meaningful when manifest exists)
    if (manifest != null) {
        val known = manifestFiles.map { it.name.lowercase() }.toSet() + BackupSchema.MANIFEST_FILE.lowercase()
        val extras = byLowerName.keys.filter { it !in known }.sorted()
        if (extras.isNotEmpty()) w("importFromUris: extra files not in manifest: ${extras.joinToString(",")}")
    }

    val out = buildSnapshot(
        baseTasks = dict?.tasks,
        baseTags = dict?.tags,
        taskSessions = taskSessions,
        tagSessions = tagSessions,
        appUsageMs = appUsageMs
    )

    i(
        "importFromUris: END tasks=${out.tasks.size} tags=${out.tags.size} " +
            "taskSessions=${out.taskSessions.size} tagSessions=${out.tagSessions.size} " +
            "runtimeSnapshot=${out.runtimeSnapshot != null}"
    )
    return out
}

/**
     * Import directly from the persistent backup folder (MultiTimer data) without any file picker.
     */
    
fun importFromBackupFolder(context: Context, dir: DocumentFile): ImportedSnapshot {
    i("importFromBackupFolder: START dir='${dir.name ?: "(null)"}' uri=${dir.uri}")

    val byLowerName: Map<String, DocumentFile> = dir.listFiles()
        .filter { it.name != null }
        .associateBy { it.name!!.lowercase() }

    val manifestDoc = pickDocByNameOrPrefix(byLowerName, BackupSchema.MANIFEST_FILE)
    val manifest = manifestDoc
        ?.takeIf { it.canRead() }
        ?.let { doc -> runCatching { BackupSchema.parseManifestJson(readText(context, doc.uri)) }.getOrNull() }

    val manifestFiles: List<BackupSchema.Entry> = manifest?.files?.takeIf { it.isNotEmpty() } ?: BackupSchema.entries

    var dict: DictPayload? = null
    var taskSessions: List<TaskSession> = emptyList()
    var tagSessions: List<TagSession> = emptyList()
    var appUsageMs: Long = 0L

    for (entry in manifestFiles) {
        val doc = pickDocByNameOrPrefix(byLowerName, entry.name) ?: run {
            if (entry.required) throw IllegalArgumentException("Manca ${entry.name} in '${dir.name ?: "backup"}'")
            continue
        }
        if (!doc.canRead()) continue

        when (entry.handlerId) {
            "dict_v1" -> dict = runCatching { parseDictJson(readText(context, doc.uri)) }.getOrNull()
            "sessions_v1" -> taskSessions = parseTaskSessions(readLines(context, doc.uri))
            "tag_sessions_v1" -> tagSessions = parseTagSessions(readLines(context, doc.uri))
            "app_usage_v1" -> appUsageMs = parseAppUsage(readLines(context, doc.uri))
            else -> {
                // ignore
            }
        }
    }

    i(
        "importFromBackupFolder: manifest=${if (manifest == null) "NO" else "YES"} " +
            "tasks=${dict?.tasks?.size ?: 0} tags=${dict?.tags?.size ?: 0}"
    )
    i("importFromBackupFolder: sessions.csv rows=${taskSessions.size} tag_sessions.csv rows=${tagSessions.size}")

    if (manifest != null) {
        val known = manifestFiles.map { it.name.lowercase() }.toSet() + BackupSchema.MANIFEST_FILE.lowercase()
        val extras = byLowerName.keys.filter { it !in known }.sorted()
        if (extras.isNotEmpty()) w("importFromBackupFolder: extra files not in manifest: ${extras.joinToString(",")}")
    }

    val out = buildSnapshot(
        baseTasks = dict?.tasks,
        baseTags = dict?.tags,
        taskSessions = taskSessions,
        tagSessions = tagSessions,
        appUsageMs = appUsageMs
    )

    i(
        "importFromBackupFolder: END tasks=${out.tasks.size} tags=${out.tags.size} " +
            "taskSessions=${out.taskSessions.size} tagSessions=${out.tagSessions.size} " +
            "runtimeSnapshot=${out.runtimeSnapshot != null}"
    )
    return out
}

private fun buildSnapshot(
        baseTasks: List<Task>?,
        baseTags: List<Tag>?,
        taskSessions: List<TaskSession>,
        tagSessions: List<TagSession>,
        appUsageMs: Long
    ): ImportedSnapshot {

        i(
            "buildSnapshot: START baseTasks=${baseTasks?.size ?: 0} baseTags=${baseTags?.size ?: 0} " +
                "taskSessions=${taskSessions.size} tagSessions=${tagSessions.size}"
        )

        // Start from dict.json if present (authoritative for structure).
        val tagsById: LinkedHashMap<Long, Tag> = linkedMapOf()
        val tasksById: LinkedHashMap<Long, Task> = linkedMapOf()

        baseTags?.forEach { t ->
            // Keep totals + lastStartedAtMs from dict.json; just reset UI-only fields.
            tagsById[t.id] = t.copy(activeChildrenCount = 0)
        }
        baseTasks?.forEach { t ->
            // Keep isRunning/totalMs/lastStartedAtMs from dict.json (authoritative).
            tasksById[t.id] = t
        }

        i("buildSnapshot: after dict.json -> tasks=${tasksById.size} tags=${tagsById.size}")

        // Fallback/merge from tag_sessions.csv (legacy support)
        val createdTasksFromTagSessions = ArrayList<Long>(16)
        val createdTagsFromTagSessions = ArrayList<Long>(16)
        tagSessions.forEach { s ->
            if (!tagsById.containsKey(s.tagId)) {
                tagsById[s.tagId] = Tag(
                    id = s.tagId,
                    name = s.tagName,
                    activeChildrenCount = 0,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
                createdTagsFromTagSessions.add(s.tagId)
            }
            if (!tasksById.containsKey(s.taskId)) {
                tasksById[s.taskId] = Task(
                    id = s.taskId,
                    name = s.taskName,
                    tagIds = setOf(s.tagId),
                    link = "",
                    isRunning = false,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
                createdTasksFromTagSessions.add(s.taskId)
            } else {
                // Ensure the association is present (dict might be missing it).
                val cur = tasksById[s.taskId]!!
                if (!cur.tagIds.contains(s.tagId)) {
                    tasksById[s.taskId] = cur.copy(tagIds = cur.tagIds + s.tagId)
                }
            }
        }

        if (createdTagsFromTagSessions.isNotEmpty() || createdTasksFromTagSessions.isNotEmpty()) {
            w(
                "buildSnapshot: created from tag_sessions.csv -> " +
                    "tags=${createdTagsFromTagSessions.size} tasks=${createdTasksFromTagSessions.size} " +
                    "(firstTags=${createdTagsFromTagSessions.take(12)}, firstTasks=${createdTasksFromTagSessions.take(12)})"
            )
        }

        // Also merge task names from sessions.csv if needed.
        val createdTasksFromTaskSessions = ArrayList<Long>(16)
        taskSessions.forEach { s ->
            val cur = tasksById[s.taskId]
            if (cur == null) {
                tasksById[s.taskId] = Task(
                    id = s.taskId,
                    name = s.taskName,
                    tagIds = emptySet(),
                    link = "",
                    isRunning = false,
                    totalMs = 0L,
                    lastStartedAtMs = null
                )
                createdTasksFromTaskSessions.add(s.taskId)
            } else if (cur.name.startsWith("task_")) {
                tasksById[s.taskId] = cur.copy(name = s.taskName)
            }
        }

        if (createdTasksFromTaskSessions.isNotEmpty()) {
            w(
                "buildSnapshot: created from sessions.csv -> tasks=${createdTasksFromTaskSessions.size} " +
                    "(firstTasks=${createdTasksFromTaskSessions.take(12)})"
            )
        }

        i("buildSnapshot: after merges -> tasks=${tasksById.size} tags=${tagsById.size}")

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
            .map { t -> t.copy(totalMs = taskTotals[t.id] ?: t.totalMs) }

        val tags = tagsById.values
            .sortedBy { it.id }
            .map { t -> t.copy(totalMs = tagTotals[t.id] ?: t.totalMs, activeChildrenCount = 0) }

        val activeTaskStart = tasks
            .filter { it.isRunning && it.lastStartedAtMs != null }
            .associate { it.id to (it.lastStartedAtMs ?: 0L) }

        val activeTagStart = tasks
            .filter { it.isRunning && it.lastStartedAtMs != null }
            .flatMap { t ->
                val start = t.lastStartedAtMs ?: return@flatMap emptyList()
                t.tagIds.map { tagId -> Triple(t.id, tagId, start) }
            }

        val runtime = if (activeTaskStart.isEmpty() && activeTagStart.isEmpty()) {
            null
        } else {
            TimeEngine.RuntimeSnapshot(activeTaskStart = activeTaskStart, activeTagStart = activeTagStart)
        }

        i(
            "buildSnapshot: END tasks=${tasks.size} tags=${tags.size} " +
                "taskTotalsKeys=${taskTotals.size} tagTotalsKeys=${tagTotals.size} runtime=${runtime != null}"
        )

        return ImportedSnapshot(
            tasks = tasks,
            tags = tags,
            taskSessions = taskSessions,
            tagSessions = tagSessions,
            appUsageMs = appUsageMs,
            runtimeSnapshot = runtime
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
            val totalMs = o.optLong("totalMs", 0L)
            val lastStarted = if (o.isNull("lastStartedAtMs")) null else o.optLong("lastStartedAtMs")
            tags.add(
                Tag(
                    id = id,
                    name = name,
                    activeChildrenCount = 0,
                    totalMs = totalMs,
                    lastStartedAtMs = lastStarted
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

            val isRunning = o.optBoolean("isRunning", false)
            val totalMs = o.optLong("totalMs", 0L)
            val lastStarted = if (o.isNull("lastStartedAtMs")) null else o.optLong("lastStartedAtMs")
            tasks.add(
                Task(
                    id = id,
                    name = name,
                    link = taskLink,
                    tagIds = tagIds,
                    isRunning = isRunning,
                    totalMs = totalMs,
                    lastStartedAtMs = lastStarted
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

    private fun parseAppUsage(lines: List<String>): Long {
        if (lines.isEmpty()) return 0L
        val header = lines.first().trim()
        if (header != "total_ms") {
            throw IllegalArgumentException("Header app_usage.csv non valido")
        }
        val valueLine = lines.drop(1).firstOrNull()?.trim().orEmpty()
        if (valueLine.isBlank()) return 0L
        return runCatching { valueLine.toLong() }.getOrDefault(0L)
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