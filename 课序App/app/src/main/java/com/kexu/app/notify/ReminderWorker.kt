package com.kexu.app.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** 本地提醒（WorkManager 兜底通道）：到点后交给 [Notifier] 发通知，样式与精确闹钟一致。 */
class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: return Result.success()
        val body = inputData.getString("body") ?: ""
        val alarm = inputData.getBoolean("alarm", false)
        val id = inputData.getString("key")?.hashCode() ?: title.hashCode()
        Notifier.post(applicationContext, id, title, body, alarm)
        return Result.success()
    }
}
