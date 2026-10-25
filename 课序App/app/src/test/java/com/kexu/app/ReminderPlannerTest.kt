package com.kexu.app

import com.kexu.app.domain.Course
import com.kexu.app.domain.CourseException
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Meeting
import com.kexu.app.domain.Prefs
import com.kexu.app.domain.ReminderPlanner
import com.kexu.app.domain.Semester
import com.kexu.app.domain.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 提醒计划器纯逻辑测试：课程/任务/截止三类提醒，以及停课/调课对提醒的影响。
 * 固定时区与时间，避免依赖运行机器时区。
 */
class ReminderPlannerTest {

    private val zone = ZoneId.of("Asia/Shanghai")
    private val semester = Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 20)
    private val periods = KexuCore.defaultPeriods()
    private val mondayWeek8 = LocalDate.of(2026, 10, 26)

    private val course = Course(
        id = "c1", name = "数据结构", teacher = "张老师", room = "A101", colorHex = "#2563EB",
        meetings = listOf(Meeting(weekday = 0, startPeriod = 1, endPeriod = 2))
    )

    private fun now(hour: Int = 7, minute: Int = 0): LocalDateTime = mondayWeek8.atTime(hour, minute)

    private fun prefs(
        notifyEnabled: Boolean = true,
        alarmEnabled: Boolean = false,
        courseReminder: Int = 10,
        taskReminder: Int = 10,
        deadlineReminder: Int = 1440
    ) = Prefs(
        notifyEnabled = notifyEnabled,
        alarmEnabled = alarmEnabled,
        courseReminderMinutes = courseReminder,
        taskReminderMinutes = taskReminder,
        deadlineReminderMinutes = deadlineReminder
    )

    private fun millis(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `课程开始提醒按提前分钟数生成`() {
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = listOf(course), tasks = emptyList(),
            periods = periods, exceptions = emptyList(), prefs = prefs(), zone = zone
        )
        val event = events.firstOrNull { it.key == "course-c1-2026-10-26-1" }
        assertTrue(events.toString(), event != null)
        assertEquals("数据结构 即将开始", event!!.title)
        assertEquals(millis(mondayWeek8, 8, 20), event.triggerAtMillis)
        assertTrue(event.body.contains("A101"))
        assertFalse(event.alarm)
    }

    @Test
    fun `开启响铃时课程提醒带 alarm 标记`() {
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = listOf(course), tasks = emptyList(),
            periods = periods, exceptions = emptyList(), prefs = prefs(alarmEnabled = true), zone = zone
        )
        assertTrue(events.first { it.key.contains("course-c1") }.alarm)
    }

    @Test
    fun `停课的那一次不再产生提醒`() {
        val exceptions = listOf(
            CourseException(
                id = "e1", courseId = "c1", week = 8, weekday = 0, startPeriod = 1, endPeriod = 2,
                kind = CourseException.KIND_CANCEL
            )
        )
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = listOf(course), tasks = emptyList(),
            periods = periods, exceptions = exceptions, prefs = prefs(), zone = zone
        )
        assertTrue(events.toString(), events.none { it.key == "course-c1-2026-10-26-1" })
    }

    @Test
    fun `调课的那一次按新时间提醒并标注调课`() {
        val exceptions = listOf(
            CourseException(
                id = "m1", courseId = "c1", week = 8, weekday = 0, startPeriod = 1, endPeriod = 2,
                kind = CourseException.KIND_MOVE, newWeekday = 1, newStartPeriod = 3, newEndPeriod = 4, newRoom = "B202"
            )
        )
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = listOf(course), tasks = emptyList(),
            periods = periods, exceptions = exceptions, prefs = prefs(), zone = zone
        )
        assertTrue(events.none { it.key == "course-c1-2026-10-26-1" })
        val moved = events.first { it.key == "course-c1-2026-10-27-3" }
        // 默认节次第 3 节 10:15 开始，提前 10 分钟
        assertEquals(millis(LocalDate.of(2026, 10, 27), 10, 5), moved.triggerAtMillis)
        assertTrue(moved.title.contains("调课"))
        assertTrue(moved.body.contains("B202"))
    }

    @Test
    fun `关闭通知后不产生任何提醒`() {
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = listOf(course), tasks = emptyList(),
            periods = periods, exceptions = emptyList(), prefs = prefs(notifyEnabled = false), zone = zone
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `已经过去的提醒不再排`() {
        // 周一 9:00 时，8:20 的课程提醒已经过去
        val events = ReminderPlanner.plan(
            now = now(9, 0), semester = semester, courses = listOf(course), tasks = emptyList(),
            periods = periods, exceptions = emptyList(), prefs = prefs(), zone = zone
        )
        assertTrue(events.none { it.key == "course-c1-2026-10-26-1" })
    }

    @Test
    fun `任务开始提醒`() {
        val task = Task(
            id = "t1", title = "复习第一章", week = 8, weekday = 4, from = "14:00", to = "15:00",
            estimatedMinutes = 60
        )
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = emptyList(), tasks = listOf(task),
            periods = periods, exceptions = emptyList(), prefs = prefs(taskReminder = 15), zone = zone
        )
        val event = events.first { it.key == "task-t1" }
        // 第 8 周周五 = 2026-10-30，14:00 提前 15 分钟
        assertEquals(millis(LocalDate.of(2026, 10, 30), 13, 45), event.triggerAtMillis)
        assertTrue(event.title.contains("复习第一章"))
    }

    @Test
    fun `已完成任务不提醒`() {
        val task = Task(
            id = "t1", title = "复习第一章", week = 8, weekday = 4, from = "14:00", to = "15:00", done = true
        )
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = emptyList(), tasks = listOf(task),
            periods = periods, exceptions = emptyList(), prefs = prefs(), zone = zone
        )
        assertTrue(events.none { it.key == "task-t1" })
    }

    @Test
    fun `截止提醒按提前天数生成`() {
        val deadline = LocalDate.of(2026, 10, 28)
        val task = Task(id = "t2", title = "交实验报告", deadline = deadline)
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = emptyList(), tasks = listOf(task),
            periods = periods, exceptions = emptyList(), prefs = prefs(deadlineReminder = 1440), zone = zone
        )
        val event = events.first { it.key == "deadline-t2" }
        // 2026-10-28 09:00 提前 1 天 = 2026-10-27 09:00
        assertEquals(millis(LocalDate.of(2026, 10, 27), 9, 0), event.triggerAtMillis)
        assertTrue(event.body.contains("10 月 28 日"))
        assertFalse(event.alarm)
    }

    @Test
    fun `超过7天视野的提醒不排`() {
        val task = Task(id = "t3", title = "远期任务", deadline = LocalDate.of(2026, 12, 1))
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = emptyList(), tasks = listOf(task),
            periods = periods, exceptions = emptyList(), prefs = prefs(), zone = zone
        )
        assertTrue(events.none { it.key == "deadline-t3" })
    }

    @Test
    fun `提醒按触发时间升序返回`() {
        val task = Task(id = "t1", title = "复习", week = 8, weekday = 4, from = "14:00", to = "15:00")
        val deadline = Task(id = "t2", title = "交报告", deadline = LocalDate.of(2026, 10, 28))
        val events = ReminderPlanner.plan(
            now = now(), semester = semester, courses = listOf(course), tasks = listOf(task, deadline),
            periods = periods, exceptions = emptyList(), prefs = prefs(), zone = zone
        )
        assertEquals(events.sortedBy { it.triggerAtMillis }, events)
        assertTrue(events.size >= 3)
    }
}
