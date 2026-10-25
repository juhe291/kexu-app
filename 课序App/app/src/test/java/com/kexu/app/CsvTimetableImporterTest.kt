package com.kexu.app

import com.kexu.app.data.imports.CsvTimetableImporter
import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CsvTimetableImporterTest {

    private val maxPeriods = 12

    private fun parse(csv: String) = CsvTimetableImporter.import(csv, maxPeriods)

    @Test
    fun `带表头 CSV 逐行映射字段`() {
        val csv = """
            课程名,教师,地点,星期,开始节次,结束节次,周次,单双周
            高等数学,张老师,A101,周一,1,2,1-16,
            大学英语,李老师,B203,周二,5,6,1-8,
        """.trimIndent()
        val result = parse(csv)
        assertEquals(2, result.records.size)
        assertTrue(result.issues.isEmpty())
        val math = result.records[0]
        assertEquals("高等数学", math.courseName)
        assertEquals("张老师", math.teacher)
        assertEquals("A101", math.location)
        assertEquals(0, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals((1..16).toSet(), math.weeks)
        assertEquals("1-16", math.originalWeekExpression)
        assertEquals(ImportSource.CSV, math.source)
        val english = result.records[1]
        assertEquals("大学英语", english.courseName)
        assertEquals(1, english.dayOfWeek)
        assertEquals(5, english.startSection)
        assertEquals(6, english.endSection)
        assertEquals((1..8).toSet(), english.weeks)
    }

    @Test
    fun `无表头 CSV 支持合并节次与单双周`() {
        val csv = """
            数据结构,王老师,C302,星期三,3-4,1-16,单周
            操作系统,赵老师,D401,周五,7-8,1-16,双周
        """.trimIndent()
        val result = parse(csv)
        assertEquals(2, result.records.size)
        assertTrue(result.issues.isEmpty())
        val dataStructure = result.records[0]
        assertEquals(2, dataStructure.dayOfWeek)
        assertEquals(3, dataStructure.startSection)
        assertEquals(4, dataStructure.endSection)
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), dataStructure.weeks)
        val expression = dataStructure.originalWeekExpression!!
        assertTrue(expression.contains("单周"))
        assertEquals(dataStructure.weeks, WeekRuleParser.parse(expression).weeks)
        val operatingSystem = result.records[1]
        assertEquals(4, operatingSystem.dayOfWeek)
        assertEquals(7, operatingSystem.startSection)
        assertEquals(8, operatingSystem.endSection)
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), operatingSystem.weeks)
    }

    @Test
    fun `同一门课多次上课生成多条记录不合并`() {
        val csv = """
            课程名,教师,地点,星期,开始节次,结束节次,周次
            高等数学,张老师,A101,周一,1,2,1-16
            高等数学,张老师,A101,周三,3,4,1-16
        """.trimIndent()
        val result = parse(csv)
        assertEquals(2, result.records.size)
        assertEquals(2, result.records.count { it.courseName == "高等数学" })
        assertEquals(listOf(0, 2), result.records.map { it.dayOfWeek })
        assertEquals(listOf(1, 3), result.records.map { it.startSection })
    }

    @Test
    fun `引号内逗号不被拆列`() {
        val csv = "\"高等数学,上\",张老师,\"A,101\",周一,1-2,1-16"
        val result = parse(csv)
        assertEquals(1, result.records.size)
        val record = result.records[0]
        assertEquals("高等数学,上", record.courseName)
        assertEquals("A,101", record.location)
        assertEquals(1, record.startSection)
        assertEquals(2, record.endSection)
        assertEquals((1..16).toSet(), record.weeks)
    }

    @Test
    fun `缺少课程名保留行并报 FATAL`() {
        val csv = """
            课程名,教师,地点,星期,开始节次,结束节次
            高等数学,张老师,A101,周一,1,2
            ,李老师,B203,周二,5,6
        """.trimIndent()
        val result = parse(csv)
        assertEquals(2, result.records.size)
        assertEquals("", result.records[1].courseName)
        assertEquals("李老师", result.records[1].teacher)
        val issue = result.issues.first { it.code == "CSV_MISSING_NAME" }
        assertEquals(IssueSeverity.FATAL, issue.severity)
        assertTrue(issue.message.contains("第 3 行"))
        assertEquals(1, issue.recordIndex)
    }

    @Test
    fun `星期无法识别保留行并报 FATAL`() {
        val csv = "高数,张老师,A101,周八,1,2"
        val result = parse(csv)
        assertEquals(1, result.records.size)
        assertNull(result.records[0].dayOfWeek)
        assertEquals("高数", result.records[0].courseName)
        val issue = result.issues.first { it.code == "CSV_BAD_WEEKDAY" }
        assertEquals(IssueSeverity.FATAL, issue.severity)
        assertTrue(issue.message.contains("第 1 行"))
        assertTrue(issue.message.contains("周八"))
    }

    @Test
    fun `节次非法保留行并报 FATAL`() {
        val csv = "线代,王老师,C302,周二,9,3"
        val result = parse(csv)
        assertEquals(1, result.records.size)
        assertNull(result.records[0].startSection)
        assertNull(result.records[0].endSection)
        val issue = result.issues.first { it.code == "CSV_BAD_SECTION" }
        assertEquals(IssueSeverity.FATAL, issue.severity)
        assertTrue(issue.message.contains("第 1 行"))
    }

    @Test
    fun `节次超出 maxPeriods 视为非法`() {
        val csv = "线代,王老师,C302,周二,13,14"
        val result = parse(csv)
        assertNull(result.records[0].startSection)
        assertNull(result.records[0].endSection)
        assertEquals(IssueSeverity.FATAL, result.issues.first { it.code == "CSV_BAD_SECTION" }.severity)
    }

    @Test
    fun `空文件返回一条 FATAL`() {
        val result = parse("")
        assertTrue(result.records.isEmpty())
        assertEquals(1, result.issues.size)
        assertEquals(IssueSeverity.FATAL, result.issues[0].severity)
        assertEquals("CSV_EMPTY", result.issues[0].code)
    }

    @Test
    fun `只有表头时返回 FATAL 且没有记录`() {
        val result = parse("课程名,教师,地点,星期,开始节次,结束节次")
        assertTrue(result.records.isEmpty())
        assertTrue(result.issues.any { it.code == "CSV_NO_RECORD" && it.severity == IssueSeverity.FATAL })
    }

    @Test
    fun `周次无法解析时不编造周次并报 CONFIRM`() {
        val csv = "高数,张老师,A101,周一,1,2,随时"
        val result = parse(csv)
        val record = result.records[0]
        assertTrue(record.weeks.isEmpty())
        assertEquals("随时", record.originalWeekExpression)
        assertTrue(record.weeksError != null)
        val issue = result.issues.first { it.code == "CSV_BAD_WEEKS" }
        assertEquals(IssueSeverity.CONFIRM, issue.severity)
        assertTrue(issue.message.contains("第 1 行"))
    }

    @Test
    fun `只有单双周缺少范围时报问题`() {
        val csv = "高数,张老师,A101,周一,1,2,,单周"
        val result = parse(csv)
        val record = result.records[0]
        assertTrue(record.weeks.isEmpty())
        assertEquals("单周", record.originalWeekExpression)
        assertEquals(IssueSeverity.CONFIRM, result.issues.first { it.code == "CSV_BAD_WEEKS" }.severity)
    }

    @Test
    fun `星期支持中文别名数字与英文`() {
        val cases = listOf(
            "周一" to 0, "星期一" to 0, "礼拜一" to 0, "1" to 0, "mon" to 0, "MONDAY" to 0,
            "星期二" to 1, "2" to 1, "tue" to 1,
            "周三" to 2, "3" to 2, "wed" to 2,
            "周四" to 3, "4" to 3, "thu" to 3,
            "周五" to 4, "5" to 4, "fri" to 4,
            "周六" to 5, "6" to 5, "sat" to 5,
            "周日" to 6, "周天" to 6, "7" to 6, "sunday" to 6
        )
        cases.forEach { (raw, expected) ->
            val result = parse("课,师,室,$raw,1,2")
            assertEquals("星期 $raw", expected, result.records[0].dayOfWeek)
        }
    }

    @Test
    fun `CRLF 换行与 BOM 不影响解析`() {
        val csv = "\uFEFF课程名,教师,地点,星期,开始节次,结束节次\r\n高等数学,张老师,A101,周一,1,2\r\n"
        val result = parse(csv)
        assertEquals(1, result.records.size)
        assertEquals("高等数学", result.records[0].courseName)
        assertEquals(0, result.records[0].dayOfWeek)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `缺少周次列不静默丢行也不误报周次错误`() {
        val csv = """
            课程名,教师,地点,星期,开始节次,结束节次
            高等数学,张老师,A101,周一,1,2
        """.trimIndent()
        val result = parse(csv)
        assertEquals(1, result.records.size)
        assertTrue(result.records[0].weeks.isEmpty())
        assertNull(result.records[0].originalWeekExpression)
        assertTrue(result.issues.none { it.code == "CSV_BAD_WEEKS" })
    }

    @Test
    fun `占位空行不产生记录`() {
        val csv = "高数,张老师,A101,周一,1,2\n,,,,\n英语,李老师,B203,周二,3,4"
        val result = parse(csv)
        assertEquals(2, result.records.size)
        assertEquals(listOf("高数", "英语"), result.records.map { it.courseName })
    }
}
