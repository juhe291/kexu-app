package com.kexu.app

import com.kexu.app.pdf.PdfDocumentKind
import com.kexu.app.pdf.PdfTypeDetector
import org.junit.Assert.assertEquals
import org.junit.Test

class PdfTypeDetectorTest {

    @Test
    fun allPagesWithoutTextIsScanned() {
        assertEquals(PdfDocumentKind.SCANNED, PdfTypeDetector.classify(listOf(0, 0, 0)))
        assertEquals(PdfDocumentKind.SCANNED, PdfTypeDetector.classify(listOf(12, 8)))
    }

    @Test
    fun allPagesWithTextIsTextPdf() {
        assertEquals(PdfDocumentKind.TEXT, PdfTypeDetector.classify(listOf(500, 1200, 80)))
        assertEquals(PdfDocumentKind.TEXT, PdfTypeDetector.classify(listOf(PdfTypeDetector.MIN_CHARS_PER_TEXT_PAGE)))
    }

    @Test
    fun somePagesWithoutTextIsMixed() {
        assertEquals(PdfDocumentKind.MIXED, PdfTypeDetector.classify(listOf(500, 0)))
        assertEquals(PdfDocumentKind.MIXED, PdfTypeDetector.classify(listOf(0, 800, 20)))
    }

    @Test
    fun emptyInspectionIsScanned() {
        assertEquals(PdfDocumentKind.SCANNED, PdfTypeDetector.classify(emptyList()))
    }

    @Test
    fun customThresholdIsRespected() {
        assertEquals(PdfDocumentKind.TEXT, PdfTypeDetector.classify(listOf(10), minCharsPerPage = 5))
        assertEquals(PdfDocumentKind.SCANNED, PdfTypeDetector.classify(listOf(10), minCharsPerPage = 50))
    }
}
