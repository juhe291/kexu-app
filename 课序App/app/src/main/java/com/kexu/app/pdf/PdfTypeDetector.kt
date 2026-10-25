package com.kexu.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.InputStream

/** PDF 类型：有文字层 / 扫描图像为主 / 文字与图片混合。 */
enum class PdfDocumentKind { TEXT, SCANNED, MIXED }

data class PdfInspection(
    val kind: PdfDocumentKind,
    val pageCount: Int,
    val pageCharCounts: List<Int>,
    val totalChars: Int,
    val pagesWithText: Int,
    val pagesWithoutText: Int
) {
    val summary: String
        get() = when (kind) {
            PdfDocumentKind.TEXT -> "$pageCount 页文字型 PDF（共 $totalChars 字符）"
            PdfDocumentKind.SCANNED -> "$pageCount 页扫描版 PDF（文字层缺失，使用本地 OCR）"
            PdfDocumentKind.MIXED -> "$pageCount 页混合型 PDF（$pagesWithText 页有文字层 / $pagesWithoutText 页需 OCR）"
        }
}

/**
 * 判断 PDF 属于哪一类：优先使用原生文字层，只有文字层缺失或明显不完整时才回退本地 OCR。
 * 避免把文字型 PDF 一律转图片识别（效率低、准确率反而下降）。
 */
object PdfTypeDetector {

    class BrokenPdfException(message: String) : Exception(message)
    class EncryptedPdfException : Exception("PDF 已加密，无法读取，请提供未加密的课表")

    /** 一页至少要有这么多非空白字符才算「有文字层」，低于此值视为扫描页。 */
    const val MIN_CHARS_PER_TEXT_PAGE = 30

    fun inspect(context: Context, input: InputStream): PdfInspection {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(input)
        } catch (e: Exception) {
            throw BrokenPdfException("PDF 文件损坏或格式不支持")
        }
        return document.use { doc ->
            if (doc.isEncrypted) throw EncryptedPdfException()
            val pageCount = doc.numberOfPages
            if (pageCount <= 0) throw BrokenPdfException("PDF 中没有页面")
            val counts = (1..pageCount).map { page ->
                runCatching {
                    val stripper = PDFTextStripper().apply {
                        startPage = page
                        endPage = page
                        sortByPosition = true
                    }
                    stripper.getText(doc).count { !it.isWhitespace() }
                }.getOrDefault(0)
            }
            val withText = counts.count { it >= MIN_CHARS_PER_TEXT_PAGE }
            PdfInspection(
                kind = classify(counts),
                pageCount = pageCount,
                pageCharCounts = counts,
                totalChars = counts.sum(),
                pagesWithText = withText,
                pagesWithoutText = pageCount - withText
            )
        }
    }

    /** 纯函数：按每页有效字符数分类，便于 JVM 单测。 */
    fun classify(pageCharCounts: List<Int>, minCharsPerPage: Int = MIN_CHARS_PER_TEXT_PAGE): PdfDocumentKind {
        if (pageCharCounts.isEmpty()) return PdfDocumentKind.SCANNED
        val textPages = pageCharCounts.count { it >= minCharsPerPage }
        return when (textPages) {
            0 -> PdfDocumentKind.SCANNED
            pageCharCounts.size -> PdfDocumentKind.TEXT
            else -> PdfDocumentKind.MIXED
        }
    }
}
