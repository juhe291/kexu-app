package com.kexu.app

import com.kexu.app.domain.CourseImporter
import com.kexu.app.domain.Period
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CourseImporterTest {

    @Test
    fun csvWithHeaderGroupsSameCourse() {
        val csv = """
            课程名,教师,地点,星期,开始节次,结束节次,周次,单双周
            高等数学,张老师,A101,周一,1,2,1-16,
            高等数学,张老师,A101,周三,3,4,1-16,
            大学英语,李老师,B203,周二,5,6,1-8,
        """.trimIndent()
        val result = CourseImporter.parseCsv(csv)
        assertEquals(0, result.errors.size)
        assertEquals(2, result.courses.size)
        val math = result.courses.first { it.name == "高等数学" }
        assertEquals(2, math.meetings.size)
        assertEquals(0, math.meetings[0].weekday)
        assertEquals(3, math.meetings[1].startPeriod)
        assertTrue(math.meetings[0].weeksCsv!!.startsWith("1,2,3"))
    }

    @Test
    fun csvWithoutHeaderAndCombinedPeriod() {
        val csv = """
            数据结构,王老师,C302,星期三,3-4,1-16,单周
            操作系统,赵老师,D401,周五,7-8,1-16,双周
        """.trimIndent()
        val result = CourseImporter.parseCsv(csv)
        assertEquals(0, result.errors.size)
        val ds = result.courses.first { it.name == "数据结构" }
        assertEquals(2, ds.meetings[0].weekday)
        assertEquals(3, ds.meetings[0].startPeriod)
        assertEquals(4, ds.meetings[0].endPeriod)
        assertEquals("odd", ds.meetings[0].parity)
        val os = result.courses.first { it.name == "操作系统" }
        assertEquals("even", os.meetings[0].parity)
    }

    @Test
    fun csvReportsInvalidRows() {
        val csv = """
            课程名,教师,地点,星期,开始节次,结束节次
            高数,张老师,A101,周八,1,2
            ,李老师,B203,周二,5,6
            线代,王老师,C302,周二,9,12
        """.trimIndent()
        val result = CourseImporter.parseCsv(csv)
        assertEquals(2, result.errors.size)
        assertEquals(1, result.courses.size)
        assertEquals("线代", result.courses[0].name)
    }

    @Test
    fun icsMapsEventsAndWeeks() {
        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VEVENT
            SUMMARY:高等数学
            LOCATION:A101
            DESCRIPTION:教师：张老师
            DTSTART;TZID=Asia/Shanghai:20260907T080000
            DTEND;TZID=Asia/Shanghai:20260907T094000
            RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261220T000000Z
            END:VEVENT
            END:VCALENDAR
        """.trimIndent()
        val periods = listOf(
            Period("08:00", "08:45"),
            Period("08:55", "09:40"),
            Period("10:00", "10:45"),
            Period("10:55", "11:40")
        )
        val result = CourseImporter.parseIcs(ics, LocalDate.of(2026, 9, 7), periods)
        assertEquals(0, result.errors.size)
        assertEquals(1, result.courses.size)
        val course = result.courses[0]
        assertEquals("高等数学", course.name)
        assertEquals(2, course.meetings.size)
        assertEquals(0, course.meetings[0].weekday)
        assertEquals(2, course.meetings[1].weekday)
        assertEquals(1, course.meetings[0].startPeriod)
        assertEquals(2, course.meetings[0].endPeriod)
        assertTrue(course.meetings[0].weeksCsv!!.endsWith(",15"))
    }

    @Test
    fun weekdayAndWeeksParsers() {
        assertEquals(0, CourseImporter.parseWeekday("周一"))
        assertEquals(6, CourseImporter.parseWeekday("Sunday"))
        assertNull(CourseImporter.parseWeekday("周八"))
        val (weeks, parity) = CourseImporter.parseWeeks("1-3,5", "单周")
        assertEquals("1,2,3,5", weeks)
        assertEquals("odd", parity)
    }
}
