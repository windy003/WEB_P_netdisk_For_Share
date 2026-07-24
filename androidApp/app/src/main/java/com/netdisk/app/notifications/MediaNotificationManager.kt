package com.netdisk.app.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import com.netdisk.app.MainActivity
import com.netdisk.app.R
import com.netdisk.app.services.AudioPlaybackService

class MediaNotificationManager(
    private val context: Context,
    private val mediaSession: MediaSessionCompat
) {

    private val notificationManager: NotificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_description)
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun buildNotification(trackTitle: String, isPlaying: Boolean): Notification {
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(mediaSession.sessionToken)
            .setShowActionsInCompactView(0, 1, 2) // prev, play/pause, next

        // Intent to open MainActivity when notification is clicked
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play) // TODO: Replace with custom icon
            .setContentTitle(trackTitle)
            .setContentText("Netdisk Player")
            .setStyle(mediaStyle)
            .setContentIntent(contentPendingIntent)
            .addAction(createAction(
                android.R.drawable.ic_media_previous,
                context.getString(R.string.previous),
                AudioPlaybackService.ACTION_PREVIOUS
            ))
            .addAction(createAction(
                if (isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (isPlaying) context.getString(R.string.pause)
                else context.getString(R.string.play),
                if (isPlaying) AudioPlaybackService.ACTION_PAUSE
                else AudioPlaybackService.ACTION_RESUME
            ))
            .addAction(createAction(
                android.R.drawable.ic_media_next,
                context.getString(R.string.next),
                AudioPlaybackService.ACTION_NEXT
            ))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(isPlaying)
            .setShowWhen(false)
            .build()
    }

    private fun createAction(icon: Int, title: String, action: String): NotificationCompat.Action {
        val intent = Intent(context, AudioPlaybackService::class.java).apply {
            this.action = action
        }
        val pendingIntent = PendingIntent.getService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Action.Builder(icon, title, pendingIntent).build()
    }

    companion object {
        private const val CHANNEL_ID = "media_playback_channel"
    }
}
