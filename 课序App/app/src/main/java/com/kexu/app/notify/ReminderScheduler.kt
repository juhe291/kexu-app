package com.kexu.app.notify

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.kexu.app.domain.ReminderPlanner
import com.kexu.app.ui.AppUiState
import java.util.concurrent.TimeUnit

/**
 * 提醒调度：
 * - 有精确闹钟权限且用户开启「响铃式提醒」→ AlarmManager 精确触发；
 * - 否则 → WorkManager 兜底（系统省电策略下可能有分钟级延迟）。
 * 两类调度共用 [ReminderPlanner] 的同一份提醒计划，行为不漂移。
 */
object ReminderScheduler {

    const val CHANNEL_NOTIFY = "kexu_notify"
    const val CHANNEL_ALARM = "kexu_alarm"
    private const val TAG = "kexu_reminders"

    /** 最近一次同步是否使用了精确闹钟（设置页展示状态用）。 */
    @Volatile
    var lastUsedExactAlarms: Boolean = false
        private set

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL_NOTIFY, "课程与任务提醒", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "课程开始、任务开始与截止提醒"
            }
        )
        manager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL_ALARM, "响铃式提醒", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "重要事项的响铃提醒"
                enableVibration(true)
            }
        )
    }

    /** App 数据/设置变化时重新排期：先全部取消，再按同一份计划分发。 */
    fun sync(context: Context, state: AppUiState) {
        ensureChannels(context)
        val workManager = WorkManager.getInstance(context)
        workManager.cancelAllWorkByTag(TAG)
        ExactAlarmScheduler.cancelAll(context)

        if (!state.prefs.notifyEnabled || state.loading) {
            AlarmStore.clear(context)
            lastUsedExactAlarms = false
            return
        }

        val events = ReminderPlanner.plan(
            now = java.time.LocalDateTime.now(),
            semester = state.semester,
            courses = state.courses,
            tasks = state.tasks,
            periods = state.periods,
            exceptions = state.exceptions,
            prefs = state.prefs
        )

        val useExact = state.prefs.alarmEnabled && ExactAlarmScheduler.canScheduleExact(context)
        lastUsedExactAlarms = useExact
        if (useExact) {
            val scheduled = ExactAlarmScheduler.schedule(context, events)
            AlarmStore.save(context, events)
            if (scheduled > 0) return
            // 权限在排程瞬间被撤销：清空后走 WorkManager 兜底
            AlarmStore.clear(context)
        }

        val now = System.currentTimeMillis()
        events.forEach { event ->
            val delay = event.triggerAtMillis - now
            if (delay <= 0) return@forEach
            val work = OneTimeWorkRequestBuilder<ReminderWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                .setInputData(
                    Data.Builder()
                        .putString("key", event.key)
                        .putString("title", event.title)
                        .putString("body", event.body)
                        .putBoolean("alarm", event.alarm)
                        .build()
                )
                .addTag(TAG)
                .build()
            workManager.enqueueUniqueWork("kexu-${event.key}", ExistingWorkPolicy.REPLACE, work)
        }
    }
}
