package com.kexu.app.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.kexu.app.domain.ReminderEvent

/**
 * 精确闹钟：Android 12+ 需要 `SCHEDULE_EXACT_ALARM` 权限（用户可在系统设置里撤销），
 * 有权限时用 `setExactAndAllowWhileIdle` 在到点时唤醒；没有权限时由调用方回退 WorkManager。
 */
object ExactAlarmScheduler {

    const val ACTION_ALARM = "com.kexu.app.action.EXACT_ALARM"
    private const val EXTRA_KEY = "key"
    private const val EXTRA_TITLE = "title"
    private const val EXTRA_BODY = "body"
    private const val EXTRA_ALARM = "alarm"

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return manager.canScheduleExactAlarms()
    }

    /** 先取消旧闹钟再排新的；返回实际排程数量（权限不足时为 0）。 */
    fun schedule(context: Context, events: List<ReminderEvent>): Int {
        cancelAll(context)
        if (events.isEmpty() || !canScheduleExact(context)) return 0
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val now = System.currentTimeMillis()
        var count = 0
        events.forEach { event ->
            if (event.triggerAtMillis <= now) return@forEach
            val pending = pendingIntent(context, event)
            try {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, event.triggerAtMillis, pending)
                count++
            } catch (e: SecurityException) {
                // 权限被撤销的瞬间：交给 WorkManager 兜底
            }
        }
        return count
    }

    /** 取消此前排在系统里的所有精确闹钟（从 [AlarmStore] 读取 key 计算 PendingIntent）。 */
    fun cancelAll(context: Context) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        AlarmStore.load(context).forEach { event ->
            val intent = Intent(context, ExactAlarmReceiver::class.java).apply {
                action = ACTION_ALARM
                putExtra(EXTRA_KEY, event.key)
            }
            val pending = PendingIntent.getBroadcast(
                context,
                event.key.hashCode(),
                intent,
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )
            if (pending != null) {
                manager.cancel(pending)
                pending.cancel()
            }
        }
    }

    /** 跳转到系统的「允许精确闹钟」设置页；Android 12 以下无需权限，返回 null。 */
    fun permissionSettingsIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return Intent(
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            Uri.parse("package:${context.packageName}")
        )
    }

    private fun pendingIntent(context: Context, event: ReminderEvent): PendingIntent {
        val intent = Intent(context, ExactAlarmReceiver::class.java).apply {
            action = ACTION_ALARM
            putExtra(EXTRA_KEY, event.key)
            putExtra(EXTRA_TITLE, event.title)
            putExtra(EXTRA_BODY, event.body)
            putExtra(EXTRA_ALARM, event.alarm)
        }
        return PendingIntent.getBroadcast(
            context,
            event.key.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
