package com.kexu.app

import com.kexu.app.domain.Course
import com.kexu.app.domain.IcsExporter
import com.kexu.app.domain.Meeting
import com.kexu.app.domain.Period
import com.kexu.app.domain.Semester
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IcsExporterTest {

    private val periods = listOf(
        Period("08:00", "08:45"),
        Period("08:55", "09:40")
    )

    @Test
    fun exportsRecurringCourse() {
        val course = Course(
            id = "c1",
            name = "高等数学, 上册",
            teacher = "张老师",
            room = "A101",
            colorHex = "#2563EB",
            meetings = listOf(Meeting(weekday = 0, startPeriod = 1, endPeriod = 2))
        )
        val ics = IcsExporter.export(Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 16), periods, listOf(course))
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("SUMMARY:高等数学\\, 上册"))
        assertTrue(ics.contains("DTSTART:20260907T080000"))
        assertTrue(ics.contains("DTEND:20260907T094000"))
        assertTrue(ics.contains("RRULE:FREQ=WEEKLY;COUNT=16;BYDAY=MO"))
        assertTrue(ics.endsWith("END:VCALENDAR\r\n"))
    }

    @Test
    fun splitsExplicitWeeksAndParity() {
        val course = Course(
            id = "c2",
            name = "大学英语",
            colorHex = "#0D9488",
            meetings = listOf(Meeting(weekday = 2, startPeriod = 1, endPeriod = 1, weeksCsv = "1,3,5"))
        )
        val ics = IcsExporter.export(Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 16), periods, listOf(course))
        assertEquals(3, Regex("BEGIN:VEVENT").findAll(ics).count())
        assertTrue(!ics.contains("RRULE:FREQ=WEEKLY"))
    }

    @Test
    fun effectiveWeeksRespectParity() {
        val meeting = Meeting(weekday = 0, startPeriod = 1, endPeriod = 2, parity = "odd")
        val weeks = IcsExporter.effectiveWeeks(Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 8), meeting)
        assertEquals(listOf(1, 3, 5, 7), weeks)
    }
}
