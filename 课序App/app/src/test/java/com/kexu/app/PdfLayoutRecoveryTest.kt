package com.kexu.app

import com.kexu.app.pdf.PdfDocumentLayout
import com.kexu.app.pdf.PdfPageLayout
import com.kexu.app.pdf.PdfRotationNormalizer
import com.kexu.app.pdf.PdfWord
import com.kexu.app.pdf.TableStructureRecovery
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutRecoveryTest {

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
    fun recoversWeekdayColumnsAndPeriodRowsFromRealPdf() {
        val layout = loadLayout()
        assertTrue("expect at least 2 pages", layout.pages.size >= 2)
        layout.pages.forEach { page ->
            println("PAGE ${page.pageIndex} size=${page.width}x${page.height} rot=${page.rotation} words=${page.words.size}")
        }
        layout.pages[0].words.filter { it.text.contains("星期") || it.text.trim().matches(Regex("\\d{1,2}-\\d{1,2}")) }.take(20).forEach { println("WORD " + it.text + " x=" + it.x + " y=" + it.y + " w=" + it.width + " h=" + it.height) }
        val grid = TableStructureRecovery.recover(layout.pages[0])
        assertNotNull("grid should be recovered", grid)
        grid!!
        println("COLUMNS=" + grid.columns.joinToString { "${it.day}:${it.left.toInt()}-${it.right.toInt()}" })
        println("ROWS=" + grid.rows.joinToString { "${it.startPeriod}-${it.endPeriod}@${it.top.toInt()}-${it.bottom.toInt()}" })
        assertTrue("weekday columns", grid.columns.size >= 5)
        assertTrue("period rows", grid.rows.size >= 3)
        assertTrue("has 1-2 row", grid.rows.any { it.startPeriod == 1 && it.endPeriod == 2 })

        val page = layout.pages[0]
        val allCellText = grid.columns.flatMap { column ->
            grid.rows.map { row ->
                TableStructureRecovery.wordsInCell(page.words, column, row).joinToString("") { it.text }
            }
        }.joinToString("|")
        println("CELL_TEXT_SAMPLE=" + allCellText.take(600))
        assertTrue("course text mapped into cells", allCellText.contains("数据结构") || allCellText.contains("大学英语"))
    }
}
