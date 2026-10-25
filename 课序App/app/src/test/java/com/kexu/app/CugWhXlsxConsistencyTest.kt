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

class CugWhXlsxConsistencyTest {

    data class Expected(
        val day: Int,
        val start: Int,
        val end: Int,
        val weeks: Set<Int>,
        val teacher: String,
        val room: String,
        val name: String
    )

    private fun layout(): PdfDocumentLayout {
        val stream = TestFixtures.require("cug_wh_timetable.pdf")
        PDDocument.load(stream).use { document ->
            val pages = mutableMapOf<Int, MutableList<PdfWord>>()
            val dims = mutableMapOf<Int, Triple<Float, Float, Int>>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, positions: List<TextPosition>) {
                    val t = text.trim()
                    if (t.isEmpty() || positions.isEmpty()) return
                    val f = positions.first()
                    val l = positions.last()
                    val idx = currentPageNo - 1
                    val pg = currentPage
                    dims[idx] = Triple(pg.mediaBox.width, pg.mediaBox.height, pg.rotation)
                    pages.getOrPut(idx) { mutableListOf() }.add(
                        PdfWord(
                            t, f.xDirAdj, f.yDirAdj,
                            (l.xDirAdj + l.widthDirAdj - f.xDirAdj).coerceAtLeast(1f),
                            positions.maxOf { it.heightDir }.coerceAtLeast(1f), idx
                        )
                    )
                }
            }.apply { sortByPosition = true }
            stripper.getText(document)
            return PdfDocumentLayout(
                pages.entries.sortedBy { it.key }.map { (i, w) ->
                    val d = dims[i] ?: Triple(595f, 842f, 0)
                    PdfPageLayout(i, d.first, d.second, d.third, w)
                }
            )
        }
    }

    private fun expected(): List<Expected> {
        val text = TestFixtures.require("cug_expected.csv").bufferedReader().readText()
        return text.lineSequence().drop(1).filter { it.isNotBlank() }.map { line ->
            val c = line.split(';')
            Expected(
                day = c[0].toInt(),
                start = c[1].toInt(),
                end = c[2].toInt(),
                weeks = c[3].split(',').map { it.toInt() }.toSet(),
                teacher = c[4],
                room = c[5],
                name = c[6]
            )
        }.toList()
    }

    private fun normalize(name: String): String = name.replace(" ", "").replace("*", "")

    @Test
    fun pdfResultMatchesXlsxGroundTruth() {
        val expected = expected()
        val actual = CugWhTableAdapter().adapt(layout()).records
        val problems = mutableListOf<String>()
        var matched = 0
        expected.forEach { e ->
            val candidates = actual.filter { r ->
                normalize(r.courseName) == normalize(e.name) && r.dayOfWeek == e.day &&
                    r.startSection == e.start && r.endSection == e.end
            }
            val record = candidates.firstOrNull { it.weeks == e.weeks } ?: candidates.firstOrNull()
            if (record == null) {
                problems += "缺少课程：" + e.name + " day=" + e.day + " " + e.start + "-" + e.end
            } else {
                matched++
                if (record.weeks != e.weeks) {
                    problems += e.name + " 周次不一致：PDF=" + record.weeks.sorted() + " XLSX=" + e.weeks.sorted()
                }
                if (e.room.isNotBlank() && !record.location.contains(e.room)) {
                    problems += e.name + " 地点不一致：PDF=" + record.location + " XLSX=" + e.room
                }
                if (e.teacher.isNotBlank() && !record.teacher.contains(e.teacher)) {
                    problems += e.name + " 教师不一致：PDF=" + record.teacher + " XLSX=" + e.teacher
                }
            }
        }
        println("EXPECTED=" + expected.size + " MATCHED=" + matched + " PROBLEMS=" + problems.size)
        problems.forEach { println("PROBLEM " + it) }
        assertTrue("应至少匹配 20 条：matched=" + matched, matched >= 20)
        assertTrue("字段应一致：" + problems.take(8), problems.isEmpty())
    }
}
