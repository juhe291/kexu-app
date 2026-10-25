package com.kexu.app

import com.kexu.app.domain.Course
import com.kexu.app.domain.CourseException
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Meeting
import com.kexu.app.domain.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** 课程单次例外（停课 / 调课）的纯逻辑测试。 */
class CourseExceptionTest {

    private val semester = Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 20)
    private val periods = KexuCore.defaultPeriods()

    private val course = Course(
        id = "c1", name = "数据结构", teacher = "张老师", room = "A101", colorHex = "#2563EB",
        meetings = listOf(
            Meeting(weekday = 0, startPeriod = 1, endPeriod = 2),
            Meeting(weekday = 2, startPeriod = 3, endPeriod = 4)
        )
    )
    private val courses = listOf(course)

    private fun cancel(week: Int, weekday: Int = 0, start: Int = 1, end: Int = 2) = CourseException(
        id = "e-$week-$weekday", courseId = "c1", week = week, weekday = weekday,
        startPeriod = start, endPeriod = end, kind = CourseException.KIND_CANCEL
    )

    @Test
    fun `停课的一次课从原时段消失但保留为可撤销的幽灵块`() {
        val exceptions = listOf(cancel(week = 8))
        val monday = KexuCore.effectiveCoursesOnDay(courses, exceptions, 8, 0)
        assertEquals(1, monday.size)
        assertTrue(monday[0].cancelled)
        assertEquals("e-8-0", monday[0].exceptionId)
        // 其他星期不受影响
        assertEquals(1, KexuCore.effectiveCoursesOnDay(courses, exceptions, 8, 2).size)
        // 其他教学周不受影响
        val nextWeek = KexuCore.effectiveCoursesOnDay(courses, exceptions, 9, 0)
        assertEquals(1, nextWeek.size)
        assertTrue(!nextWeek[0].cancelled)
    }

    @Test
    fun `调课把这一次课移动到新的星期节次和教室`() {
        val move = CourseException(
            id = "m1", courseId = "c1", week = 8, weekday = 0, startPeriod = 1, endPeriod = 2,
            kind = CourseException.KIND_MOVE, newWeekday = 3, newStartPeriod = 5, newEndPeriod = 6, newRoom = "B202",
            note = "临时调课"
        )
        val exceptions = listOf(move)
        // 原时间不再出现
        assertEquals(0, KexuCore.effectiveCoursesOnDay(courses, exceptions, 8, 0).size)
        // 新时间出现，且标记为调课
        val thursday = KexuCore.effectiveCoursesOnDay(courses, exceptions, 8, 3)
        assertEquals(1, thursday.size)
        val occurrence = thursday[0]
        assertTrue(occurrence.moved)
        assertEquals(5, occurrence.startPeriod)
        assertEquals(6, occurrence.endPeriod)
        assertEquals("B202", occurrence.room)
        assertEquals("m1", occurrence.exceptionId)
        assertEquals("临时调课", occurrence.note)
        // 其他周仍是原时间
        assertEquals(1, KexuCore.effectiveCoursesOnDay(courses, exceptions, 9, 0).size)
    }

    @Test
    fun `只改教室的调课仍出现在原星期`() {
        val roomChange = CourseException(
            id = "r1", courseId = "c1", week = 8, weekday = 0, startPeriod = 1, endPeriod = 2,
            kind = CourseException.KIND_MOVE, newWeekday = 0, newStartPeriod = 1, newEndPeriod = 2, newRoom = "C303"
        )
        val monday = KexuCore.effectiveCoursesOnDay(courses, listOf(roomChange), 8, 0)
        assertEquals(1, monday.size)
        assertTrue(monday[0].moved)
        assertEquals("C303", monday[0].room)
    }

    @Test
    fun `下一节课跳过已停课并考虑调课`() {
        // 2026-10-26 是第 8 周周一，7:40 时下一节应是 8:30 的数据结构
        val exceptions = listOf(
            CourseException(
                id = "m2", courseId = "c1", week = 8, weekday = 0, startPeriod = 1, endPeriod = 2,
                kind = CourseException.KIND_MOVE, newWeekday = 1, newStartPeriod = 3, newEndPeriod = 4, newRoom = "D404"
            )
        )
        val mondayNext = KexuCore.nextCourse(
            LocalDateTime.of(2026, 10, 26, 7, 40), semester, courses, periods, exceptions
        )
        // 周一被调走，剩下的下一节是周三第 3-4 节（周二那门课只出现在第 8 周之后？这里是常驻课，
        // 因此周一调课后，周二原本没有课 → 下一节应是周二被调过去的第 3-4 节）
        assertEquals(1, mondayNext!!.weekday)
        assertEquals(3, mondayNext.startPeriod)
        assertEquals("D404", mondayNext.room)
        assertTrue(mondayNext.moved)
    }

    @Test
    fun `停课不影响其他课程`() {
        val other = Course(
            id = "c2", name = "大学英语", colorHex = "#0D9488",
            meetings = listOf(Meeting(weekday = 0, startPeriod = 3, endPeriod = 4))
        )
        val exceptions = listOf(cancel(week = 8))
        val monday = KexuCore.effectiveCoursesOnDay(listOf(course, other), exceptions, 8, 0)
        assertEquals(2, monday.size)
        assertTrue(monday.any { it.course.id == "c2" && !it.cancelled })
        assertTrue(monday.any { it.course.id == "c1" && it.cancelled })
    }

    @Test
    fun `exceptionFor能找到对应例外`() {
        val move = CourseException(
            id = "m3", courseId = "c1", week = 8, weekday = 0, startPeriod = 1, endPeriod = 2,
            kind = CourseException.KIND_MOVE, newWeekday = 3, newStartPeriod = 5, newEndPeriod = 6
        )
        val meeting = course.meetings.first()
        assertEquals("m3", KexuCore.exceptionFor(listOf(move), "c1", 8, meeting)?.id)
        assertNull(KexuCore.exceptionFor(listOf(move), "c1", 9, meeting))
        assertNull(KexuCore.exceptionFor(listOf(move), "c2", 8, meeting))
    }

    @Test
    fun `孤儿调课例外不会凭空生成课程`() {
        val orphan = CourseException(
            id = "x", courseId = "c1", week = 8, weekday = 5, startPeriod = 1, endPeriod = 2,
            kind = CourseException.KIND_MOVE, newWeekday = 3, newStartPeriod = 5, newEndPeriod = 6
        )
        // 原安排里周五没有这门课 → 目标位置不能凭空出现调课记录
        assertEquals(0, KexuCore.effectiveCoursesOnDay(courses, listOf(orphan), 8, 3).size)
        // 原来正常的周一/周三安排也不受这个孤儿例外影响
        assertEquals(1, KexuCore.effectiveCoursesOnDay(courses, listOf(orphan), 8, 0).size)
        assertEquals(1, KexuCore.effectiveCoursesOnDay(courses, listOf(orphan), 8, 2).size)
    }

    @Test
    fun `整周视图按星期分组`() {
        val exceptions = listOf(cancel(week = 8))
        val week = KexuCore.effectiveCoursesOnWeek(courses, exceptions, 8)
        assertEquals(7, week.size)
        assertTrue(week[0].any { it.cancelled })
        assertEquals(1, week[2].size)
    }
}
