package com.kexu.app

import com.kexu.app.data.imports.IcsTimetableImporter
import com.kexu.app.domain.CourseImporter
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Period
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class IcsTimetableImporterTest {

    private val semesterStart = LocalDate.of(2026, 9, 7)
    private val periods = listOf(
        Period("08:00", "08:45"),
        Period("08:55", "09:40"),
        Period("10:00", "10:45"),
        Period("10:55", "11:40")
    )

    private fun ics(vararg lines: String): String = buildString {
        appendLine("BEGIN:VCALENDAR")
        appendLine("BEGIN:VEVENT")
        lines.forEach { appendLine(it) }
        appendLine("END:VEVENT")
        appendLine("END:VCALENDAR")
    }

    private fun hhmm(dateTime: ZonedDateTime): String =
        KexuCore.timeOf(dateTime.hour * 60 + dateTime.minute)

    @Test
    fun `重复事件按 BYDAY 生成多条记录并换算教学周`() {
        val text = ics(
            "SUMMARY:高等数学",
            "LOCATION:A101",
            "DESCRIPTION:教师：张老师",
            "DTSTART:20260907T080000",
            "DTEND:20260907T094000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20261220T000000Z"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(2, result.records.size)
        assertTrue(result.issues.isEmpty())
        val monday = result.records[0]
        assertEquals("高等数学", monday.courseName)
        assertEquals("张老师", monday.teacher)
        assertEquals("A101", monday.location)
        assertEquals(0, monday.dayOfWeek)
        assertEquals(1, monday.startSection)
        assertEquals(2, monday.endSection)
        assertEquals((1..15).toSet(), monday.weeks)
        assertEquals("1-15", monday.originalWeekExpression)
        assertEquals(ImportSource.ICS, monday.source)
        assertEquals(2, result.records[1].dayOfWeek)
        assertEquals((1..15).toSet(), result.records[1].weeks)
    }

    @Test
    fun `普通 RRULE 日历与旧 CourseImporter 的周次节次一致`() {
        val text = ics(
            "SUMMARY:大学物理",
            "LOCATION:B203",
            "DESCRIPTION:教师：李老师",
            "DTSTART:20260908T100000",
            "DTEND:20260908T114000",
            "RRULE:FREQ=WEEKLY;BYDAY=TU,TH;UNTIL=20261220T000000Z"
        )
        val legacy = CourseImporter.parseIcs(text, semesterStart, periods)
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(0, legacy.errors.size)
        assertEquals(1, legacy.courses.size)
        val legacyMeetings = legacy.courses.flatMap { it.meetings }.map {
            listOf(it.weekday, it.startPeriod, it.endPeriod, it.weeksCsv)
        }
        val newMeetings = result.records.map {
            listOf(it.dayOfWeek, it.startSection, it.endSection, it.weeks.sorted().joinToString(","))
        }
        assertEquals(legacyMeetings, newMeetings)
    }

    @Test
    fun `RRULE COUNT 限制教学周数量`() {
        val text = ics(
            "SUMMARY:线性代数",
            "DTSTART:20260909T080000",
            "DTEND:20260909T094000",
            "RRULE:FREQ=WEEKLY;BYDAY=WE;COUNT=4"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        val record = result.records[0]
        assertEquals(2, record.dayOfWeek)
        assertEquals(setOf(1, 2, 3, 4), record.weeks)
        assertEquals(1, record.startSection)
        assertEquals(2, record.endSection)
    }

    @Test
    fun `EXDATE 从教学周中剔除`() {
        val text = ics(
            "SUMMARY:高等数学",
            "DTSTART:20260907T080000",
            "DTEND:20260907T094000",
            "RRULE:FREQ=WEEKLY;BYDAY=MO;UNTIL=20261220T000000Z",
            "EXDATE:20261005T080000,20261012T080000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        val record = result.records[0]
        assertEquals((1..15).toSet() - setOf(5, 6), record.weeks)
        assertEquals("1-4,7-15", record.originalWeekExpression)
    }

    @Test
    fun `无 RRULE 的单次日程取所在教学周`() {
        val text = ics(
            "SUMMARY:讲座",
            "DTSTART:20261007T100000",
            "DTEND:20261007T104500"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        val record = result.records[0]
        assertEquals(setOf(5), record.weeks)
        assertEquals(2, record.dayOfWeek)
        assertEquals(3, record.startSection)
        assertEquals(3, record.endSection)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `学期开始前的单次日程教学周留空并报 CONFIRM`() {
        val text = ics(
            "SUMMARY:补课",
            "DTSTART:20260803T080000",
            "DTEND:20260803T094000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        val record = result.records[0]
        assertTrue(record.weeks.isEmpty())
        assertTrue(record.weeksError != null)
        assertEquals(0, record.dayOfWeek)
        assertEquals(1, record.startSection)
        val issue = result.issues.first { it.code == "ICS_BEFORE_SEMESTER" }
        assertEquals(IssueSeverity.CONFIRM, issue.severity)
        assertTrue(issue.message.contains("补课"))
    }

    @Test
    fun `时间不在节次范围时保留记录但节次留空`() {
        val text = ics(
            "SUMMARY:早课",
            "DTSTART:20260907T073000",
            "DTEND:20260907T081500"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        val record = result.records[0]
        assertEquals("早课", record.courseName)
        assertNull(record.startSection)
        assertNull(record.endSection)
        val issue = result.issues.first { it.code == "ICS_SECTION_OUT_OF_RANGE" }
        assertEquals(IssueSeverity.CONFIRM, issue.severity)
        assertTrue(issue.message.contains("早课"))
        assertTrue(issue.message.contains("07:30"))
    }

    @Test
    fun `只有日期的事件保留记录并报 CONFIRM`() {
        val text = ics(
            "SUMMARY:校运会",
            "DTSTART;VALUE=DATE:20260907",
            "DTEND;VALUE=DATE:20260908"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        val record = result.records[0]
        assertEquals("校运会", record.courseName)
        assertEquals(0, record.dayOfWeek)
        assertNull(record.startSection)
        assertEquals(setOf(1), record.weeks)
        assertTrue(result.issues.any { it.code == "ICS_DATE_ONLY" && it.severity == IssueSeverity.CONFIRM })
    }

    @Test
    fun `UTC 时间换算成本地时间后映射节次`() {
        val zone = ZoneId.systemDefault()
        val localStart = ZonedDateTime.ofInstant(Instant.parse("2026-09-07T00:00:00Z"), zone)
        val localEnd = ZonedDateTime.ofInstant(Instant.parse("2026-09-07T01:40:00Z"), zone)
        val localPeriods = listOf(
            Period(hhmm(localStart), hhmm(localStart.plusMinutes(45))),
            Period(hhmm(localStart.plusMinutes(55)), hhmm(localEnd))
        )
        val text = ics(
            "SUMMARY:晨间课",
            "DTSTART:20260907T000000Z",
            "DTEND:20260907T014000Z"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, localPeriods)
        assertEquals(1, result.records[0].startSection)
        assertEquals(2, result.records[0].endSection)
        assertTrue(result.issues.none { it.code == "ICS_SECTION_OUT_OF_RANGE" })
    }

    @Test
    fun `带 TZID 的时间会换算到本地时区`() {
        val shanghai = ZoneId.of("Asia/Shanghai")
        val localStart = ZonedDateTime.of(2026, 9, 7, 8, 0, 0, 0, shanghai)
            .withZoneSameInstant(ZoneId.systemDefault())
        val localEnd = ZonedDateTime.of(2026, 9, 7, 9, 40, 0, 0, shanghai)
            .withZoneSameInstant(ZoneId.systemDefault())
        val localPeriods = listOf(
            Period(hhmm(localStart), hhmm(localStart.plusMinutes(45))),
            Period(hhmm(localStart.plusMinutes(55)), hhmm(localEnd))
        )
        val text = ics(
            "SUMMARY:编译原理",
            "DTSTART;TZID=Asia/Shanghai:20260907T080000",
            "DTEND;TZID=Asia/Shanghai:20260907T094000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, localPeriods)
        assertEquals(1, result.records[0].startSection)
        assertEquals(2, result.records[0].endSection)
        assertTrue(result.issues.none { it.code == "ICS_UNKNOWN_TZID" })
    }

    @Test
    fun `未知时区按本地墙钟时间处理并给 WARNING`() {
        val text = ics(
            "SUMMARY:体育",
            "DTSTART;TZID=Mars/Olympus:20260907T080000",
            "DTEND;TZID=Mars/Olympus:20260907T094000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records[0].startSection)
        assertEquals(2, result.records[0].endSection)
        val warning = result.issues.first { it.code == "ICS_UNKNOWN_TZID" }
        assertEquals(IssueSeverity.WARNING, warning.severity)
        assertTrue(warning.message.contains("体育"))
        assertTrue(warning.message.contains("Mars/Olympus"))
    }

    @Test
    fun `折叠行被正确拼接`() {
        val text = ics(
            "SUMMARY:高等",
            " 数学",
            "DTSTART:20260907T080000",
            "DTEND:20260907T094000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        assertEquals("高等数学", result.records[0].courseName)
    }

    @Test
    fun `缺少 SUMMARY 保留记录并报 FATAL`() {
        val text = ics(
            "DTSTART:20260907T080000",
            "DTEND:20260907T094000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        assertEquals("", result.records[0].courseName)
        val issue = result.issues.first { it.code == "ICS_MISSING_SUMMARY" }
        assertEquals(IssueSeverity.FATAL, issue.severity)
    }

    @Test
    fun `无法解析的 DTSTART 保留事件并报 FATAL`() {
        val text = ics(
            "SUMMARY:坏事件",
            "DTSTART:not-a-date",
            "DTEND:20260907T094000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        assertEquals("坏事件", result.records[0].courseName)
        assertNull(result.records[0].dayOfWeek)
        val issue = result.issues.first { it.code == "ICS_BAD_DATETIME" }
        assertEquals(IssueSeverity.FATAL, issue.severity)
        assertTrue(issue.message.contains("坏事件"))
    }

    @Test
    fun `空文件返回一条 FATAL`() {
        val result = IcsTimetableImporter.import("   ", semesterStart, periods)
        assertTrue(result.records.isEmpty())
        assertEquals(1, result.issues.size)
        assertEquals(IssueSeverity.FATAL, result.issues[0].severity)
        assertEquals("ICS_EMPTY", result.issues[0].code)
    }

    @Test
    fun `没有 VEVENT 时返回 FATAL`() {
        val result = IcsTimetableImporter.import("BEGIN:VCALENDAR\nEND:VCALENDAR", semesterStart, periods)
        assertTrue(result.records.isEmpty())
        assertTrue(result.issues.any { it.code == "ICS_NO_EVENT" && it.severity == IssueSeverity.FATAL })
    }

    @Test
    fun `缺少 END VEVENT 的事件不丢弃`() {
        val text = "BEGIN:VCALENDAR\nBEGIN:VEVENT\nSUMMARY:未闭合\n" +
            "DTSTART:20260907T080000\nDTEND:20260907T094000\nEND:VCALENDAR"
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        assertEquals("未闭合", result.records[0].courseName)
        assertTrue(result.issues.any { it.code == "ICS_UNCLOSED_EVENT" })
    }

    @Test
    fun `缺少 DTEND 时按单节保留并报 CONFIRM`() {
        val text = ics(
            "SUMMARY:单节",
            "DTSTART:20260907T080000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        assertEquals(1, result.records[0].startSection)
        assertEquals(1, result.records[0].endSection)
        assertEquals(IssueSeverity.CONFIRM, result.issues.first { it.code == "ICS_MISSING_DTEND" }.severity)
    }

    @Test
    fun `非法 BYDAY 回退到开始日期星期并给 WARNING`() {
        val text = ics(
            "SUMMARY:怪规则",
            "DTSTART:20260907T080000",
            "DTEND:20260907T094000",
            "RRULE:FREQ=WEEKLY;BYDAY=XX;UNTIL=20260928T000000Z"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        assertEquals(0, result.records[0].dayOfWeek)
        assertEquals(IssueSeverity.WARNING, result.issues.first { it.code == "ICS_BAD_BYDAY" }.severity)
    }

    @Test
    fun `SUMMARY 与 DESCRIPTION 的转义字符被还原`() {
        val text = ics(
            "SUMMARY:研讨\\,专题",
            "DESCRIPTION:教师：王老师\\n地点：A101",
            "DTSTART:20260907T080000",
            "DTEND:20260907T094000"
        )
        val result = IcsTimetableImporter.import(text, semesterStart, periods)
        assertEquals(1, result.records.size)
        assertEquals("研讨,专题", result.records[0].courseName)
        assertEquals("王老师", result.records[0].teacher)
    }
}
