package com.kexu.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机 / 时间或时区变化 / 应用更新后重建精确闹钟。
 * AlarmManager 的闹钟不会在重启后保留；WorkManager 任务由系统自行恢复，这里只处理精确闹钟。
 */
class BootRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return
        val app = context.applicationContext
        ReminderScheduler.ensureChannels(app)
        val now = System.currentTimeMillis()
        val events = AlarmStore.load(app).filter { it.triggerAtMillis > now }
        if (events.isEmpty()) {
            AlarmStore.clear(app)
            return
        }
        if (ExactAlarmScheduler.canScheduleExact(app)) {
            val scheduled = ExactAlarmScheduler.schedule(app, events)
            if (scheduled > 0) AlarmStore.save(app, events)
        }
    }

    private companion object {
        val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED
        )
    }
}
