package com.kexu.app.notify

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/** 统一的本地通知发送：WorkManager 与精确闹钟共用，避免两套通知样式漂移。 */
object Notifier {

    fun post(context: Context, id: Int, title: String, body: String, alarm: Boolean): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            ReminderScheduler.ensureChannels(context)
            val channel = if (alarm) ReminderScheduler.CHANNEL_ALARM else ReminderScheduler.CHANNEL_NOTIFY
            val notification = NotificationCompat.Builder(context, channel)
                .setSmallIcon(com.kexu.app.R.drawable.ic_stat_kexu)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(if (alarm) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(id, notification)
            true
        } catch (e: SecurityException) {
            false
        }
    }
}
