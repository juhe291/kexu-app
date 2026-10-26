package com.kexu.app.domain

import java.time.LocalDate

/** 学期信息：决定教学周与周次映射。 */
data class Semester(
    val name: String = "2026—2027 学年第一学期",
    val startDate: LocalDate = LocalDate.of(2026, 9, 7),
    val totalWeeks: Int = 20
)

/** 节次：只存上下课时间，布局由 KexuCore 计算。 */
data class Period(val from: String, val to: String) {
    val fromMin: Int get() = KexuCore.minutesOf(from)
    val toMin: Int get() = KexuCore.minutesOf(to)
}

/** 课程的一次上课安排；weeksCsv 为空表示每周，parity = odd/even 表示单双周。 */
data class Meeting(
    val id: Long = 0,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val parity: String? = null,
    val weeksCsv: String? = null,
    val room: String? = null
) {
    val weeks: Set<Int>?
        get() = weeksCsv?.split(',', ' ', '，')
            ?.mapNotNull { it.trim().toIntOrNull() }
            ?.toSet()
            ?.takeIf { it.isNotEmpty() }
}

/**
 * 课程单次例外：只影响「某一教学周的那一次课」，不改变周期规则。
 * 用 `courseId + week + 原 weekday + 原 startPeriod/endPeriod` 定位被执行的那一次课。
 */
data class CourseException(
    val id: String,
    val courseId: String,
    val week: Int,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val kind: String = KIND_CANCEL,
    val newWeekday: Int? = null,
    val newStartPeriod: Int? = null,
    val newEndPeriod: Int? = null,
    val newRoom: String? = null,
    val note: String = ""
) {
    val isCancel: Boolean get() = kind == KIND_CANCEL
    val isMove: Boolean get() = kind == KIND_MOVE

    companion object {
        const val KIND_CANCEL = "cancel"
        const val KIND_MOVE = "move"

        fun occurrenceKey(courseId: String, week: Int, weekday: Int, startPeriod: Int, endPeriod: Int): String =
            "$courseId|$week|$weekday|$startPeriod|$endPeriod"
    }
}

/**
 * 应用单次例外后的「某一次课」视图：星期/节次/教室可能已被调课改写。
 * 课表与今日页都消费这个模型，避免两处各写一遍例外逻辑。
 */
data class CourseOccurrence(
    val course: Course,
    val meeting: Meeting,
    val week: Int,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val room: String,
    val exceptionId: String? = null,
    val moved: Boolean = false,
    val cancelled: Boolean = false,
    val note: String = ""
)

data class Course(
    val id: String,
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val colorHex: String,
    val meetings: List<Meeting> = emptyList()
)

data class Task(
    val id: String,
    val title: String,
    val courseId: String? = null,
    val priority: String = "medium",
    val estimatedMinutes: Int? = null,
    val deadline: LocalDate? = null,
    val done: Boolean = false,
    val source: String = "manual",
    val week: Int? = null,
    val weekday: Int? = null,
    val from: String? = null,
    val to: String? = null,
    val goalId: String? = null,
    /** 重复规则：none / daily / weekly；完成后自动生成下一次任务。 */
    val repeatKind: String = RecurringTaskPlanner.NONE,
    /** 重复截止日期（可选）：下一次的时间晚于它就不再生成。 */
    val repeatUntil: LocalDate? = null,
    /** 重复系列 id：根任务用自身 id，后续每次生成的任务共享同一个系列 id。 */
    val repeatSeriesId: String? = null
) {
    val isRepeating: Boolean get() = repeatKind != RecurringTaskPlanner.NONE
}

data class Goal(
    val id: String,
    val title: String,
    val totalWeeks: Int = 12,
    val currentWeek: Int = 1,
    val weeklyHours: Int = 4,
    val doneHours: Int = 0,
    val progress: Int = 0,
    val next: String = ""
)

/** 设置项：目前用 DataStore 持久化，保持与原型设置页一致。 */
data class Prefs(
    val themeMode: String = "system",
    val accentHex: String = "#305FD6",
    val moduleOpen: Map<String, Boolean> = DEFAULT_MODULES,
    val availabilityStart: String = "07:30",
    val availabilityEnd: String = "22:30",
    val defaultTaskMinutes: Int = 60,
    val defaultPriority: String = "medium",
    val focusMinutes: Int = 50,
    val splitEnabled: Boolean = true,
    val splitMin: Int = 30,
    val splitMax: Int = 90,
    val bufferDays: Int = 1,
    val dailyCapHours: Int = 4,
    val restEnabled: Boolean = true,
    val restMinutes: Int = 15,
    val courseReminderMinutes: Int = 10,
    val taskReminderMinutes: Int = 10,
    val deadlineReminderMinutes: Int = 1440,
    val notifyEnabled: Boolean = true,
    val alarmEnabled: Boolean = false,
    val vibrateEnabled: Boolean = true,
    val sound: String = "默认提示音",
    val fontTitleScale: Int = 100,
    val fontBodyScale: Int = 100,
    val fontCaptionScale: Int = 100,
    val fontTimetableScale: Int = 100,
    /** 辅助说明文字透明度：100 = 不透明，70 = 更浅。 */
    val textOpacity: Int = 100,
    /** 色块（卡片/浅色块）透明度：100 = 不透明，70 = 半透明。 */
    val blockOpacity: Int = 100,
    /** 玻璃拟态：大卡片使用半透明磨砂质感。 */
    val glassEnabled: Boolean = true,
    /** 玻璃边缘畸变：卡片边缘加折射高光/暗边。 */
    val glassDistortion: Boolean = true,
    val taskSort: String = "default"
) {
    companion object {
        val DEFAULT_MODULES = mapOf(
            "agenda" to true,
            "deadline" to true,
            "inbox" to false,
            "goal" to false,
            "done" to false
        )
    }
}

/** 排程与首页共用的下一节课结果。 */
data class NextCourse(
    val course: Course,
    val meeting: Meeting,
    val startMin: Int,
    val endMin: Int,
    val startsInMin: Int,
    val week: Int,
    val weekday: Int,
    val startPeriod: Int = meeting.startPeriod,
    val endPeriod: Int = meeting.endPeriod,
    val moved: Boolean = false,
    val room: String? = null
)
