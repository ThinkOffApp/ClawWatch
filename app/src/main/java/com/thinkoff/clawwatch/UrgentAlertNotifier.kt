package com.thinkoff.clawwatch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object UrgentAlertNotifier {

    private const val NOTIFICATION_GROUP = "clawwatch_urgent"

    fun show(
        context: Context,
        eventId: String,
        title: String,
        body: String,
        room: String?,
        prompt: String?
    ) {
        createChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            action = AlertContract.ACTION_ALERT_OPEN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(AlertContract.EXTRA_ALERT_EVENT_ID, eventId)
            putExtra(AlertContract.EXTRA_ALERT_TITLE, title)
            putExtra(AlertContract.EXTRA_ALERT_BODY, body)
            putExtra(AlertContract.EXTRA_ALERT_ROOM, room)
            putExtra(AlertContract.EXTRA_ALERT_PROMPT, prompt)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            eventId.hashCode(),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (!room.isNullOrBlank()) "[$room] $body" else body

        val notification = NotificationCompat.Builder(context, AlertContract.CHANNEL_URGENT_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title.ifBlank { "ClawWatch urgent alert" })
            .setContentText(contentText.ifBlank { "Open ClawWatch for details." })
            .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setGroup(NOTIFICATION_GROUP)
            .setContentIntent(pendingIntent)
            .build()

        NotificationManagerCompat.from(context).notify(eventId.hashCode(), notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val existing = manager.getNotificationChannel(AlertContract.CHANNEL_URGENT_ALERTS)
        if (existing != null) return

        val channel = NotificationChannel(
            AlertContract.CHANNEL_URGENT_ALERTS,
            "ClawWatch Urgent Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Only urgent tagged alerts from ClawWatch services."
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
