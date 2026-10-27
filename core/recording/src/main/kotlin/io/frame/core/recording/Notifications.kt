package io.frame.core.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import io.frame.core.model.Mode

object Notifications {
    private const val CHANNEL_RECORDING = "recording"
    private const val CHANNEL_ALERTS = "alerts"
    const val ID_RECORDING = 1
    private const val ID_ALERT = 2

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RECORDING, "Recording", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while FRAME is recording"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Recording alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Storage full, overheating, recording stopped"
            },
        )
    }

    fun recording(context: Context, mode: Mode, state: RecorderState.Recording?): Notification {
        val title = when (mode) {
            Mode.DASHCAM -> "Dashcam recording"
            Mode.SPORTS -> "Recording match"
            Mode.CAMERA -> "Recording video"
        }
        val text = state?.let {
            buildString {
                append("Segment ${it.segmentSeq}")
                if (it.eventCount > 0) append(" · ${it.eventCount} ${if (mode == Mode.SPORTS) "highlights" else "protected"}")
            }
        } ?: "Starting…"
        val markLabel = if (mode == Mode.SPORTS) "Highlight" else "Save clip"

        return NotificationCompat.Builder(context, CHANNEL_RECORDING)
            .setSmallIcon(R.drawable.ic_stat_record)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openApp(context))
            .apply {
                if (state != null) setUsesChronometer(true).setWhen(state.sessionStartedAt).setShowWhen(true)
            }
            .addAction(0, "Stop", serviceAction(context, RecordingService.ACTION_STOP, 1))
            .addAction(0, markLabel, serviceAction(context, RecordingService.ACTION_MARK, 2))
            .build()
    }

    fun alert(context: Context, title: String, text: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return
        manager.notify(
            ID_ALERT,
            NotificationCompat.Builder(context, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_stat_record)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(openApp(context))
                .build(),
        )
    }

    private fun openApp(context: Context): PendingIntent? {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun serviceAction(context: Context, action: String, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            Intent(context, RecordingService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
