// v1
package com.example.multitimetracker

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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service used ONLY while at least one task timer is running.
 *
 * Goal: keep the process warm and reduce the 2-3s "cold start" friction,
 * while timing correctness remains based on NOW-START_TIME.
 */
class TrackingForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_TRACKING -> {
                // App-level "stop tracking" doesn't exist yet. We just stop the service.
                // Timers remain correct because they are based on persisted start timestamps.
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        // Must call startForeground quickly after startForegroundService().
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_STICKY
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

        val openPending = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, TrackingForegroundService::class.java).apply {
            action = ACTION_STOP_TRACKING
        }

        val stopPending = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Tracking attivo")
            .setContentText("MultiTimeTracker resta attivo mentre tracci")
            .setOngoing(true)
            .setContentIntent(openPending)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Stop service",
                stopPending
            )
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = nm.getNotificationChannel(CHANNEL_ID)
        if (existing != null) return

        val channel = NotificationChannel(
            CHANNEL_ID,
            "Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifica persistente durante il tracking"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "tracking"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP_TRACKING = "com.example.multitimetracker.action.STOP_TRACKING"

        fun startIfNeeded(context: Context) {
            val intent = Intent(context, TrackingForegroundService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopIfRunning(context: Context) {
            // Cancel the notification (if any) then stop the service.
            runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID) }
            val intent = Intent(context, TrackingForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
