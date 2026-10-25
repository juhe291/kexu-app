package com.kexu.app

import com.kexu.app.domain.Course
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Meeting
import com.kexu.app.domain.Period
import com.kexu.app.domain.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class KexuCoreTest {

    private val semester = Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 20)
    private val periods = KexuCore.defaultPeriods()

    private val courses = listOf(
        Course(
            id = "a", name = "测试课程", colorHex = "#2563EB",
            meetings = listOf(
                Meeting(weekday = 0, startPeriod = 1, endPeriod = 2),
                Meeting(weekday = 2, startPeriod = 3, endPeriod = 4, weeksCsv = "1,2,3,5,8"),
                Meeting(weekday = 4, startPeriod = 5, endPeriod = 6, parity = "odd")
            )
        )
    )

    @Test
    fun `第8周周一对应2026-10-26`() {
        assertEquals(LocalDate.of(2026, 10, 26), KexuCore.dateForWeekDay(LocalDate.of(2026, 9, 7), 8, 0))
    }

    @Test
    fun `第9周周日跨月`() {
        assertEquals(LocalDate.of(2026, 11, 8), KexuCore.dateForWeekDay(LocalDate.of(2026, 9, 7), 9, 6))
    }

    @Test
    fun `教学周反查`() {
        assertEquals(8, KexuCore.weekIndexForDate(semester.startDate, LocalDate.of(2026, 10, 26)))
        assertEquals(1, KexuCore.weekIndexForDate(semester.startDate, LocalDate.of(2026, 9, 7)))
        assertEquals(7, KexuCore.weekIndexForDate(semester.startDate, LocalDate.of(2026, 10, 25)))
    }

    @Test
    fun `跨月周范围含两个月份`() {
        val label = KexuCore.weekRangeLabel(KexuCore.dateForWeekDay(semester.startDate, 8, 0))
        assertTrue(label, label.contains("10 月 26 日"))
        assertTrue(label, label.contains("11 月 1 日"))
    }

    @Test
    fun `跨年周范围包含两个年份`() {
        val label = KexuCore.weekRangeLabel(KexuCore.dateForWeekDay(semester.startDate, 17, 0))
        assertTrue(label, label.contains("2026"))
        assertTrue(label, label.contains("2027"))
    }

    @Test
    fun `跨月月份标签`() {
        val label = KexuCore.weekMonthLabel(KexuCore.dateForWeekDay(semester.startDate, 8, 0))
        assertTrue(label, label.contains("10 月"))
        assertTrue(label, label.contains("11 月"))
    }

    @Test
    fun `单双周判定`() {
        assertTrue(KexuCore.parityMatch("odd", 9))
        assertFalse(KexuCore.parityMatch("odd", 8))
        assertTrue(KexuCore.parityMatch("even", 8))
        assertTrue(KexuCore.parityMatch(null, 7))
    }

    @Test
    fun `非连续周次`() {
        assertTrue(KexuCore.coursesOnDay(courses, 4, 2).isEmpty())
        assertEquals(1, KexuCore.coursesOnDay(courses, 8, 2).size)
    }

    @Test
    fun `单双周课程出现规则`() {
        assertTrue(KexuCore.coursesOnDay(courses, 8, 4).isEmpty())
        assertEquals(1, KexuCore.coursesOnDay(courses, 9, 4).size)
    }

    @Test
    fun `节次布局首节从零开始`() {
        val layout = KexuCore.periodLayout(periods)
        assertEquals(0f, layout.rows[0].topPx, 0.01f)
        assertTrue(layout.rows[4].topPx > layout.rows[3].topPx + layout.rows[3].heightPx)
    }

    @Test
    fun `午休空档被压缩且标签正确`() {
        val layout = KexuCore.periodLayout(periods)
        val lunch = layout.rows[4]
        assertEquals(130, lunch.gapMin)
        assertTrue(lunch.gapPx <= 30f)
        assertEquals("午休", lunch.gapLabel)
        assertEquals("晚餐", layout.rows[8].gapLabel)
    }

    @Test
    fun `跨节课程高度按节次计算`() {
        val layout = KexuCore.periodLayout(periods)
        val (top, height) = KexuCore.meetingRect(periods, 3, 4)
        assertEquals(layout.rows[2].topPx, top, 0.01f)
        val expected = layout.rows[3].topPx + layout.rows[3].heightPx - layout.rows[2].topPx
        assertEquals(expected, height, 0.01f)
    }

    @Test
    fun `自定义节次后重新布局`() {
        val custom = listOf(Period("08:00", "08:40"), Period("09:00", "09:40"), Period("10:00", "10:40"), Period("11:00", "11:40"))
        val layout = KexuCore.periodLayout(custom)
        assertEquals(40f * KexuCore.PX_PER_MIN, layout.rows[1].heightPx, 0.01f)
        assertEquals(layout.rows[0].heightPx + layout.rows[1].gapPx, layout.rows[1].topPx, 0.01f)
    }

    @Test
    fun `时间轴校验能发现重叠和非法时间`() {
        assertTrue(KexuCore.validatePeriods(periods).isEmpty())
        assertFalse(KexuCore.validatePeriods(listOf(
            Period("08:00", "09:00"), Period("08:50", "09:50"),
            Period("10:00", "10:45"), Period("11:00", "11:45")
        )).isEmpty())
        assertFalse(KexuCore.validatePeriods(listOf(
            Period("09:00", "08:00"), Period("10:00", "10:45"),
            Period("11:00", "11:45"), Period("12:00", "12:45")
        )).isEmpty())
    }

    @Test
    fun `半开区间冲突判断`() {
        assertFalse(KexuCore.overlaps(510, 555, 555, 600))
        assertTrue(KexuCore.overlaps(510, 556, 555, 600))
    }

    @Test
    fun `下一节课搜索`() {
        val next = KexuCore.nextCourse(
            LocalDateTime.of(2026, 10, 26, 7, 40),
            semester, courses, periods
        )
        assertNotNull(next)
        assertEquals("a", next!!.course.id)
        assertEquals(510, next.startMin)
        assertEquals(50, next.startsInMin)
    }

    @Test
    fun `当天课后顺延到下一个有课日`() {
        val next = KexuCore.nextCourse(LocalDateTime.of(2026, 10, 26, 23, 0), semester, courses, periods)
        assertNotNull(next)
        assertEquals(2, next!!.weekday)
    }

    @Test
    fun `课程色板循环分配`() {
        assertTrue(KexuCore.defaultColor(0) != KexuCore.defaultColor(1))
        assertEquals(KexuCore.defaultColor(0), KexuCore.defaultColor(KexuCore.PALETTE.size))
    }

    @Test
    fun `浅色与深色课程色块对比度达4点5`() {
        listOf(true, false).forEach { dark ->
            val tone = KexuCore.toneFor("#2563EB", dark)
            assertTrue(KexuCore.contrastRatio(tone.bg, tone.ink) >= 4.5)
        }
    }

    @Test
    fun `可读文字色自动反转`() {
        assertEquals("#0D1930", KexuCore.readableInk("#EEF2F8"))
        assertEquals("#EAF1FB", KexuCore.readableInk("#1B2836"))
    }

    @Test
    fun `时长与倒计时格式化`() {
        assertEquals("1 小时 30 分钟", KexuCore.formatDuration(90))
        assertEquals("45 分钟", KexuCore.formatDuration(45))
        assertEquals("50 分钟后", KexuCore.countdownText(50L * 60 * 1000))
    }
}
