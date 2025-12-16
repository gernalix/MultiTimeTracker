// v1
package com.example.multitimetracker.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.example.multitimetracker.MainActivity
import com.example.multitimetracker.R

/**
 * Foreground service used ONLY while at least one task is running.
 *
 * Goal: keep the process warm / reduce kill probability, while timing correctness
 * is still handled by NOW - START_TIME logic.
 */
class TrackingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()

        when (intent?.action) {
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE, ACTION_START -> {
                val runningCount = intent.getIntExtra(EXTRA_RUNNING_COUNT, 1).coerceAtLeast(1)
                val notification = buildNotification(runningCount)

                // If the service is already in foreground, this updates the notification.
                // If not, this promotes it to foreground.
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
            else -> {
                // Defensive: if somehow started without an action, just show a minimal notification.
                val notification = buildNotification(runningCount = 1)
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            }
        }

        // We don't want the OS to restart this service automatically.
        // If the OS kills it, timing remains correct; and the UI will restart it when needed.
        return START_NOT_STICKY
    }

    private fun buildNotification(runningCount: Int): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val openAppPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrackingForegroundService::class.java)
            .setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = "MultiTimeTracker"
        val text = if (runningCount == 1) {
            "Tracking attivo: 1 task"
        } else {
            "Tracking attivo: $runningCount task"
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openAppPending)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                0,
                "Stop tracking",
                stopPending
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val ch = NotificationChannel(
            CHANNEL_ID,
            "Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifica mostrata solo mentre un timer è in esecuzione"
        }
        nm.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_START = "com.example.multitimetracker.action.TRACKING_START"
        private const val ACTION_UPDATE = "com.example.multitimetracker.action.TRACKING_UPDATE"
        private const val ACTION_STOP = "com.example.multitimetracker.action.TRACKING_STOP"

        private const val EXTRA_RUNNING_COUNT = "extra_running_count"

        /**
         * Idempotent helper used by UI.
         * - If runningCount > 0: start/update the foreground service.
         * - If runningCount == 0: stop it.
         */
        fun setTrackingState(context: Context, runningCount: Int) {
            if (runningCount <= 0) {
                val stop = Intent(context, TrackingForegroundService::class.java)
                    .setAction(ACTION_STOP)
                context.startService(stop)
                return
            }

            val action = ACTION_UPDATE
            val i = Intent(context, TrackingForegroundService::class.java)
                .setAction(action)
                .putExtra(EXTRA_RUNNING_COUNT, runningCount)

            // Required for Android O+; safe on older devices.
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }
}
