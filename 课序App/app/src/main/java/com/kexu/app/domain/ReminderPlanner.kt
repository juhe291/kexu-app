package com.kexu.app.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * 一条待触发的提醒。纯数据，不含 Android 类型，便于单测；
 * 由 [com.kexu.app.notify.ReminderScheduler] 分发给 WorkManager（兜底）或 AlarmManager（精确）。
 */
data class ReminderEvent(
    val key: String,
    val triggerAtMillis: Long,
    val title: String,
    val body: String,
    val alarm: Boolean
)

/**
 * 提醒计划器：课程开始、任务开始、截止日期三类提醒的统一纯逻辑。
 * 课程按「应用停课/调课后的真实安排」计算；停课不提醒，调课按新时间提醒。
 * 未来版本接入精确闹钟时直接复用同一份计划，避免两套逻辑漂移。
 */
object ReminderPlanner {

    /** 只排未来 7 天内的提醒（与原先 WorkManager 行为一致），避免一次排太远。 */
    const val HORIZON_DAYS = 7
    private const val DAY_MS = 86_400_000L

    fun plan(
        now: LocalDateTime,
        semester: Semester,
        courses: List<Course>,
        tasks: List<Task>,
        periods: List<Period>,
        exceptions: List<CourseException>,
        prefs: Prefs,
        zone: ZoneId = ZoneId.systemDefault(),
        horizonDays: Int = HORIZON_DAYS
    ): List<ReminderEvent> {
        if (!prefs.notifyEnabled) return emptyList()
        val nowMillis = now.atZone(zone).toInstant().toEpochMilli()
        val horizonMillis = horizonDays.coerceAtLeast(1) * DAY_MS
        val out = ArrayList<ReminderEvent>()

        fun add(key: String, atMillis: Long, title: String, body: String, alarm: Boolean) {
            val delay = atMillis - nowMillis
            if (delay <= 0 || delay > horizonMillis) return
            out += ReminderEvent(key, atMillis, title, body, alarm)
        }

        fun epoch(date: LocalDate, hhmm: String): Long {
            val minutes = KexuCore.minutesOf(hhmm)
            if (minutes < 0) return -1
            return date.atTime(LocalTime.of(minutes / 60, minutes % 60))
                .atZone(zone).toInstant().toEpochMilli()
        }

        val today = now.toLocalDate()
        for (offset in 0 until horizonDays.coerceAtLeast(1)) {
            val date = today.plusDays(offset.toLong())
            val week = KexuCore.weekIndexForDate(semester.startDate, date)
            if (week < 1 || week > semester.totalWeeks) continue
            val weekday = KexuCore.weekdayIndex(date)
            KexuCore.effectiveCoursesOnDay(courses, exceptions, week, weekday).forEach { occurrence ->
                if (occurrence.cancelled) return@forEach
                val course = occurrence.course
                val from = periods.getOrNull(occurrence.startPeriod - 1)?.from ?: return@forEach
                val at = epoch(date, from) - prefs.courseReminderMinutes * 60_000L
                add(
                    "course-${course.id}-${date}-${occurrence.startPeriod}",
                    at,
                    "${course.name} 即将开始" + if (occurrence.moved) "（调课）" else "",
                    "$from · ${occurrence.room}",
                    prefs.alarmEnabled
                )
            }
        }

        tasks.filter { !it.done && it.week != null && it.weekday != null && it.from != null }.forEach { task ->
            val date = runCatching {
                KexuCore.dateForWeekDay(semester.startDate, task.week!!, task.weekday!!)
            }.getOrNull() ?: return@forEach
            val at = epoch(date, task.from!!) - prefs.taskReminderMinutes * 60_000L
            add(
                "task-${task.id}",
                at,
                "任务开始：${task.title}",
                "${task.from}–${task.to ?: ""} · ${KexuCore.formatDuration(task.estimatedMinutes)}",
                prefs.alarmEnabled
            )
        }

        tasks.filter { !it.done && it.deadline != null }.forEach { task ->
            val date = task.deadline ?: return@forEach
            val at = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli() -
                prefs.deadlineReminderMinutes * 60_000L
            add(
                "deadline-${task.id}",
                at,
                "任务即将截止：${task.title}",
                "${date.monthValue} 月 ${date.dayOfMonth} 日截止",
                false
            )
        }
        return out.distinctBy { it.key }.sortedBy { it.triggerAtMillis }
    }
}
