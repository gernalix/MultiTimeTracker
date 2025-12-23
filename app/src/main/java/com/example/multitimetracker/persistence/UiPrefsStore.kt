// v2
package com.example.multitimetracker.persistence

import android.content.Context

/**
 * Tiny UI preferences stored in SharedPreferences.
 * Keep this intentionally small: only view toggles that affect rendering.
 */
object UiPrefsStore {
    private const val PREFS = "ui_prefs"

    private const val KEY_HIDE_INACTIVE_TIME = "hide_inactive_task_time"
    private const val KEY_HIDE_INACTIVE_TAGS = "hide_inactive_task_tags"
    private const val KEY_SHOW_SECONDS = "show_seconds"
    private const val KEY_HIDE_HOURS_IF_ZERO = "hide_hours_if_zero"

    fun getHideInactiveTime(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_INACTIVE_TIME, false)

    fun setHideInactiveTime(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_INACTIVE_TIME, value)
            .apply()
    }

    fun getHideInactiveTags(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_INACTIVE_TAGS, false)

    fun setHideInactiveTags(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_INACTIVE_TAGS, value)
            .apply()
    }

    fun getShowSeconds(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOW_SECONDS, true)

    fun setShowSeconds(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SHOW_SECONDS, value)
            .apply()
    }

    fun getHideHoursIfZero(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_HIDE_HOURS_IF_ZERO, true)

    fun setHideHoursIfZero(context: Context, value: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_HIDE_HOURS_IF_ZERO, value)
            .apply()
    }
}