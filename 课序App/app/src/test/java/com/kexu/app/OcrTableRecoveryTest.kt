package com.kexu.app

import com.kexu.app.domain.Period
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.ocr.OcrCourseBlock
import com.kexu.app.ocr.OcrCourseRecordParser
import com.kexu.app.ocr.OcrLine
import com.kexu.app.ocr.OcrPage
import com.kexu.app.ocr.OcrPageStructure
import com.kexu.app.ocr.OcrPeriodAxis
import com.kexu.app.ocr.OcrTableRecovery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图片/扫描版 PDF 的本地 OCR 版面恢复与课程语义解析测试。
 * 全部用合成 OCR 行（像素坐标）驱动纯 Kotlin 逻辑，不依赖 Android / ML Kit。
 */
class OcrTableRecoveryTest {

    private val periods = listOf(
        Period("08:00", "08:45"), Period("08:55", "09:40"),
        Period("10:00", "10:45"), Period("10:55", "11:40"),
        Period("14:00", "14:45"), Period("14:55", "15:40"),
        Period("16:00", "16:45"), Period("16:55", "17:40"),
        Period("19:00", "19:45"), Period("19:55", "20:40"),
        Period("20:50", "21:35"), Period("21:45", "22:30")
    )
    private val axis = OcrPeriodAxis(periods)

    private fun line(text: String, left: Int, top: Int, width: Int = 70, height: Int = 30): OcrLine =
        OcrLine(text, left, top, left + width, top + height)

    private fun centered(text: String, centerX: Int, top: Int, width: Int = 70): OcrLine =
        line(text, centerX - width / 2, top, width, 30)

    /** 标准 CUG 风格截图：七个星期表头 + 左侧节次标签（1-2 / 3-4 / 5-6）。 */
    private fun gridPage(extraLines: List<OcrLine> = emptyList()): OcrPage {
        val header = (0..6).map { day ->
            centered(listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[day], 200 + day * 200, 70)
        }
        val periodLabels = listOf(
            line("1-2", 40, 170),
            line("3-4", 40, 350),
            line("5-6", 40, 530)
        )
        return OcrPage(0, 1600, 1000, header + periodLabels + extraLines)
    }

    @Test
    fun recoversGridFromSeparateHeaderCells() {
        val structure = OcrTableRecovery.recover(gridPage(), axis, periods.size)
        assertEquals(7, structure.columns.size)
        assertEquals(3, structure.rows.size)
        // 该页只有表头与节次标签、没有课程内容，不应凭空生成课程块
        assertTrue(structure.blocks.isEmpty())
        assertEquals(0, structure.columns.first().day)
        assertEquals(1, structure.rows.first().startSection)
        assertEquals(2, structure.rows.first().endSection)
        assertTrue(structure.issues.none { it.code.name == "NO_WEEKDAY_HEADER" })
    }

