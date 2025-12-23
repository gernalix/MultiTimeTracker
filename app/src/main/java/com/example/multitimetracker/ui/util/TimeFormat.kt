// v1
package com.example.multitimetracker.ui.util

/**
 * Formats a duration in ms.
 *
 * - If showSeconds=false: returns H:MM or M (when hideHoursIfZero && hours==0)
 * - If showSeconds=true:  returns H:MM:SS or M:SS (when hideHoursIfZero && hours==0)
 *
 * Note: Minutes are not zero-padded when hours are hidden (7:05 not 07:05).
 */
fun formatDuration(
    ms: Long,
    showSeconds: Boolean = true,
    hideHoursIfZero: Boolean = false
): String {
    val totalSec = (ms.coerceAtLeast(0L)) / 1000L
    val sec = totalSec % 60L
    val totalMin = totalSec / 60L
    val min = totalMin % 60L
    val hours = totalMin / 60L

    return if (showSeconds) {
        if (hideHoursIfZero && hours == 0L) {
            "${min}:%02d".format(sec)
        } else {
            "%02d:%02d:%02d".format(hours, min, sec)
        }
    } else {
        if (hideHoursIfZero && hours == 0L) {
            "${min}"
        } else {
            "%02d:%02d".format(hours, min)
        }
    }
}
