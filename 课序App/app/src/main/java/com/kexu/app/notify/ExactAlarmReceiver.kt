package com.kexu.app.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 精确闹钟到点广播：发通知并从 [AlarmStore] 移除该条。 */
class ExactAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ExactAlarmScheduler.ACTION_ALARM) return
        val key = intent.getStringExtra("key") ?: return
        val title = intent.getStringExtra("title") ?: return
        val body = intent.getStringExtra("body") ?: ""
        val alarm = intent.getBooleanExtra("alarm", false)
        Notifier.post(context.applicationContext, key.hashCode(), title, body, alarm)
        AlarmStore.remove(context.applicationContext, key)
    }
}
