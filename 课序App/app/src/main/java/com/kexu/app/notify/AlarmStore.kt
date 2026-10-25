package com.kexu.app.notify

import android.content.Context
import com.kexu.app.domain.ReminderEvent
import org.json.JSONArray
import org.json.JSONObject

/**
 * 已排的精确闹钟持久化：进程被杀/设备重启后，[BootRescheduleReceiver] 用它重建闹钟。
 * 只保存触发所需的最小字段（key/时间/标题/正文/是否响铃），不涉及业务数据。
 */
object AlarmStore {

    private const val PREFS = "kexu_alarm_store"
    private const val KEY_EVENTS = "events"

    fun save(context: Context, events: List<ReminderEvent>) {
        val array = JSONArray()
        events.filter { it.triggerAtMillis > System.currentTimeMillis() }.forEach { event ->
            array.put(
                JSONObject()
                    .put("key", event.key)
                    .put("at", event.triggerAtMillis)
                    .put("title", event.title)
                    .put("body", event.body)
                    .put("alarm", event.alarm)
            )
        }
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_EVENTS, array.toString())
            .apply()
    }

    fun load(context: Context): List<ReminderEvent> {
        val raw = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_EVENTS, null)
            ?: return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<ReminderEvent>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val key = obj.optString("key")
            if (key.isBlank()) continue
            out += ReminderEvent(
                key = key,
                triggerAtMillis = obj.optLong("at", 0L),
                title = obj.optString("title"),
                body = obj.optString("body"),
                alarm = obj.optBoolean("alarm", false)
            )
        }
        return out
    }

    fun remove(context: Context, key: String) {
        val remaining = load(context).filterNot { it.key == key }
        save(context, remaining)
    }

    fun clear(context: Context) {
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_EVENTS)
            .apply()
    }
}
