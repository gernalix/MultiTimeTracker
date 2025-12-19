// v8
package com.example.multitimetracker

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile.provider.DocumentFile
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

    private val engine = TimeEngine()

    private var appContext: Context? = null
    private var initialized = false

    // Debounced auto-backup to SAF folder (MultiTimer data).
    private var autoBackupJob: Job? = null
    private var lastBackupSignature: String? = null

    private val _state = MutableStateFlow(
        UiState(
            tasks = emptyList(),
            tags = emptyList(),
            taskSessions = emptyList(),
            tagSessions = emptyList(),
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
                    nowMs = System.currentTimeMillis()
                )
            }
            return
        }

        // Fallback: demo data (puoi cancellarlo quando vuoi)
        val logseq = engine.createTag("Logseq")
        val coding = engine.createTag("coding")
        val siti = engine.createTag("siti")
        val faccende = engine.createTag("faccende")

        val t1 = engine.createTask("Logseq FAQ monitor", setOf(logseq.id, coding.id))
        val t2 = engine.createTask("lavatrice", setOf(faccende.id))
        val t3 = engine.createTask("site monitor", setOf(coding.id, siti.id))

        _state.update {
            it.copy(
                tasks = listOf(t1, t2, t3),
                tags = listOf(logseq, coding, siti, faccende),
                taskSessions = engine.getTaskSessions(),
                tagSessions = engine.getTagSessions()
            )
        }
        persist()
        scheduleAutoBackup()
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
        return "${ts.size}|${tgs.size}|$lastTask|$lastTag"
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
                    taskSessions = engine.getTaskSessions(),
                    tagSessions = engine.getTagSessions()
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
        }.onFailure {
            Toast.makeText(context, "Errore cartella backup: ${it.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun exportBackup(context: Context) {
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

        try {
            val dir = BackupFolderStore.getOrCreateDataDir(context)
            CsvExporter.exportAllToDirectory(context, dir, taskSessions, tagSessions)
            lastBackupSignature = computeBackupSignature()
            Toast.makeText(context, "Export completato in '${dir.name ?: "MultiTimer data"}'", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Export fallito: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun importBackup(context: Context) {
        viewModelScope.launch {
            try {
                val dir = BackupFolderStore.getOrCreateDataDir(context)
                val snapshot = CsvImporter.importFromBackupFolder(context, dir)

                engine.loadImportedSnapshot(
                    tasks = snapshot.tasks,
                    tags = snapshot.tags,
                    importedTaskSessions = snapshot.taskSessions,
                    importedTagSessions = snapshot.tagSessions
                )

                _state.update {
                    it.copy(
                        tasks = snapshot.tasks,
                        tags = snapshot.tags,
                        taskSessions = snapshot.taskSessions,
                        tagSessions = snapshot.tagSessions,
                        nowMs = System.currentTimeMillis()
                    )
                }
                persist()
                lastBackupSignature = computeBackupSignature()

                Toast.makeText(
                    context,
                    "Import completato: ${snapshot.tasks.size} task, ${snapshot.tags.size} tag",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Import fallito: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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

    fun addTask(name: String, tagIds: Set<Long>) {
        if (name.isBlank()) return
        _state.update { current ->
            val task = engine.createTask(name.trim(), tagIds)
            current.copy(tasks = current.tasks + task)
        }
        persist()
        scheduleAutoBackup()
    }

    fun addTag(name: String) {
        if (name.isBlank()) return
        _state.update { current ->
            val tag = engine.createTag(name.trim())
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


    fun updateTaskTags(taskId: Long, newTagIds: Set<Long>) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val result = engine.reassignTaskTags(
                tasks = current.tasks,
                tags = current.tags,
                taskId = taskId,
                newTagIds = newTagIds,
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

    fun deleteTag(tagId: Long) {
        _state.update { current ->
            val now = System.currentTimeMillis()
            val res = engine.deleteTag(
                tasks = current.tasks,
                tags = current.tags,
                tagId = tagId,
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

        val files = listOf(
            CsvExporter.exportTaskSessions(context, taskSessions),
            CsvExporter.exportTaskTotals(context, taskSessions),
            CsvExporter.exportTagSessions(context, tagSessions),
            CsvExporter.exportTagTotals(context, tagSessions)
        )

        ShareUtils.shareFiles(context, files, title = "MultiTimeTracker export")
    }

    fun importCsv(context: Context, uris: List<Uri>) {
        viewModelScope.launch {
            try {
                val snapshot = CsvImporter.importFromUris(context, uris)

                // load snapshot into engine so that future exports work
                engine.loadImportedSnapshot(
                    tasks = snapshot.tasks,
                    tags = snapshot.tags,
                    importedTaskSessions = snapshot.taskSessions,
                    importedTagSessions = snapshot.tagSessions
                )

                _state.update {
                    it.copy(
                        tasks = snapshot.tasks,
                        tags = snapshot.tags,
                        taskSessions = snapshot.taskSessions,
                        tagSessions = snapshot.tagSessions,
                        nowMs = System.currentTimeMillis()
                    )
                }

                // Persist immediately so that reopening the app keeps the imported state.
                persist()
                scheduleAutoBackup()

                Toast.makeText(
                    context,
                    "Import completato: ${snapshot.tasks.size} task, ${snapshot.tags.size} tag",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    "Import fallito: ${e.message ?: e::class.java.simpleName}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

}