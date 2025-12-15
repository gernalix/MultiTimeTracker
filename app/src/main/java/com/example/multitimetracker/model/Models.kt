// v1
package com.example.multitimetracker.model

data class Task(
    val id: Long,
    val name: String,
    val tagIds: Set<Long>,
    val isRunning: Boolean,
    val totalMs: Long,
    val lastStartedAtMs: Long? // null se in pausa
)

data class Tag(
    val id: Long,
    val name: String,
    val activeChildrenCount: Int,
    val totalMs: Long,
    val lastStartedAtMs: Long? // null se non sta correndo
)

data class UiState(
    val tasks: List<Task>,
    val tags: List<Tag>,
    val nowMs: Long
)
