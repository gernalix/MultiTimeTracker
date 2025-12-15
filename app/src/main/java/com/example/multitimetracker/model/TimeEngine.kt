// v2
package com.example.multitimetracker.model

import com.example.multitimetracker.export.TaskSession

/**
 * TimeEngine = il "motore" che gestisce start/stop e accumulo dei tempi.
 * In questo patch aggiunge anche un log di sessioni (start/end) per ogni task.
 */
class TimeEngine {

    private val activeTaskStart = mutableMapOf<Long, Long>() // taskId -> startTs
    private val taskSessions = mutableListOf<TaskSession>()

    fun onTaskStarted(task: Task, nowTs: Long = System.currentTimeMillis()) {
        // evita doppio start
        if (activeTaskStart.containsKey(task.id)) return
        activeTaskStart[task.id] = nowTs
    }

    fun onTaskStopped(task: Task, nowTs: Long = System.currentTimeMillis()) {
        val start = activeTaskStart.remove(task.id) ?: return
        if (nowTs <= start) return
        taskSessions.add(
            TaskSession(
                taskId = task.id,
                taskName = task.name,
                startTs = start,
                endTs = nowTs
            )
        )
    }

    fun getTaskSessions(): List<TaskSession> = taskSessions.toList()

    fun clearSessions() {
        taskSessions.clear()
        activeTaskStart.clear()
    }
}