    @Test
    fun parsesCourseWithWeeksTeacherAndRoom() {
        val page = gridPage(
            listOf(
                centered("数据结构", 200, 130),
                centered("周数:1-16", 200, 190),
                centered("教师:张老师", 200, 220),
                centered("地点:A101", 200, 250)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        val record = parsed.records[0]
        assertEquals("数据结构", record.courseName)
        assertEquals(0, record.dayOfWeek)
        assertEquals(1, record.startSection)
        assertEquals(2, record.endSection)
        assertEquals((1..16).toSet(), record.weeks)
        assertEquals("张老师", record.teacher)
        assertEquals("A101", record.location)
        assertEquals(ImportSource.IMAGE, record.source)
    }

    @Test
    fun parsesOddAndEvenWeeks() {
        val page = gridPage(
            listOf(
                centered("篮球初级班", 200, 130),
                centered("周数:1-8周(单)", 200, 190),
                centered("教师:王老师", 200, 220),
                centered("地点:田径场", 200, 250),
                centered("大学英语3", 800, 300),
                centered("周数:2-10周(双)", 800, 330),
                centered("教师:李老师", 800, 360),
                centered("地点:B202", 800, 390)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(2, parsed.records.size)
        val basketball = parsed.records.first { it.courseName == "篮球初级班" }
        assertEquals(setOf(1, 3, 5, 7), basketball.weeks)
        assertEquals(0, basketball.dayOfWeek)
        val english = parsed.records.first { it.courseName == "大学英语3" }
        assertEquals(setOf(2, 4, 6, 8, 10), english.weeks)
        assertEquals(3, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(4, english.endSection)
    }

    @Test
    fun splitsTwoCoursesInSameCellByWeekMarkers() {
        val page = gridPage(
            listOf(
                centered("高等数学", 200, 110),
                centered("周数:1-8", 200, 140),
                centered("教师:张老师", 200, 170),
                centered("大学英语", 200, 200),
                centered("周数:9-16", 200, 230),
                centered("教师:李老师", 200, 250)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(2, parsed.records.size)
        val math = parsed.records.first { it.courseName == "高等数学" }
        assertEquals((1..8).toSet(), math.weeks)
        assertEquals("张老师", math.teacher)
        val english = parsed.records.first { it.courseName == "大学英语" }
        assertEquals((9..16).toSet(), english.weeks)
        assertEquals("李老师", english.teacher)
    }

    @Test
    fun mergesCourseBlockSpanningTwoRows() {
        val page = gridPage(
            listOf(
                centered("离散数学", 200, 120),
                centered("周数:1-16", 200, 320),
                centered("教师:赵老师", 200, 350),
                centered("地点:C303", 200, 380)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        val record = parsed.records[0]
        assertEquals("离散数学", record.courseName)
        assertEquals(1, record.startSection)
        assertEquals(4, record.endSection)
        assertEquals((1..16).toSet(), record.weeks)
    }

    @Test
    fun singleLineHeaderWithSpacesIsSplitProportionally() {
        val header = line("星期一 星期二 星期三 星期四 星期五 星期六 星期日", 120, 70, width = 1360)
        val page = OcrPage(
            0, 1600, 1000,
            listOf(
                header,
                line("1-2", 40, 170),
                line("3-4", 40, 350),
                centered("计算机网络", 800, 130),
                centered("周数:5-12", 800, 190),
                centered("教师:孙老师", 800, 220),
                centered("地点:D404", 800, 250)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        assertEquals(7, structure.columns.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        assertEquals(3, parsed.records[0].dayOfWeek)
        assertEquals((5..12).toSet(), parsed.records[0].weeks)
    }

    @Test
    fun timeLabelsAreMappedThroughPeriodAxis() {
        val header = (0..6).map { centered(listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[it], 200 + it * 200, 70) }
        val page = OcrPage(
            0, 1600, 1000,
            header + listOf(
                line("08:00-09:40", 20, 165, width = 120),
                line("10:00-11:40", 20, 345, width = 120),
                centered("概率论", 200, 130),
                centered("周数:1-16", 200, 190),
                centered("教师:周老师", 200, 220)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        assertEquals(2, structure.rows.size)
        assertEquals(1, structure.rows[0].startSection)
        assertEquals(2, structure.rows[0].endSection)
        assertEquals(3, structure.rows[1].startSection)
        assertEquals(4, structure.rows[1].endSection)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        assertEquals(1, parsed.records[0].startSection)
        assertEquals(2, parsed.records[0].endSection)
    }

    @Test
    fun keepsCourseListWhenNoGridExists() {
        val page = OcrPage(
            0, 800, 600,
            listOf(
                line("其他课程", 100, 40, width = 120),
                line("形势与政策", 100, 100, width = 120),
                line("周数:3-8", 100, 140, width = 120),
                line("教师:王老师", 100, 180, width = 120)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        val record = parsed.records[0]
        assertEquals("形势与政策", record.courseName)
        assertNull(record.dayOfWeek)
        assertNull(record.startSection)
        assertEquals((3..8).toSet(), record.weeks)
        assertEquals("王老师", record.teacher)
        assertTrue(parsed.issues.any { it.code == "OCR_NO_TIME" })
        assertTrue(structure.issues.any { it.code.name == "LIST_FALLBACK" })
        assertTrue(structure.issues.any { it.code.name == "NO_WEEKDAY_HEADER" })
    }

    @Test
    fun missingPeriodLabelsKeepsRecordWithConfirmation() {
        val header = (0..6).map { centered(listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日")[it], 200 + it * 200, 70) }
        val page = OcrPage(
            0, 1600, 900,
            header + listOf(
                centered("计算方法", 200, 120),
                centered("周数:3-15", 200, 160),
                centered("教师:钱老师", 200, 200)
            )
        )
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        assertTrue(structure.rows.isEmpty())
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        assertEquals(0, parsed.records[0].dayOfWeek)
        assertNull(parsed.records[0].startSection)
        assertTrue(parsed.issues.any { it.code == "OCR_NO_TIME" })
        assertTrue(structure.issues.any { it.code.name == "NO_PERIOD_LABELS" })
    }

    @Test
    fun blankPageReportsNoCourse() {
        val structure = OcrTableRecovery.recover(OcrPage(0, 800, 600, emptyList()), axis, periods.size)
        assertTrue(structure.blocks.isEmpty())
        assertTrue(structure.issues.any { it.code.name == "NO_COURSE_BLOCK" })
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertTrue(parsed.records.isEmpty())
    }

    @Test
    fun multiPageParsingDedupesIdenticalRecords() {
        val first = OcrTableRecovery.recover(
            gridPage(
                listOf(
                    centered("数据结构", 200, 130),
                    centered("周数:1-16", 200, 190),
                    centered("教师:张老师", 200, 220),
                    centered("地点:A101", 200, 250)
                )
            ),
            axis, periods.size
        )
        val second = first.copy(pageIndex = 1)
        val parsed = OcrCourseRecordParser.parseAll(listOf(first, second), ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        assertTrue(parsed.issues.any { it.code == "OCR_DUPLICATE_PAGE" })
    }

    @Test
    fun missingWeekExpressionKeepsRecordWithoutWeeks() {
        val page = gridPage(listOf(centered("体育", 200, 130), centered("教师:吴老师", 200, 190)))
        val structure = OcrTableRecovery.recover(page, axis, periods.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        assertEquals("体育", parsed.records[0].courseName)
        assertTrue(parsed.records[0].weeks.isEmpty())
        assertNull(parsed.records[0].originalWeekExpression)
    }

    @Test
    fun blockWithUnknownFieldsDoesNotInventData() {
        val block = OcrCourseBlock(
            day = null, startSection = null, endSection = null,
            lines = listOf(line("未识别课程", 100, 100), line("周数:1-4", 100, 130))
        )
        val structure = OcrPageStructure(0, emptyList(), emptyList(), listOf(block), null, emptyList())
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertEquals(1, parsed.records.size)
        assertNull(parsed.records[0].dayOfWeek)
        assertNull(parsed.records[0].startSection)
        assertEquals("", parsed.records[0].teacher)
        assertEquals("", parsed.records[0].location)
        assertNotNull(parsed.issues.firstOrNull { it.code == "OCR_NO_TIME" })
    }

    /**
     * 地大教务 PDF/截图的「星期分组 + 课程列表」版式：
     * 左列星期标签纵向排列、每组覆盖多行；每行 = 节次 + 课程名 + 周数/教师/地点。
     */
    @Test
    fun recoversCugStyleDayGroupList() {
        val lines = mutableListOf<OcrLine>()
        fun dayLabel(text: String, top: Int) {
            lines += line(text, 40, top, width = 70, height = 40)
        }
        fun courseRow(top: Int, period: String, name: String, detail: String) {
            lines += line(period, 180, top - 15, width = 60)
            lines += line(name, 350, top - 15, width = 130)
            lines += line(detail, 700, top - 15, width = 560)
        }
        // 星期一：4 行，标签纵向居中于整组
        dayLabel("星期一", 260)
        courseRow(150, "1-2", "数据结构", "周数:1-5周,7-11周/教师:李桂玲/地点:公教2-509")
        courseRow(250, "3-4", "离散数学", "周数:4-5周,7-18周/教师:吴亦奇/地点:公教2-306")
        courseRow(350, "5-6", "毛泽东思想概论", "周数:1-5周,7-10周/教师:王永/地点:公教2-310")
        courseRow(450, "9-10", "概率论与数理统计A", "周数:16周/教师:汤庆/地点:公教2-510")
        // 星期二：2 行
        dayLabel("星期二", 630)
        courseRow(600, "1-2", "篮球初级班", "周数:5-7周(单),7-18周/教师:李伦/地点:未来城田径场")
        courseRow(700, "3-4", "计算机网络", "周数:1-5周,7-11周/教师:陈晶/地点:公教2-402")
        // 星期三：2 行
        dayLabel("星期三", 880)
        courseRow(850, "1-2", "计算方法", "周数:1-3周,7-8周,10-14周/教师:万林/地点:公教2-309")
        courseRow(950, "3-4", "大学英语3", "周数:1-5周,7-17周/教师:吕瑶/地点:公教2-315")

        val structure = OcrTableRecovery.recover(OcrPage(0, 1400, 1000, lines), axis, periods.size)
        val parsed = OcrCourseRecordParser.parse(structure, ImportSource.IMAGE)
        assertTrue(structure.issues.any { it.code.name == "LIST_FALLBACK" })
        assertEquals(8, parsed.records.size)
        val dataStructure = parsed.records.first { it.courseName == "数据结构" }
        assertEquals(0, dataStructure.dayOfWeek)
        assertEquals(1, dataStructure.startSection)
        assertEquals(2, dataStructure.endSection)
        assertEquals(setOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11), dataStructure.weeks)
        assertEquals("李桂玲", dataStructure.teacher)
        assertTrue(dataStructure.location.contains("公教2-509"))

        val basketball = parsed.records.first { it.courseName == "篮球初级班" }
        assertEquals(1, basketball.dayOfWeek)
        assertEquals(setOf(5, 7) + (7..18).toSet(), basketball.weeks)

        val english = parsed.records.first { it.courseName == "大学英语3" }
        assertEquals(2, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(4, english.endSection)
        assertEquals((1..5).toSet() + (7..17).toSet(), english.weeks)
    }
}
