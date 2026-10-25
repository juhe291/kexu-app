package com.kexu.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream

/**
 * PDF 文字块坐标提取（PDFBox-Android）。
 * y 统一为从页面上边缘往下（PDFBox getYDirAdj 已处理页面方向），并保留旋转角供模板判断。
 */
object PdfLayoutExtractor {

    class BlankPdfException : Exception("PDF 中没有可提取的文字（可能是扫描件或空白页）")
    class EncryptedPdfException : Exception("PDF 已加密，无法读取，请提供未加密的课表")
    class BrokenPdfException(message: String) : Exception(message)

    fun extract(context: Context, input: InputStream): PdfDocumentLayout {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(input)
        } catch (e: Exception) {
            throw BrokenPdfException("PDF 文件损坏或格式不支持")
        }
        return document.use { doc ->
            if (doc.isEncrypted) throw EncryptedPdfException()
            val pages = mutableMapOf<Int, MutableList<PdfWord>>()
            val dimensions = mutableMapOf<Int, Triple<Float, Float, Int>>()
            val stripper = object : PDFTextStripper() {
                override fun writeString(text: String, textPositions: List<TextPosition>) {
                    val trimmed = text.trim()
                    if (trimmed.isEmpty() || textPositions.isEmpty()) return
                    val first = textPositions.first()
                    val last = textPositions.last()
                    val pageIndex = currentPageNo - 1
                    val page = currentPage
                    dimensions[pageIndex] = Triple(page.mediaBox.width, page.mediaBox.height, page.rotation)
                    val width = (last.xDirAdj + last.widthDirAdj - first.xDirAdj).coerceAtLeast(1f)
                    val height = textPositions.maxOf { it.heightDir }.coerceAtLeast(1f)
                    pages.getOrPut(pageIndex) { mutableListOf() }.add(
                        PdfWord(trimmed, first.xDirAdj, first.yDirAdj, width, height, pageIndex)
                    )
                }
            }.apply {
                sortByPosition = true
                lineSeparator = "\n"
            }
            stripper.getText(doc)
            if (pages.isEmpty()) throw BlankPdfException()
            PdfDocumentLayout(
                pages.entries.sortedBy { it.key }.map { (index, words) ->
                    val dim = dimensions[index] ?: Triple(595f, 842f, 0)
                    PdfPageLayout(index, dim.first, dim.second, dim.third, words.toList())
                }
            )
        }
    }
}
