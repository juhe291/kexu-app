package com.kexu.app

import com.kexu.app.domain.PdfTimetableParser
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfExtractionProbeTest {
    @Test
    fun parseRealCugTimetablePdf() {
        val resource = TestFixtures.require("cug_wh_timetable.pdf")
        PDDocument.load(resource).use { doc ->
            val text = PDFTextStripper().apply { sortByPosition = true; lineSeparator = "\n" }.getText(doc)
            val result = PdfTimetableParser.parse(text)
            println("COURSES=" + result.courses.size + " WARN=" + result.warnings.size)
            assertTrue("should parse courses", result.courses.size >= 5)
            assertTrue(result.courses.any { it.name.contains("数据结构") })
        }
    }
}
