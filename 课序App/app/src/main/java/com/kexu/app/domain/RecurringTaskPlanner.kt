package com.kexu.app.domain

import java.time.LocalDate
import java.util.UUID

/**
 * 重复任务纯逻辑：完成一条重复任务后，计算下一条任务。
 *
 * 规则（1.0 范围，保持简单可验证）：
 * - `daily` 每天一条、`weekly` 每周一条；
 * - 已安排时间（week+weekday+from/to）的任务：按日期 +1 天 / +7 天，换算回新的教学周与星期；
 * - 只有截止日期的任务：把截止日期 +1 天 / +7 天；
 * - 既没有安排时间也没有截止日期的任务无法计算下一次，返回 null；
 * - 可选 `repeatUntil`：下一次的日期晚于它就不再生成；
 * - 已安排时间超出学期最后一天时不再生成（避免课表里出现第 21 周的幽灵任务）。
 *
 * 生成的任务 `done=false`、`source="recurring"`，并携带同一个 `repeatSeriesId`，
 * 便于「完成后不重复生成」与后续可能需要的整系列操作。
 */
object RecurringTaskPlanner {

    const val NONE = "none"
    const val DAILY = "daily"
    const val WEEKLY = "weekly"

    val KINDS = listOf(NONE, DAILY, WEEKLY)

    fun isValidKind(kind: String): Boolean = kind in KINDS

    fun label(kind: String): String = when (kind) {
        DAILY -> "每天"
        WEEKLY -> "每周"
        else -> "不重复"
    }

    /** 下一条任务；不重复、无法计算或已到重复截止日期时返回 null。 */
    fun nextOccurrence(
        task: Task,
        semester: Semester,
        newId: String = UUID.randomUUID().toString()
    ): Task? {
        if (!task.isRepeating) return null
        val stepDays = when (task.repeatKind) {
            DAILY -> 1L
            WEEKLY -> 7L
            else -> return null
        }
        var nextWeek = task.week
        var nextWeekday = task.weekday
        var nextDeadline = task.deadline?.plusDays(stepDays)
        var anchorDate: LocalDate? = null

        if (task.week != null && task.weekday != null) {
            val currentDate = KexuCore.dateForWeekDay(semester.startDate, task.week, task.weekday)
            val next = currentDate.plusDays(stepDays)
            anchorDate = next
            nextWeek = KexuCore.weekIndexForDate(semester.startDate, next)
            nextWeekday = KexuCore.weekdayIndex(next)
            if (nextWeek < 1 || nextWeek > semester.totalWeeks) return null
        } else if (task.deadline != null) {
            anchorDate = task.deadline.plusDays(stepDays)
        } else {
            return null
        }

        val until = task.repeatUntil
        if (until != null && anchorDate != null && anchorDate.isAfter(until)) return null

        return task.copy(
            id = newId,
            done = false,
            source = "recurring",
            week = nextWeek,
            weekday = nextWeekday,
            deadline = nextDeadline,
            repeatSeriesId = task.repeatSeriesId ?: task.id
        )
    }
}
