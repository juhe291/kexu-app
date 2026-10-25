package com.kexu.app

import com.kexu.app.domain.Semester
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportFormatDetector
import com.kexu.app.domain.imports.ImportMapper
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.ImportValidator
import com.kexu.app.domain.imports.IssueSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ImportEngineTest {

    private val semester = Semester(startDate = LocalDate.of(2026, 9, 7), totalWeeks = 20)

    private fun record(
        name: String = "数据结构",
        day: Int? = 0,
        start: Int? = 1,
        end: Int? = 2,
        weeks: Set<Int> = setOf(1, 2, 3),
        teacher: String = "李老师",
        room: String = "A101"
    ) = CourseImportRecord(
        courseName = name,
        teacher = teacher,
        location = room,
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        weeks = weeks,
        source = ImportSource.PDF
    )

    @Test
    fun validRecordHasNoFatalOrConfirmIssues() {
        val issues = ImportValidator.validate(listOf(record()), semester, 12)
        assertTrue(issues.none { it.severity == IssueSeverity.FATAL || it.severity == IssueSeverity.CONFIRM })
    }

    @Test
    fun blankNameIsFatal() {
        val issues = ImportValidator.validate(listOf(record(name = " ")), semester, 12)
        assertTrue(issues.any { it.severity == IssueSeverity.FATAL && it.code == "EMPTY_COURSE_NAME" })
    }

    @Test
    fun invalidDayAndSectionAreFatal() {
        val issues = ImportValidator.validate(listOf(record(day = 8), record(start = 5, end = 3)), semester, 12)
        assertTrue(issues.any { it.code == "INVALID_DAY" })
        assertTrue(issues.any { it.code == "INVALID_SECTION" })
    }

    @Test
    fun missingWeeksNeedsConfirmation() {
        val issues = ImportValidator.validate(listOf(record(weeks = emptySet())), semester, 12)
        assertTrue(issues.any { it.severity == IssueSeverity.CONFIRM && it.code == "NO_WEEKS" })
    }

    @Test
    fun weeksOutsideSemesterIsWarning() {
        val issues = ImportValidator.validate(listOf(record(weeks = setOf(1, 25))), semester, 12)
        assertTrue(issues.any { it.severity == IssueSeverity.WARNING && it.code == "WEEKS_OUT_OF_RANGE" })
    }

    @Test
    fun missingTeacherAndRoomAreWarningsNotFatal() {
        val issues = ImportValidator.validate(listOf(record(teacher = "", room = "")), semester, 12)
        assertTrue(issues.any { it.code == "NO_TEACHER" })
        assertTrue(issues.any { it.code == "NO_ROOM" })
        assertTrue(issues.none { it.severity == IssueSeverity.FATAL })
    }

    @Test
    fun duplicateRecordIsWarning() {
        val r = record()
        val issues = ImportValidator.validate(listOf(r, r), semester, 12)
        assertTrue(issues.any { it.code == "DUPLICATE_RECORD" })
    }

    @Test
    fun overlappingRecordsProduceConflict() {
        val a = record(name = "高数", start = 1, end = 2, weeks = setOf(1, 2, 3))
        val b = record(name = "英语", start = 2, end = 3, weeks = setOf(3, 4))
        val issues = ImportValidator.validate(listOf(a, b), semester, 12)
        assertTrue(issues.any { it.severity == IssueSeverity.WARNING && it.code == "TIME_CONFLICT" })
    }

    @Test
    fun dedupeRemovesExactDuplicate() {
        val r = record()
        assertEquals(1, ImportValidator.dedupe(listOf(r, r)).size)
    }

    @Test
    fun mapperKeepsDifferentRoomsAndMergesSameCourseMeetings() {
        val a = record(room = "A101", day = 0)
        val b = record(room = "A101", day = 2, start = 3, end = 4)
        val c = record(room = "B202", day = 1)
        val courses = ImportMapper.toImportedCourses(listOf(a, b, c))
        assertEquals(2, courses.size)
        val a101 = courses.first { it.room == "A101" }
        assertEquals(2, a101.meetings.size)
        assertEquals("1,2,3", a101.meetings[0].weeksCsv)
        assertTrue(courses.any { it.room == "B202" })
    }

    @Test
    fun formatDetectorUsesFileHeader() {
        assertEquals(ImportSource.PDF, ImportFormatDetector.detect("a.pdf", "%PDF".toByteArray()))
        assertEquals(ImportSource.CSV, ImportFormatDetector.detect("课表.csv", "课程名,星期".toByteArray()))
        assertEquals(ImportSource.ICS, ImportFormatDetector.detect("a.ics", "BEGIN:VCALENDAR".toByteArray()))
        assertEquals(ImportSource.IMAGE, ImportFormatDetector.detect("a.png", byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)))
        assertEquals(null, ImportFormatDetector.detect("a.txt", "hello".toByteArray()))
    }

    @Test
    fun formatDetectorRecognizesXlsxAndHtml() {
        assertEquals(ImportSource.XLSX, ImportFormatDetector.detect("课表.xlsx", byteArrayOf(0x50, 0x4B, 3, 4)))
        assertEquals(ImportSource.HTML, ImportFormatDetector.detect("课表.html", "<table>".toByteArray()))
        assertEquals(ImportSource.HTML, ImportFormatDetector.detect("课表.htm", "<table>".toByteArray()))
    }

    @Test
    fun resultOverallVisibility() {
        val result = com.kexu.app.domain.imports.CourseImportResult(
            source = ImportSource.PDF,
            records = listOf(record()),
            issues = listOf(
                com.kexu.app.domain.imports.ImportIssue(IssueSeverity.FATAL, "X", "bad"),
                com.kexu.app.domain.imports.ImportIssue(IssueSeverity.WARNING, "Y", "warn")
            )
        )
        assertFalse(result.canImport)
        assertEquals(1, result.fatalIssues.size)
        assertEquals(1, result.warnings.size)
    }
}
