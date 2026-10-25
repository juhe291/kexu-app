package com.kexu.app.pdf

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import java.io.InputStream

/** 用 PDFBox-Android 从 PDF 读取可复制文字；加密/空白/损坏分别抛不同异常。 */
object PdfTextExtractor {

    class BlankPdfException : Exception("PDF 中没有可提取的文字（可能是扫描件或空白页）")
    class EncryptedPdfException : Exception("PDF 已加密，无法读取，请提供未加密的课表")
    class BrokenPdfException(message: String) : Exception(message)

    fun extract(context: Context, input: InputStream): String {
        PDFBoxResourceLoader.init(context.applicationContext)
        val document = try {
            PDDocument.load(input)
        } catch (e: InvalidPasswordException) {
            throw EncryptedPdfException()
        } catch (e: Exception) {
            throw BrokenPdfException("PDF 文件损坏或格式不支持")
        }
        return document.use { doc ->
            if (doc.isEncrypted) throw EncryptedPdfException()
            val stripper = PDFTextStripper().apply {
                sortByPosition = true
                lineSeparator = "\n"
            }
            val text = stripper.getText(doc).trim()
            if (text.isBlank()) throw BlankPdfException()
            text
        }
    }
}
