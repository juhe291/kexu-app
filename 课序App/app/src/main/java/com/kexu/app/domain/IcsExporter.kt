package com.kexu.app.domain

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** 把课程表导出为 ICS 日历：整学期课程用 RRULE，指定周次/单双周拆成多条事件。 */
object IcsExporter {

    private val stamp = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val dateTime = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    fun export(semester: Semester, periods: List<Period>, courses: List<Course>): String {
        val sb = StringBuilder()
        val now = LocalDateTime.now(ZoneOffset.UTC).format(stamp) + "Z"
        sb.append("BEGIN:VCALENDAR\r\n")
        sb.append("VERSION:2.0\r\n")
        sb.append("PRODID:-//Kexu//Course Timetable//CN\r\n")
        sb.append("CALSCALE:GREGORIAN\r\n")
        sb.append("X-WR-CALNAME:课序课程表\r\n")
        courses.forEach { course ->
            course.meetings.forEach { meeting ->
                val weeks = effectiveWeeks(semester, meeting)
                if (weeks.isEmpty()) return@forEach
                if (meeting.weeksCsv == null && meeting.parity == null) {
                    writeEvent(sb, now, semester, periods, course, meeting, firstWeek = weeks.first(), recurring = true)
                } else {
                    weeks.forEach { week ->
                        writeEvent(sb, now, semester, periods, course, meeting, firstWeek = week, recurring = false)
                    }
                }
            }
        }
        sb.append("END:VCALENDAR\r\n")
        return sb.toString()
    }

    fun effectiveWeeks(semester: Semester, meeting: Meeting): List<Int> {
        val explicit = meeting.weeks
        val parity = meeting.parity
        return (1..semester.totalWeeks).filter { week ->
            val byList = explicit?.contains(week) ?: true
            val byParity = when (parity) {
                "odd" -> week % 2 == 1
                "even" -> week % 2 == 0
                else -> true
            }
            byList && byParity
        }
    }

    private fun writeEvent(
        sb: StringBuilder,
        now: String,
        semester: Semester,
        periods: List<Period>,
        course: Course,
        meeting: Meeting,
        firstWeek: Int,
        recurring: Boolean
    ) {
        val startPeriod = periods.getOrNull(meeting.startPeriod - 1) ?: return
        val endPeriod = periods.getOrNull(meeting.endPeriod - 1) ?: return
        val date = semester.startDate.plusWeeks((firstWeek - 1).toLong()).plusDays(meeting.weekday.toLong())
        val start = date.atTime(startPeriod.fromMin / 60, startPeriod.fromMin % 60)
        val end = date.atTime(endPeriod.toMin / 60, endPeriod.toMin % 60)
        val uid = "${course.id}-$firstWeek-${meeting.weekday}-${meeting.startPeriod}@kexu"
        sb.append("BEGIN:VEVENT\r\n")
        sb.append("UID:$uid\r\n")
        sb.append("DTSTAMP:$now\r\n")
        sb.append("DTSTART:${start.format(dateTime)}\r\n")
        sb.append("DTEND:${end.format(dateTime)}\r\n")
        sb.append("SUMMARY:${escape(course.name)}\r\n")
        val location = meeting.room ?: course.room
        if (location.isNotBlank()) sb.append("LOCATION:${escape(location)}\r\n")
        if (course.teacher.isNotBlank()) sb.append("DESCRIPTION:${escape("教师：" + course.teacher)}\r\n")
        if (recurring) {
            val count = semester.totalWeeks - firstWeek + 1
            sb.append("RRULE:FREQ=WEEKLY;COUNT=$count;BYDAY=${icsWeekday(meeting.weekday)}\r\n")
        }
        sb.append("END:VEVENT\r\n")
    }

    private fun icsWeekday(weekday: Int): String =
        listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU").getOrElse(weekday) { "MO" }

    private fun escape(text: String): String = text
        .replace("\\", "\\\\")
        .replace(";", "\\;")
        .replace(",", "\\,")
        .replace("\n", "\\n")
}
