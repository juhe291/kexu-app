package com.kexu.app

import com.kexu.app.data.imports.HtmlTimetableImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HtmlTimetableImporterTest {

    @Test
    fun parsesGenericGridHtmlWithMergedCells() {
        val html = """
            <table>
              <tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th></tr>
              <tr>
                <td rowspan="2">1-2</td>
                <td>高等数学<br/>周数: 1-16周/地点: A101/教师: 张老师</td>
                <td></td>
                <td rowspan="2">大学英语3<br/>周数: 1-5周,7-11周/地点: B203/教师: 李老师</td>
              </tr>
              <tr>
                <td>线性代数<br/>周数: 2-16周(双)/地点: A102/教师: 王老师</td>
                <td>数据结构<br/>周数: 1-8周/地点: C301/教师: 赵老师</td>
              </tr>
            </table>
        """.trimIndent()
        val records = HtmlTimetableImporter.import(html)
        println(records)
        assertEquals(4, records.size)
        val math = records.first { it.courseName == "高等数学" }
        assertEquals(0, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals("张老师", math.teacher)
        assertEquals("A101", math.location)
        assertEquals((1..16).toSet(), math.weeks)
        val english = records.first { it.courseName == "大学英语3" }
        assertEquals(2, english.dayOfWeek)
        assertEquals(1, english.startSection)
        assertTrue(english.weeks.containsAll(setOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11)))
        val even = records.first { it.courseName == "线性代数" }
        assertTrue(even.weeks.all { it % 2 == 0 })
    }

    @Test
    fun ignoresTableWithoutWeekdays() {
        val html = "<table><tr><td>课程</td><td>周数: 1-8周</td></tr></table>"
        assertTrue(HtmlTimetableImporter.import(html).isEmpty())
    }
}
