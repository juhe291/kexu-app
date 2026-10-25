package com.kexu.app

import com.kexu.app.data.imports.JsonTimetableImporter
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外部 AI / 其他学校模板的 JSON 导入（kexu-timetable/v1）。
 * org.json 在 Android 单元测试里由 Robolectric 提供。
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33])
class JsonTimetableImporterTest {

    private val validJson = """
        {
          "schema": "kexu-timetable/v1",
          "weekdayNote": "1=周一",
          "courses": [
            {
              "name": "高等数学",
              "teacher": "张老师",
              "room": "公教2-509",
              "meetings": [
                { "weekday": 1, "startPeriod": 1, "endPeriod": 2, "weeks": "1-16" },
                { "weekday": 3, "startPeriod": 3, "endPeriod": 4, "weeks": "1-5,7-11" }
              ]
            },
            {
              "name": "篮球初级班",
              "teacher": "王老师",
              "room": "田径场",
              "meetings": [
                { "weekday": 5, "startPeriod": 5, "endPeriod": 6, "weeks": "5-7周(单),8-16周" }
              ]
            }
          ]
        }
    """.trimIndent()

    @Test
    fun parsesCoursesAndMeetings() {
        val result = JsonTimetableImporter.import(validJson, 12)
        assertEquals(3, result.records.size)
        val mathFirst = result.records[0]
        assertEquals("高等数学", mathFirst.courseName)
        assertEquals("张老师", mathFirst.teacher)
        assertEquals(0, mathFirst.dayOfWeek)
        assertEquals(1, mathFirst.startSection)
        assertEquals(2, mathFirst.endSection)
        assertEquals((1..16).toSet(), mathFirst.weeks)
        assertEquals(ImportSource.JSON, mathFirst.source)
        val mathSecond = result.records[1]
        assertEquals(2, mathSecond.dayOfWeek)
        assertEquals((1..5).toSet() + (7..11).toSet(), mathSecond.weeks)
        val basketball = result.records[2]
        assertEquals(4, basketball.dayOfWeek)
        assertEquals(5, basketball.startSection)
        assertTrue(basketball.weeks.containsAll(setOf(5, 7)))
        assertTrue(result.issues.none { it.severity == IssueSeverity.FATAL })
    }

    @Test
    fun extractsJsonFromMarkdownCodeFence() {
        val wrapped = """
            好的，下面是可以导入的文件：
            ```json
            $validJson
            ```
            如果还有问题请告诉我。
        """.trimIndent()
        val result = JsonTimetableImporter.import(wrapped, 12)
        assertEquals(3, result.records.size)
    }

    @Test
    fun missingObjectIsFatal() {
        val result = JsonTimetableImporter.import("抱歉我无法生成", 12)
        assertTrue(result.records.isEmpty())
        assertTrue(result.issues.any { it.code == "JSON_NO_OBJECT" && it.severity == IssueSeverity.FATAL })
    }

    @Test
    fun brokenJsonIsFatal() {
        val result = JsonTimetableImporter.import("{ \"schema\": \"kexu-timetable/v1\", ", 12)
        assertTrue(result.records.isEmpty())
        assertTrue(result.issues.any { it.severity == IssueSeverity.FATAL })
    }

    @Test
    fun missingCoursesIsFatal() {
        val result = JsonTimetableImporter.import("{ \"schema\": \"kexu-timetable/v1\" }", 12)
        assertTrue(result.issues.any { it.code == "JSON_NO_COURSES" })
    }

    @Test
    fun badWeekdayIsReportedButRecordKept() {
        val json = """{ "courses": [ { "name": "体育", "meetings": [ { "weekday": 9, "startPeriod": 1, "endPeriod": 2, "weeks": "1-4" } ] } ] }"""
        val result = JsonTimetableImporter.import(json, 12)
        assertEquals(1, result.records.size)
        assertEquals(null, result.records[0].dayOfWeek)
        assertTrue(result.issues.any { it.code == "JSON_BAD_WEEKDAY" })
    }

    @Test
    fun badSectionIsReportedAndNotInvented() {
        val json = """{ "courses": [ { "name": "体育", "meetings": [ { "weekday": 2, "startPeriod": 3, "endPeriod": 99, "weeks": "1-4" } ] } ] }"""
        val result = JsonTimetableImporter.import(json, 12)
        assertEquals(1, result.records.size)
        assertEquals(1, result.records[0].dayOfWeek)
        assertEquals(null, result.records[0].startSection)
        assertTrue(result.issues.any { it.code == "JSON_BAD_SECTION" && it.severity == IssueSeverity.FATAL })
    }

    @Test
    fun missingWeeksNeedsConfirmation() {
        val json = """{ "courses": [ { "name": "体育", "meetings": [ { "weekday": 2, "startPeriod": 1, "endPeriod": 2 } ] } ] }"""
        val result = JsonTimetableImporter.import(json, 12)
        assertTrue(result.issues.any { it.code == "JSON_NO_WEEKS" && it.severity == IssueSeverity.CONFIRM })
        assertTrue(result.records[0].weeks.isEmpty())
    }

    @Test
    fun parityColumnIsFoldedIntoWeeks() {
        val json = """{ "courses": [ { "name": "篮球", "meetings": [ { "weekday": 5, "startPeriod": 1, "endPeriod": 2, "weeks": "1-8", "weekType": "单" } ] } ] }"""
        val result = JsonTimetableImporter.import(json, 12)
        assertEquals(setOf(1, 3, 5, 7), result.records[0].weeks)
        assertEquals("1-8(单)", result.records[0].originalWeekExpression)
    }

    @Test
    fun flatCourseWithoutMeetingsArrayIsSupported() {
        val json = """{ "courses": [ { "name": "形势与政策", "teacher": "王老师", "room": "A101", "weekday": 3, "startPeriod": 5, "endPeriod": 6, "weeks": "3-8" } ] }"""
        val result = JsonTimetableImporter.import(json, 12)
        assertEquals(1, result.records.size)
        assertEquals(2, result.records[0].dayOfWeek)
        assertEquals((3..8).toSet(), result.records[0].weeks)
    }

    @Test
    fun zeroWeekdayIsTreatedAsMondayWithWarning() {
        val json = """{ "courses": [ { "name": "高数", "meetings": [ { "weekday": 0, "startPeriod": 1, "endPeriod": 2, "weeks": "1-4" } ] } ] }"""
        val result = JsonTimetableImporter.import(json, 12)
        assertEquals(0, result.records[0].dayOfWeek)
        assertTrue(result.issues.any { it.code == "JSON_WEEKDAY_ZERO" })
    }

    @Test
    fun exportTemplateCanBeImportedBack() {
        val template = com.kexu.app.domain.imports.ImportTemplates.jsonTemplate()
        val result = JsonTimetableImporter.import(template, 12)
        assertTrue(result.records.isNotEmpty())
        assertTrue(result.records.any { it.courseName == "高等数学" })
        assertTrue(result.records.any { it.courseName == "篮球初级班" })
    }

    @Test
    fun formatDetectorRecognizesJsonTemplate() {
        assertEquals(
            ImportSource.JSON,
            com.kexu.app.domain.imports.ImportFormatDetector.detect("课序导入模板.json", "{\"schema\"".toByteArray())
        )
        assertEquals(
            ImportSource.JSON,
            com.kexu.app.domain.imports.ImportFormatDetector.detect("课表.JSON", "{}".toByteArray())
        )
    }
}
