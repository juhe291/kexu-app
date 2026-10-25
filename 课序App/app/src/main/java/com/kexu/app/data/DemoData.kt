package com.kexu.app.data

import com.kexu.app.domain.KexuCore
import java.time.LocalDate

/** 首次启动时播种的示例数据：与 V8 原型保持同一套课程/节次/任务，方便直接看到效果。 */
object DemoData {

    fun semester(): SemesterEntity = SemesterEntity(
        id = 1,
        name = "2026—2027 学年第一学期",
        startDate = "2026-09-07",
        totalWeeks = 20
    )

    fun periods(): List<PeriodEntity> = KexuCore.defaultPeriods().mapIndexed { index, period ->
        PeriodEntity(position = index, fromTime = period.from, toTime = period.to)
    }

    fun courses(): List<CourseEntity> = listOf(
        CourseEntity("ds", "数据结构", "王启明", "教三 B201", KexuCore.PALETTE[0]),
        CourseEntity("net", "计算机网络", "李芷", "教一 A302", KexuCore.PALETTE[1]),
        CourseEntity("math", "高等数学", "陈立", "教二 A101", KexuCore.PALETTE[2]),
        CourseEntity("num", "计算方法", "周敏", "教一 A203", KexuCore.PALETTE[3]),
        CourseEntity("prob", "概率论", "赵昀", "教二 B204", KexuCore.PALETTE[4]),
        CourseEntity("physics", "大学物理", "孙楷", "教一 B105", KexuCore.PALETTE[5]),
        CourseEntity("co", "计算机组成原理", "刘畅", "教三 B305", KexuCore.PALETTE[6])
    )

    fun meetings(): List<MeetingEntity> = listOf(
        MeetingEntity(courseId = "ds", weekday = 0, startPeriod = 3, endPeriod = 4),
        MeetingEntity(courseId = "ds", weekday = 2, startPeriod = 3, endPeriod = 4),
        MeetingEntity(courseId = "ds", weekday = 3, startPeriod = 7, endPeriod = 8, room = "机房 208"),
        MeetingEntity(courseId = "net", weekday = 0, startPeriod = 1, endPeriod = 2),
        MeetingEntity(courseId = "net", weekday = 3, startPeriod = 3, endPeriod = 4),
        MeetingEntity(courseId = "math", weekday = 1, startPeriod = 1, endPeriod = 2),
        MeetingEntity(courseId = "math", weekday = 4, startPeriod = 1, endPeriod = 2),
        MeetingEntity(courseId = "num", weekday = 1, startPeriod = 3, endPeriod = 4, parity = "even"),
        MeetingEntity(courseId = "num", weekday = 3, startPeriod = 5, endPeriod = 6),
        MeetingEntity(courseId = "num", weekday = 4, startPeriod = 9, endPeriod = 10, weeksCsv = "8,10,12,14,16"),
        MeetingEntity(courseId = "prob", weekday = 1, startPeriod = 5, endPeriod = 6),
        MeetingEntity(courseId = "physics", weekday = 2, startPeriod = 1, endPeriod = 2),
        MeetingEntity(courseId = "physics", weekday = 4, startPeriod = 5, endPeriod = 6, parity = "odd"),
        MeetingEntity(courseId = "co", weekday = 3, startPeriod = 1, endPeriod = 2)
    )

    fun tasks(today: LocalDate = LocalDate.now()): List<TaskEntity> {
        val start = LocalDate.parse("2026-09-07")
        fun taskOf(date: LocalDate, id: String, title: String, courseId: String?, priority: String,
                   minutes: Int, deadline: LocalDate?, done: Boolean, source: String,
                   from: String, to: String, goalId: String? = null): TaskEntity {
            return TaskEntity(
                id = id,
                title = title,
                courseId = courseId,
                priority = priority,
                estimatedMinutes = minutes,
                deadline = deadline?.toString(),
                done = done,
                source = source,
                week = KexuCore.weekIndexForDate(start, date),
                weekday = KexuCore.weekdayIndex(date),
                fromTime = from,
                toTime = to,
                goalId = goalId
            )
        }
        val tomorrow = today.plusDays(1)
        val third = today.plusDays(2)
        return listOf(
            taskOf(today, "t1", "完成数据结构实验报告初稿", "ds", "high", 60, today.plusDays(3), false, "auto", "14:00", "15:00"),
            taskOf(today, "t2", "Python 进阶学习", null, "medium", 60, null, false, "goal", "16:00", "17:00", "g1"),
            taskOf(today, "t3", "复习计算机网络第三章", "net", "medium", 45, null, false, "plan", "19:30", "20:15"),
            taskOf(tomorrow, "t4", "完善数据结构实验报告", "ds", "high", 60, today.plusDays(3), false, "auto", "16:00", "17:00"),
            taskOf(third, "t5", "整理计算方法笔记", "num", "medium", 45, null, false, "plan", "19:00", "19:45"),
            TaskEntity(
                id = "i1", title = "准备英语课前展示", courseId = null, priority = "medium",
                estimatedMinutes = 45, deadline = today.plusDays(9).toString(), done = false, source = "manual"
            ),
            TaskEntity(
                id = "i2", title = "整理计算方法错题", courseId = "num", priority = "low",
                estimatedMinutes = 60, deadline = null, done = false, source = "manual"
            )
        )
    }

    fun goals(): List<GoalEntity> = listOf(
        GoalEntity(
            id = "g1", title = "Python 系统学习", totalWeeks = 12, currentWeek = 3,
            weeklyHours = 4, doneHours = 1, progress = 25, next = "完成第 3 章练习"
        )
    )
}
