// v27
package com.example.multitimetracker

import android.content.Context
import android.net.Uri
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.multitimetracker.export.BackupFolderStore
import com.example.multitimetracker.export.CsvExporter
import com.example.multitimetracker.export.CsvImporter
import com.example.multitimetracker.export.ShareUtils
import com.example.multitimetracker.model.Tag
import com.example.multitimetracker.model.Task
import com.example.multitimetracker.model.TimeEngine
import com.example.multitimetracker.model.UiState
import com.example.multitimetracker.persistence.SnapshotStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private companion object {
        private const val LOG_TAG = "MT_IMPORT"
    }

    private val engine = TimeEngine()

    private var appContext: Context? = null
    private var initialized = false

    // Foreground app usage tracking (UI only). Persisted into snapshot + CSV.
    private var appForegroundStartMs: Long? = null

    /**
     * Allows MainActivity to provide an application context before the main initialize()
     * (useful for first-run setup/import flows).
     */
    fun bindContext(context: Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    // Debounced auto-backup to SAF folder (MultiTimer data).
    private var autoBackupJob: Job? = null
    private var lastBackupSignature: String? = null

    private val _state = MutableStateFlow(
        UiState(
            tasks = emptyList(),
            tags = emptyList(),
            taskSessions = emptyList(),
            tagSessions = emptyList(),
            appUsageMs = 0L,
            installAtMs = System.currentTimeMillis(),
            nowMs = System.currentTimeMillis()
        )
    )
    val state: StateFlow<UiState> = _state

    init {
        // Tick per aggiornare SOLO la UI (non salva nulla ogni secondo).
        viewModelScope.launch {
            engine.uiTickerFlow().collect { now ->
                _state.update { it.copy(nowMs = now) }
            }
        }
    }
    fun onAppForeground(nowMs: Long = System.currentTimeMillis()) {
        if (appForegroundStartMs != null) return
        appForegroundStartMs = nowMs
        _state.update { it.copy(appUsageRunningSinceMs = nowMs) }
    }
    fun onAppBackground(nowMs: Long = System.currentTimeMillis()) {
        val start = appForegroundStartMs ?: _state.value.appUsageRunningSinceMs ?: return
        appForegroundStartMs = null
        val delta = (nowMs - start).coerceAtLeast(0L)
        _state.update { it.copy(
            appUsageMs = it.appUsageMs + delta,
            appUsageRunningSinceMs = null
        ) }
        if (delta <= 0L) return
        persist()
        scheduleAutoBackup()
    }

    /**
     * Must be called once at app start (MainActivity does this).
     * Restores persisted snapshot and AUTO-RESUMES any running tasks after process death.
     */
    fun initialize(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext

        val snap = SnapshotStore.load(context)
        if (snap != null) {
            engine.importRuntimeSnapshot(
                tasks = snap.tasks,
                tags = snap.tags,
                taskSessionsSnapshot = snap.taskSessions,
                tagSessionsSnapshot = snap.tagSessions,
                snapshot = TimeEngine.RuntimeSnapshot(
                    activeTaskStart = snap.activeTaskStart,
                    activeTagStart = snap.activeTagStart.map { Triple(it.taskId, it.tagId, it.startTs) }
                )
            )
            _state.update {
                it.copy(
                    tasks = snap.tasks,
                    tags = snap.tags,
                    taskSessions = snap.taskSessions,
                    tagSessions = snap.tagSessions,
                    appUsageMs = snap.appUsageMs,
                    appUsageRunningSinceMs = null,
                    installAtMs = snap.installAtMs,
                    nowMs = System.currentTimeMillis()
                )
            }
            // Ensure autoexport is active after restore.
            scheduleAutoBackup()
            return
        }

        // Fresh install (or no snapshot yet): start clean.
        _state.update {
            it.copy(
                tasks = emptyList(),
                tags = emptyList(),
                taskSessions = emptyList(),
                tagSessions = emptyList(),
                appUsageMs = 0L,
                nowMs = System.currentTimeMillis()
            )
        }
        persist()
        scheduleAutoBackup()
    }

/**
 * Reloads the persisted snapshot from disk and applies it to the in-memory state.
 * Used when an external component (e.g. the widget) mutates the snapshot while the app is open.
 */
fun reloadFromSnapshot(context: Context) {
    bindContext(context)
    val snap = SnapshotStore.load(context) ?: return

    engine.importRuntimeSnapshot(
        tasks = snap.tasks,
        tags = snap.tags,
        taskSessionsSnapshot = snap.taskSessions,
        tagSessionsSnapshot = snap.tagSessions,
        snapshot = TimeEngine.RuntimeSnapshot(
            activeTaskStart = snap.activeTaskStart,
            activeTagStart = snap.activeTagStart.map { Triple(it.taskId, it.tagId, it.startTs) }
        )
    )

    _state.update {
        it.copy(
            tasks = snap.tasks,
            tags = snap.tags,
            taskSessions = snap.taskSessions,
            tagSessions = snap.tagSessions,
            appUsageMs = snap.appUsageMs,
                    appUsageRunningSinceMs = null,
                installAtMs = minOf(it.installAtMs, snap.installAtMs),
                            nowMs = System.currentTimeMillis()
        )
    }

    // Keep auto-backup scheduled after external changes.
    scheduleAutoBackup()
}

    data class BackupProbeResult(
        val hasValidFullSet: Boolean,
        val presentFiles: List<String>,
        val problems: List<String>
    )

    private val expectedCsv = listOf(
        "sessions.csv" to "task_id,task_name,start_ts,end_ts",
        "totals.csv" to "task_id,task_name,total_ms",
        "tag_sessions.csv" to "tag_id,tag_name,task_id,task_name,start_ts,end_ts",
        "tag_totals.csv" to "tag_id,tag_name,total_ms",
        "app_usage.csv" to "total_ms"
    )

    /**
     * Probe leggera: esistono i file? Sono leggibili? Header compatibile?
     */
    fun probeBackupFolder(context: Context): BackupProbeResult {
        bindContext(context)
        return runCatching {
            val dir = BackupFolderStore.getOrCreateDataDir(context)
            val present = mutableListOf<String>()
            val problems = mutableListOf<String>()
            var okCount = 0

            for ((name, expectedHeader) in expectedCsv) {
                val f = dir.findFile(name)
                if (f == null) {
                    problems.add("Manca $name")
                    continue
                }
                present.add(name)
                if (!f.canRead()) {
                    problems.add("Non leggibile: $name")
                    continue
                }
                val header = readFirstLine(context, f.uri)
                if (header == null) {
                    problems.add("Vuoto o non leggibile: $name")
                    continue
                }
                if (header.trim() != expectedHeader) {
                    problems.add("Header non valido: $name")
                    continue
                }
                okCount++
            }

            BackupProbeResult(
                hasValidFullSet = okCount == expectedCsv.size,
                presentFiles = present.toList(),
                problems = problems.toList()
            )
        }.getOrElse {
            BackupProbeResult(
                hasValidFullSet = false,
                presentFiles = emptyList(),
                problems = listOf(it.message ?: it.javaClass.simpleName)
            )
        }
    }

    private fun readFirstLine(context: Context, uri: Uri): String? {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                input.bufferedReader(Charsets.UTF_8).useLines { seq ->
                    seq.firstOrNull { it.isNotBlank() }
                }
            }
        }.getOrNull()
    }

    /**
     * Cancella SOLO i 4 file CSV attesi (match nome esatto).
     */
    fun deleteBackupCsv(context: Context) {
        bindContext(context)
        runCatching {
            val dir = BackupFolderStore.getOrCreateDataDir(context)
            (expectedCsv.map { it.first } + "dict.json").forEach { name ->
                dir.findFile(name)?.let { f ->
                    if (f.name == name) {
                        f.delete()
                    }
                }
            }
        }.onFailure { e ->
            Toast.makeText(context, "Cleanup fallito: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun persist() {
        val ctx = appContext ?: return
        val cur = _state.value
        val runtime = engine.exportRuntimeSnapshot()
        SnapshotStore.save(
            context = ctx,
            tasks = cur.tasks,
            tags = cur.tags,
            taskSessions = cur.taskSessions,
            tagSessions = cur.tagSessions,
            installAtMs = cur.installAtMs,
            appUsageMs = cur.appUsageMs,
            activeTaskStart = runtime.activeTaskStart,
            activeTagStart = runtime.activeTagStart.map { (taskId, tagId, startTs) ->
                SnapshotStore.ActiveTag(taskId = taskId, tagId = tagId, startTs = startTs)
            }
        )
    }

    private fun computeBackupSignature(): String {
        // Cheap signature to skip redundant exports.
        val ts = engine.getTaskSessions()
        val tgs = engine.getTagSessions()
        val lastTask = ts.maxOfOrNull { it.endTs } ?: 0L
        val lastTag = tgs.maxOfOrNull { it.endTs } ?: 0L

        // Include structure changes too (tasks/tags + associations), so dict.json stays in sync
        // even when the user edits tasks/tags without creating new sessions.
        val cur = _state.value
        val tasksSig = cur.tasks
            .sortedBy { it.id }
            .joinToString("|") { t -> "${t.id}:${t.name}:${t.tagIds.sorted().joinToString(",")}" }
        val tagsSig = cur.tags
            .sortedBy { it.id }
            .joinToString("|") { t -> "${t.id}:${t.name}" }

        return "${ts.size}|${tgs.size}|$lastTask|$lastTag|$tasksSig|$tagsSig|${_state.value.appUsageMs}"
    }

    private fun scheduleAutoBackup() {
        val ctx = appContext ?: return
        // Auto-backup only if the user has configured a tree URI.
        if (BackupFolderStore.getTreeUri(ctx) == null) return

        autoBackupJob?.cancel()
        autoBackupJob = viewModelScope.launch {
            // Small debounce to coalesce rapid start/pause taps.
            delay(1200)
            runCatching {
                val sig = computeBackupSignature()
                if (sig == lastBackupSignature) return@runCatching

                val dir = BackupFolderStore.getOrCreateDataDir(ctx)
                CsvExporter.exportAllToDirectory(
                    context = ctx,
                    dir = dir,
                    tasks = _state.value.tasks,
                    tags = _state.value.tags,
                    taskSessions = engine.getTaskSessions(),
                    tagSessions = engine.getTagSessions(),
                    appUsageMs = _state.value.appUsageMs
                )
                lastBackupSignature = sig
            }
        }
    }

    /**
     * Called from UI after the user picks a folder (OpenDocumentTree).
     * Persists the permission and creates/uses the "MultiTimer data" subfolder.
     */
    fun setBackupRootFolder(context: Context, treeUri: Uri) {
        // Persist permission for future sessions.
        val flags = (android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
            or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        BackupFolderStore.saveTreeUri(context, treeUri)

        runCatching {
            BackupFolderStore.getOrCreateDataDir(context)
        }.onFailure { e: Throwable ->
            Toast.makeText(context, "Errore cartella backup: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun exportBackup(context: Context) {
        val taskSessions = engine.getTaskSessions()
        val tagSessions = engine.getTagSessions()

        if (taskSessions.isEmpty() && tagSessions.isEmpty()) {
            Toast.makeText(
                context,
                "Nessuna sessione trovata: esporto comunque task/tag (anagrafica) per non perdere nulla",
                Toast.LENGTH_LONG
            ).show()
        }

        try {
            val dir = BackupFolderStore.getOrCreateDataDir(context)
            CsvExporter.exportAllToDirectory(
                context = context,
                dir = dir,
                tasks = _state.value.tasks,
                tags = _state.value.tags,
                taskSessions = taskSessions,
                tagSessions = tagSessions,
                appUsageMs = _state.value.appUsageMs
            )
            lastBackupSignature = computeBackupSignature()
            Toast.makeText(context, "Export completato in '${dir.name ?: "MultiTimer data"}'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Export fallito: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun importBackup(context: Context) {
        viewModelScope.launch {
            try {
                importBackupBlocking(context)
                Toast.makeText(context, "Import completato", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import fallito: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * Same as importBackup(), but runs in the caller coroutine (so UI can await it).
     */
    suspend fun importBackupBlocking(context: Context) {
        bindContext(context)
        Log.i(LOG_TAG, "importBackupBlocking: START (current tasks=${_state.value.tasks.size}, tags=${_state.value.tags.size})")
        val dir = BackupFolderStore.getOrCreateDataDir(context)
        Log.i(LOG_TAG, "importBackupBlocking: reading from dir='${dir.name ?: "(null)"}' uri=${dir.uri}")
        val snapshot = CsvImporter.importFromBackupFolder(context, dir)

        Log.i(
            LOG_TAG,
            "importBackupBlocking: parsed snapshot tasks=${snapshot.tasks.size} tags=${snapshot.tags.size} " +
                "taskSessions=${snapshot.taskSessions.size} tagSessions=${snapshot.tagSessions.size} runtime=${snapshot.runtimeSnapshot != null}"
        )

        engine.loadImportedSnapshot(
            tasks = snapshot.tasks,
            tags = snapshot.tags,
            importedTaskSessions = snapshot.taskSessions,
            importedTagSessions = snapshot.tagSessions,
            runtimeSnapshot = snapshot.runtimeSnapshot
        )

        Log.i(LOG_TAG, "importBackupBlocking: engine loaded snapshot")

        _state.update {
            it.copy(
                tasks = snapshot.tasks,
                tags = snapshot.tags,
                taskSessions = snapshot.taskSessions,
                tagSessions = snapshot.tagSessions,
                appUsageMs = snapshot.appUsageMs,
                // Imported backups don't carry install time; keep current persisted value.
                installAtMs = it.installAtMs,
                appUsageRunningSinceMs = null,
                nowMs = System.currentTimeMillis()
            )
        }
        Log.i(LOG_TAG, "importBackupBlocking: state updated")
        persist()
        Log.i(LOG_TAG, "importBackupBlocking: persisted")
        lastBackupSignature = computeBackupSignature()
        scheduleAutoBackup()
        Log.i(LOG_TAG, "importBackupBlocking: END")
    }

    fun toggleTask(taskId: Long) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val result = engine.toggleTask(
                tasks = current.tasks,
                tags = current.tags,
                taskId = taskId,
                nowMs = now
            )
            val updated = current.copy(
                tasks = result.tasks,
                tags = result.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = now
            )
            updated
        }
        persist()
        scheduleAutoBackup()
    }

    fun addTask(name: String, tagIds: Set<Long>, link: String) {
        if (name.isBlank()) return
        _state.update { current ->
            val now = System.currentTimeMillis()
            val task = engine.createTask(name.trim(), tagIds, link.trim())
            val withTask = current.tasks + task

            // Requirement: a task starts automatically immediately after being created.
            // We reuse the same toggle logic used by the UI so tag timers are also started.
            val result = engine.toggleTask(
                tasks = withTask,
                tags = current.tags,
                taskId = task.id,
                nowMs = now
            )

            current.copy(
                tasks = result.tasks,
                tags = result.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = now
            )
        }
        persist()
        scheduleAutoBackup()
    }

    fun addTag(name: String) {
        val n = name.trim()
        if (n.isBlank()) return

        val already = _state.value.tags.any { it.name.equals(n, ignoreCase = true) }
        if (already) {
            appContext?.let { ctx -> Toast.makeText(ctx, "tag già esiste", Toast.LENGTH_SHORT).show() }
            return
        }

        _state.update { current ->
            val tag = engine.createTag(n)
            current.copy(tags = current.tags + tag)
        }
        persist()
        scheduleAutoBackup()
    }

    fun renameTag(tagId: Long, newName: String) {
        if (newName.isBlank()) return
        _state.update { current ->
            current.copy(tags = engine.renameTag(current.tags, tagId, newName))
        }
        persist()
        scheduleAutoBackup()
    }

    fun updateTask(taskId: Long, newName: String, newTagIds: Set<Long>, link: String) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val result = engine.reassignTaskTags(
                tasks = current.tasks,
                tags = current.tags,
                taskId = taskId,
                newTagIds = newTagIds,
                newName = newName,
                newLink = link.trim(),
                nowMs = now
            )
            val updated = current.copy(
                tasks = result.tasks,
                tags = result.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = now
            )
            updated
        }
        persist()
        scheduleAutoBackup()
    }

    fun deleteTask(taskId: Long) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val res = engine.deleteTask(
                tasks = current.tasks,
                tags = current.tags,
                taskId = taskId,
                nowMs = now
            )
            val updated = current.copy(
                tasks = res.tasks,
                tags = res.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = now
            )
            updated
        }
        persist()
        scheduleAutoBackup()
    }

    // Backward compatible call sites
    fun updateTaskTags(taskId: Long, newTagIds: Set<Long>, link: String) {
        val curName = _state.value.tasks.firstOrNull { it.id == taskId }?.name ?: ""
        updateTask(taskId = taskId, newName = curName, newTagIds = newTagIds, link = link)
    }

    fun deleteTag(tagId: Long, deleteAssociatedTasks: Boolean = false) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val res = engine.deleteTag(
                tasks = current.tasks,
                tags = current.tags,
                tagId = tagId,
                deleteAssociatedTasks = deleteAssociatedTasks,
                nowMs = now
            )
            val updated = current.copy(
                tasks = res.tasks,
                tags = res.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = now
            )
            updated
        }
        persist()
        scheduleAutoBackup()
    }

    fun restoreTask(taskId: Long) {
        _state.update { current ->
            current.copy(tasks = engine.restoreTask(current.tasks, taskId), nowMs = System.currentTimeMillis())
        }
        persist()
        scheduleAutoBackup()
    }

    fun restoreTag(tagId: Long) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val res = engine.restoreTag(
                tasks = current.tasks,
                tags = current.tags,
                tagId = tagId,
                nowMs = now
            )
            current.copy(
                tasks = res.tasks,
                tags = res.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = now
            )
        }
        persist()
        scheduleAutoBackup()
    }

    fun purgeTask(taskId: Long) {
        _state.update { current ->
            val res = engine.purgeTask(current.tasks, current.tags, taskId)
            current.copy(
                tasks = res.tasks,
                tags = res.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = System.currentTimeMillis()
            )
        }
        persist()
        scheduleAutoBackup()
    }

    fun purgeTag(tagId: Long) {
        _state.update { current ->
            val res = engine.purgeTag(current.tasks, current.tags, tagId)
            current.copy(
                tasks = res.tasks,
                tags = res.tags,
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions(),
                nowMs = System.currentTimeMillis()
            )
        }
        persist()
        scheduleAutoBackup()
    }
fun exportCsv(context: Context) {
        val taskSessions = engine.getTaskSessions()
        val tagSessions = engine.getTagSessions()

        if (taskSessions.isEmpty() && tagSessions.isEmpty()) {
            Toast.makeText(
                context,
                "Nessuna sessione da esportare (avvia e ferma almeno un task)",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val activeTasks = state.value.tasks.filter { !it.isDeleted }
        val activeTags = state.value.tags.filter { !it.isDeleted }

        val files = listOf(
            CsvExporter.exportTaskSessions(context, taskSessions),
            CsvExporter.exportTaskTotals(context, taskSessions),
            CsvExporter.exportTagSessions(context, tagSessions),
            CsvExporter.exportTagTotals(context, activeTags, activeTasks, state.value.taskSessions, state.value.nowMs)
        )

        ShareUtils.shareFiles(context, files, title = "MultiTimeTracker export")
    }

    fun importCsv(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            try {
                Log.i(LOG_TAG, "importCsv: START uris=${uris.size} (current tasks=${_state.value.tasks.size}, tags=${_state.value.tags.size})")
                val snapshot = CsvImporter.importFromUris(context, uris)
                Log.i(
                    LOG_TAG,
                    "importCsv: parsed snapshot tasks=${snapshot.tasks.size} tags=${snapshot.tags.size} " +
                        "taskSessions=${snapshot.taskSessions.size} tagSessions=${snapshot.tagSessions.size} runtime=${snapshot.runtimeSnapshot != null}"
                )

                // load snapshot into engine so that future exports work
                engine.loadImportedSnapshot(
                    tasks = snapshot.tasks,
                    tags = snapshot.tags,
                    importedTaskSessions = snapshot.taskSessions,
                    importedTagSessions = snapshot.tagSessions,
                    runtimeSnapshot = snapshot.runtimeSnapshot
                )

                Log.i(LOG_TAG, "importCsv: engine loaded snapshot")
                
                _state.update {
                    it.copy(
                        tasks = snapshot.tasks,
                        tags = snapshot.tags,
                        taskSessions = snapshot.taskSessions,
                        tagSessions = snapshot.tagSessions,
                        appUsageMs = snapshot.appUsageMs,
                        // Imported CSV doesn't carry install time; keep current persisted value.
                        installAtMs = it.installAtMs,
                        appUsageRunningSinceMs = null,
                        nowMs = System.currentTimeMillis()
                    )
                }

                Log.i(LOG_TAG, "importCsv: state updated")

                // Persist immediately so that reopening the app keeps the imported state.
                persist()
                Log.i(LOG_TAG, "importCsv: persisted")
                scheduleAutoBackup()
                Log.i(LOG_TAG, "importCsv: END")

                Toast.makeText(
                    context,
                    "Import completato: ${snapshot.tasks.size} task, ${snapshot.tags.size} tag",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "importCsv: FAILED ${e.message ?: e::class.java.simpleName}", e)
                Toast.makeText(
                    context,
                    "Import fallito: ${e.message ?: e::class.java.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

}