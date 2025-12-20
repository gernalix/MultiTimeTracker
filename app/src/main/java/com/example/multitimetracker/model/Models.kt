// v5
package com.example.multitimetracker.model
import com.example.multitimetracker.export.TaskSession
import com.example.multitimetracker.export.TagSession

data class Task(
    val id: Long,
    val name: String,
    val link: String = "",
    val tagIds: Set<Long>,
    val isDeleted: Boolean = false,
    val deletedAtMs: Long? = null,
    val isRunning: Boolean,
    val totalMs: Long,
    val lastStartedAtMs: Long? // null se in pausa
)

data class Tag(
    val id: Long,
    val name: String,
    val isDeleted: Boolean = false,
    val deletedAtMs: Long? = null,
    val activeChildrenCount: Int,
    val totalMs: Long,
    val lastStartedAtMs: Long? // null se non sta correndo
)

data class UiState(
    val tasks: List<Task>,
    val tags: List<Tag>,
    val taskSessions: List<TaskSession>,
    val tagSessions: List<TagSession>,
    val activeTagStart: Map<Long, Long> = emptyMap(),
    val nowMs: Long
)
