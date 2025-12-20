// v1
package com.example.multitimetracker.widget

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.widget.Toast

/**
 * A tiny transparent Activity used as a reliable place to trigger haptics and toast.
 * Some launchers/ROMs suppress Toast/Vibration when triggered from an AppWidgetProvider.
 */
class QuickTaskWidgetClickActivity : Activity() {

    companion object {
        private const val ACTION_QUICK_TASK_CLICK = "com.example.multitimetracker.widget.ACTION_QUICK_TASK_CLICK"
        private const val EXTRA_FROM_ACTIVITY = "com.example.multitimetracker.widget.EXTRA_FROM_ACTIVITY"

        private fun vibrateStrong(context: Context) {
            runCatching {
                val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                    vm.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                }

                if (vibrator?.hasVibrator() == true) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(60, 255))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(60)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val appCtx = applicationContext

        // Feedback first.
        vibrateStrong(appCtx)
        Toast.makeText(appCtx, "Quick task avviato", Toast.LENGTH_SHORT).show()

        // Then delegate the actual work to the existing widget provider logic.
        val intent = Intent(appCtx, QuickTaskWidgetProvider::class.java).apply {
            action = ACTION_QUICK_TASK_CLICK
            putExtra(EXTRA_FROM_ACTIVITY, true)
        }
        appCtx.sendBroadcast(intent)

        // Close immediately (no UI).
        finish()
        overridePendingTransition(0, 0)
    }
}
