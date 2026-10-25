package com.kexu.app

import com.kexu.app.domain.RecurringTaskPlanner
import com.kexu.app.domain.Semester
import com.kexu.app.domain.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 重复任务纯逻辑测试：完成后生成下一次任务。 */
class RecurringTaskPlannerTest {

    private val semester = Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 20)

    private fun scheduled(kind: String, week: Int = 8, weekday: Int = 0) = Task(
        id = "task-1",
        title = "每天背单词",
        week = week,
        weekday = weekday,
        from = "14:00",
        to = "15:00",
        estimatedMinutes = 30,
        repeatKind = kind
    )

    @Test
    fun `每周任务生成下一周同一天`() {
        // 第 8 周周一 = 2026-10-26，下一周 = 第 9 周周一
        val next = RecurringTaskPlanner.nextOccurrence(scheduled(RecurringTaskPlanner.WEEKLY), semester)
        assertTrue(next != null)
        assertEquals(9, next!!.week)
        assertEquals(0, next.weekday)
        assertFalse(next.done)
        assertEquals("recurring", next.source)
        assertEquals("task-1", next.repeatSeriesId)
        assertEquals("14:00", next.from)
        assertEquals(RecurringTaskPlanner.WEEKLY, next.repeatKind)
    }

    @Test
    fun `每天任务生成第二天`() {
        val next = RecurringTaskPlanner.nextOccurrence(scheduled(RecurringTaskPlanner.DAILY), semester)
        assertTrue(next != null)
        assertEquals(8, next!!.week)
        assertEquals(1, next.weekday) // 周一 → 周二
    }

    @Test
    fun `周日每天任务跨到下一周周一`() {
        val task = scheduled(RecurringTaskPlanner.DAILY, week = 8, weekday = 6)
        val next = RecurringTaskPlanner.nextOccurrence(task, semester)
        assertTrue(next != null)
        assertEquals(9, next!!.week)
        assertEquals(0, next.weekday)
    }

    @Test
    fun `只有截止日期的任务按天或周顺延截止日期`() {
        val daily = Task(id = "d1", title = "浇水", deadline = LocalDate.of(2026, 10, 26), repeatKind = RecurringTaskPlanner.DAILY)
        val nextDaily = RecurringTaskPlanner.nextOccurrence(daily, semester)
        assertEquals(LocalDate.of(2026, 10, 27), nextDaily!!.deadline)
        assertNull(nextDaily.week)

        val weekly = Task(id = "w1", title = "周报", deadline = LocalDate.of(2026, 10, 26), repeatKind = RecurringTaskPlanner.WEEKLY)
        assertEquals(LocalDate.of(2026, 11, 2), RecurringTaskPlanner.nextOccurrence(weekly, semester)!!.deadline)
    }

    @Test
    fun `重复截止日期之后不再生成`() {
        val task = scheduled(RecurringTaskPlanner.WEEKLY).copy(repeatUntil = LocalDate.of(2026, 10, 26))
        // 下一次是 2026-11-02，已经被 repeatUntil 挡住
        assertNull(RecurringTaskPlanner.nextOccurrence(task, semester))
    }

    @Test
    fun `重复截止日期当天仍可生成`() {
        val task = scheduled(RecurringTaskPlanner.WEEKLY).copy(repeatUntil = LocalDate.of(2026, 11, 2))
        assertTrue(RecurringTaskPlanner.nextOccurrence(task, semester) != null)
    }

    @Test
    fun `不重复任务不生成下一次`() {
        val task = scheduled(RecurringTaskPlanner.NONE)
        assertNull(RecurringTaskPlanner.nextOccurrence(task, semester))
    }

    @Test
    fun `既无时间也无截止日期的任务无法计算下一次`() {
        val task = Task(id = "x", title = "随手任务", repeatKind = RecurringTaskPlanner.DAILY)
        assertNull(RecurringTaskPlanner.nextOccurrence(task, semester))
    }

    @Test
    fun `系列id在后续生成中保持不变`() {
        val child = scheduled(RecurringTaskPlanner.WEEKLY).copy(repeatSeriesId = "series-42")
        val next = RecurringTaskPlanner.nextOccurrence(child, semester)
        assertEquals("series-42", next!!.repeatSeriesId)
    }

    @Test
    fun `超出学期最后一周不再生成`() {
        val lastWeek = scheduled(RecurringTaskPlanner.WEEKLY, week = 20, weekday = 0)
        assertNull(RecurringTaskPlanner.nextOccurrence(lastWeek, semester))
    }

    @Test
    fun `星期标签与合法性`() {
        assertEquals("不重复", RecurringTaskPlanner.label(RecurringTaskPlanner.NONE))
        assertEquals("每天", RecurringTaskPlanner.label(RecurringTaskPlanner.DAILY))
        assertEquals("每周", RecurringTaskPlanner.label(RecurringTaskPlanner.WEEKLY))
        assertTrue(RecurringTaskPlanner.isValidKind("daily"))
        assertFalse(RecurringTaskPlanner.isValidKind("monthly"))
    }
}
