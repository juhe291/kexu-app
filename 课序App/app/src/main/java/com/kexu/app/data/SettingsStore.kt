package com.kexu.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.kexu.app.domain.Prefs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.kexuDataStore by preferencesDataStore(name = "kexu_prefs")

/** DataStore 保存界面偏好：主题、强调色、折叠状态、可用时间、默认值与提醒设置。 */
class SettingsStore(context: Context) {

    private val store = context.applicationContext.kexuDataStore

    val prefs: Flow<Prefs> = store.data.map { data ->
        Prefs(
            themeMode = data[K.themeMode] ?: "system",
            accentHex = (data[K.accentHex] ?: "#305FD6").let { if (it.equals("#1F5CF0", ignoreCase = true)) "#305FD6" else it },
            moduleOpen = mapOf(
                "agenda" to (data[K.moduleAgenda] ?: true),
                "deadline" to (data[K.moduleDeadline] ?: true),
                "inbox" to (data[K.moduleInbox] ?: false),
                "goal" to (data[K.moduleGoal] ?: false),
                "done" to (data[K.moduleDone] ?: false)
            ),
            availabilityStart = data[K.availabilityStart] ?: "07:30",
            availabilityEnd = data[K.availabilityEnd] ?: "22:30",
            defaultTaskMinutes = data[K.defaultTaskMinutes] ?: 60,
            defaultPriority = data[K.defaultPriority] ?: "medium",
            focusMinutes = data[K.focusMinutes] ?: 50,
            splitEnabled = data[K.splitEnabled] ?: true,
            splitMin = data[K.splitMin] ?: 30,
            splitMax = data[K.splitMax] ?: 90,
            bufferDays = data[K.bufferDays] ?: 1,
            dailyCapHours = data[K.dailyCapHours] ?: 4,
            restEnabled = data[K.restEnabled] ?: true,
            restMinutes = data[K.restMinutes] ?: 15,
            courseReminderMinutes = data[K.courseReminder] ?: 10,
            taskReminderMinutes = data[K.taskReminder] ?: 10,
            deadlineReminderMinutes = data[K.deadlineReminder] ?: 1440,
            notifyEnabled = data[K.notifyEnabled] ?: true,
            alarmEnabled = data[K.alarmEnabled] ?: false,
            vibrateEnabled = data[K.vibrateEnabled] ?: true,
            sound = data[K.sound] ?: "默认提示音",
            fontTitleScale = data[K.fontTitleScale] ?: 100,
            fontBodyScale = data[K.fontBodyScale] ?: 100,
            fontCaptionScale = data[K.fontCaptionScale] ?: 100,
            fontTimetableScale = data[K.fontTimetableScale] ?: 100,
        textOpacity = data[K.textOpacity] ?: 100,
        blockOpacity = data[K.blockOpacity] ?: 100,
        glassEnabled = data[K.glassEnabled] ?: true,
        glassDistortion = data[K.glassDistortion] ?: true,
            taskSort = data[K.taskSort] ?: "default"
        )
    }

    suspend fun save(prefs: Prefs) {
        store.edit { data ->
            data[K.themeMode] = prefs.themeMode
            data[K.accentHex] = prefs.accentHex
            data[K.moduleAgenda] = prefs.moduleOpen["agenda"] ?: true
            data[K.moduleDeadline] = prefs.moduleOpen["deadline"] ?: true
            data[K.moduleInbox] = prefs.moduleOpen["inbox"] ?: false
            data[K.moduleGoal] = prefs.moduleOpen["goal"] ?: false
            data[K.moduleDone] = prefs.moduleOpen["done"] ?: false
            data[K.availabilityStart] = prefs.availabilityStart
            data[K.availabilityEnd] = prefs.availabilityEnd
            data[K.defaultTaskMinutes] = prefs.defaultTaskMinutes
            data[K.defaultPriority] = prefs.defaultPriority
            data[K.focusMinutes] = prefs.focusMinutes
            data[K.splitEnabled] = prefs.splitEnabled
            data[K.splitMin] = prefs.splitMin
            data[K.splitMax] = prefs.splitMax
            data[K.bufferDays] = prefs.bufferDays
            data[K.dailyCapHours] = prefs.dailyCapHours
            data[K.restEnabled] = prefs.restEnabled
            data[K.restMinutes] = prefs.restMinutes
            data[K.courseReminder] = prefs.courseReminderMinutes
            data[K.taskReminder] = prefs.taskReminderMinutes
            data[K.deadlineReminder] = prefs.deadlineReminderMinutes
            data[K.notifyEnabled] = prefs.notifyEnabled
            data[K.alarmEnabled] = prefs.alarmEnabled
            data[K.vibrateEnabled] = prefs.vibrateEnabled
            data[K.sound] = prefs.sound
            data[K.fontTitleScale] = prefs.fontTitleScale
            data[K.fontBodyScale] = prefs.fontBodyScale
            data[K.fontCaptionScale] = prefs.fontCaptionScale
            data[K.fontTimetableScale] = prefs.fontTimetableScale
        data[K.textOpacity] = prefs.textOpacity
        data[K.blockOpacity] = prefs.blockOpacity
        data[K.glassEnabled] = prefs.glassEnabled
        data[K.glassDistortion] = prefs.glassDistortion
            data[K.taskSort] = prefs.taskSort
        }
    }

    private object K {
        val themeMode = stringPreferencesKey("theme_mode")
        val accentHex = stringPreferencesKey("accent_hex")
        val moduleAgenda = booleanPreferencesKey("module_agenda")
        val moduleDeadline = booleanPreferencesKey("module_deadline")
        val moduleInbox = booleanPreferencesKey("module_inbox")
        val moduleGoal = booleanPreferencesKey("module_goal")
        val moduleDone = booleanPreferencesKey("module_done")
        val availabilityStart = stringPreferencesKey("availability_start")
        val availabilityEnd = stringPreferencesKey("availability_end")
        val defaultTaskMinutes = intPreferencesKey("default_task_minutes")
        val defaultPriority = stringPreferencesKey("default_priority")
        val focusMinutes = intPreferencesKey("focus_minutes")
        val splitEnabled = booleanPreferencesKey("split_enabled")
        val splitMin = intPreferencesKey("split_min")
        val splitMax = intPreferencesKey("split_max")
        val bufferDays = intPreferencesKey("buffer_days")
        val dailyCapHours = intPreferencesKey("daily_cap_hours")
        val restEnabled = booleanPreferencesKey("rest_enabled")
        val restMinutes = intPreferencesKey("rest_minutes")
        val courseReminder = intPreferencesKey("course_reminder")
        val taskReminder = intPreferencesKey("task_reminder")
        val deadlineReminder = intPreferencesKey("deadline_reminder")
        val notifyEnabled = booleanPreferencesKey("notify_enabled")
        val alarmEnabled = booleanPreferencesKey("alarm_enabled")
        val vibrateEnabled = booleanPreferencesKey("vibrate_enabled")
        val sound = stringPreferencesKey("sound")
        val fontTitleScale = intPreferencesKey("font_title_scale")
        val fontBodyScale = intPreferencesKey("font_body_scale")
        val fontCaptionScale = intPreferencesKey("font_caption_scale")
        val fontTimetableScale = intPreferencesKey("font_timetable_scale")
    val textOpacity = intPreferencesKey("text_opacity")
    val blockOpacity = intPreferencesKey("block_opacity")
    val glassEnabled = booleanPreferencesKey("glass_enabled")
    val glassDistortion = booleanPreferencesKey("glass_distortion")
        val taskSort = stringPreferencesKey("task_sort")
    }
}
