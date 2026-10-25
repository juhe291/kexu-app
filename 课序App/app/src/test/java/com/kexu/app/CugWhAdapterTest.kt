package com.kexu.app

import com.kexu.app.pdf.CugWhTableAdapter
import com.kexu.app.pdf.PdfDocumentLayout
import com.kexu.app.pdf.PdfPageLayout
import com.kexu.app.pdf.PdfWord
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertTrue
import org.junit.Test

class CugWhAdapterTest {

    private fun loadLayout(): PdfDocumentLayout {
        val resource = TestFixtures.require("cug_wh_timetable.pdf")
        PDDocument.load(resource).use { document ->
            val pages = mutableMapOf<Int, MutableList<PdfWord>>()
            val dims = mutableMapOf<Int, Triple<Float, Float, Int>>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    val trimmed = text.trim()
                    if (trimmed.isEmpty() || textPositions.isEmpty()) return
                    val first = textPositions.first()
                    val last = textPositions.last()
                    val pageIndex = currentPageNo - 1
                    val page = currentPage
                    dims[pageIndex] = Triple(page.mediaBox.width, page.mediaBox.height, page.rotation)
                    val width = (last.xDirAdj + last.widthDirAdj - first.xDirAdj).coerceAtLeast(1f)
                    val height = textPositions.maxOf { it.heightDir }.coerceAtLeast(1f)
                    pages.getOrPut(pageIndex) { mutableListOf() }
                        .add(PdfWord(trimmed, first.xDirAdj, first.yDirAdj, width, height, pageIndex))
                }
            }.apply { sortByPosition = true }
            stripper.getText(document)
            return PdfDocumentLayout(
                pages.entries.sortedBy { it.key }.map { (index, words) ->
                    val dim = dims[index] ?: Triple(595f, 842f, 0)
                    PdfPageLayout(index, dim.first, dim.second, dim.third, words)
                }
            )
        }
    }

    @Test
    fun parsesRealCugTimetable() {
        val layout = loadLayout()
        val result = CugWhTableAdapter().adapt(layout)
        println("RECORDS=" + result.records.size + " ISSUES=" + result.issues.size)
        result.records.forEach { r ->
            println(
                "${r.courseName} day=${r.dayOfWeek} p=${r.startSection}-${r.endSection} " +
                    "weeks=${r.weeks.sorted()} teacher=${r.teacher} room=${r.location} page=${r.sourcePage} " +
                    "weekErr=${r.weeksError ?: ""}"
            )
        }
        assertTrue("records should be parsed", result.records.size >= 20)
        val dsMonday = result.records.firstOrNull { it.courseName.contains("数据结构") && it.dayOfWeek == 0 }
        assertTrue("数据结构 Monday", dsMonday != null)
        dsMonday!!
        assertTrue("数据结构 weeks", dsMonday.weeks.containsAll(setOf(1, 2, 3, 4, 5, 7, 8, 9, 10, 11)))
        assertTrue("数据结构 teacher", dsMonday.teacher.contains("李桂玲"))
        assertTrue("数据结构 room", dsMonday.location.contains("公教2-509"))
        assertTrue("数据结构 period", dsMonday.startSection == 1 && dsMonday.endSection == 2)

        assertTrue("篮球 weeks", result.records.first { it.courseName.contains("篮球") }
            .weeks.containsAll(setOf(5, 7, 8, 9, 19)))
        assertTrue("英语多教室", result.records.filter { it.courseName.contains("大学英语3") }.map { it.location }.distinct().size >= 2)
        assertTrue("概率论 Thursday 两段", result.records.count { it.courseName.contains("概率论") && it.dayOfWeek == 3 } >= 2)
        val design = result.records.firstOrNull { it.courseName.contains("数据结构课程设计") }
        assertTrue("其他课程应保留", design != null)
        assertTrue("其他课程周次", design!!.weeks.containsAll(setOf(16, 17)))
        assertTrue("其他课程无编造时间", design.dayOfWeek == null && design.startSection == null)
        assertTrue("不应出现 QQ 群垃圾课程名", result.records.none { it.courseName.contains("QQ") || it.courseName.contains("周/") })
    }
}
