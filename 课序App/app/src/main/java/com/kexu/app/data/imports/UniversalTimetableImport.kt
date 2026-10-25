package com.kexu.app.data.imports

import android.content.Context
import com.kexu.app.domain.Period
import com.kexu.app.domain.Semester
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.CourseImportResult
import com.kexu.app.domain.imports.ImportFormatDetector
import com.kexu.app.domain.imports.ImportIssue
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.ImportValidator
import com.kexu.app.domain.imports.IssueSeverity
import com.kexu.app.ocr.OcrCourseRecordParser
import com.kexu.app.ocr.OcrImagePipeline
import com.kexu.app.ocr.OcrPage
import com.kexu.app.ocr.OcrPeriodAxis
import com.kexu.app.ocr.OcrTableRecovery
import com.kexu.app.pdf.CugWhTableAdapter
import com.kexu.app.pdf.PdfDocumentKind
import com.kexu.app.pdf.PdfLayoutExtractor
import com.kexu.app.pdf.PdfTypeDetector
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream

/**
 * 统一导入入口：格式检测 → 对应解析器/学校适配器/OCR 流水线 → 统一记录 → 校验。
 *
 * - 只做解析与校验，不操作 Room、不直接更新 UI；调用方拿到 [CourseImportResult] 后进入可编辑预览。
 * - PDF 先判断文字层：文字型走 PDFBox 坐标解析；扫描版/混合版或文字层解析不出课程时才用本地 OCR。
 * - 图片（PNG/JPG）走本地 ML Kit OCR → 表格结构恢复；全部离线，不联网。
 */
object UniversalTimetableImport {

    /** 允许的最大文件大小（课程表/日历文件足够）；避免误选视频/压缩包造成内存压力。 */
    const val MAX_FILE_BYTES = 30 * 1024 * 1024

    private const val OCR_MAX_PAGES = 8

    private data class ParseOutcome(
        val records: List<CourseImportRecord> = emptyList(),
        val issues: List<ImportIssue> = emptyList(),
        val schoolTemplate: String? = null,
        val rawTextLength: Int = 0
    )

    suspend fun import(
        context: Context,
        bytes: ByteArray,
        fileName: String,
        semester: Semester,
        periods: List<Period>,
        onProgress: ((String) -> Unit)? = null
    ): CourseImportResult = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        require(bytes.isNotEmpty()) { "文件为空，请重新选择课表文件" }
        require(bytes.size <= MAX_FILE_BYTES) {
            "文件过大（${bytes.size / 1024 / 1024}MB），课表文件不应超过 ${MAX_FILE_BYTES / 1024 / 1024}MB"
        }
        val source = ImportFormatDetector.detect(fileName, bytes.take(8).toByteArray())
            ?: throw IllegalArgumentException("无法识别文件类型：支持 CSV / ICS / PDF / XLSX / HTML / JSON 模板 / 图片（PNG、JPG）课表")

        val outcome = when (source) {
            ImportSource.PDF -> importPdf(context, bytes, periods, onProgress)
            ImportSource.IMAGE -> importImage(bytes, periods, onProgress)
            ImportSource.JSON -> {
                onProgress?.invoke("正在解析 JSON 课表模板…")
                val decoded = TextDecoding.decode(bytes)
                val parsed = JsonTimetableImporter.import(decoded.text, periods.size)
                ParseOutcome(parsed.records, parsed.issues)
            }
            ImportSource.XLSX -> {
                onProgress?.invoke("正在解析 Excel 课表…")
                ParseOutcome(records = CugXlsxImporter().import(bytes))
            }
            ImportSource.HTML -> {
                onProgress?.invoke("正在解析 HTML 课表…")
                val text = TextDecoding.decode(bytes).text
                ParseOutcome(records = HtmlTimetableImporter.import(text))
            }
            ImportSource.CSV -> {
                onProgress?.invoke("正在解析 CSV 课表…")
                val decoded = TextDecoding.decode(bytes)
                val parsed = CsvTimetableImporter.import(decoded.text, periods.size)
                val issues = parsed.issues.toMutableList()
                if (!decoded.charset.equals("UTF-8", ignoreCase = true)) {
                    issues += ImportIssue(
                        IssueSeverity.WARNING, "ENCODING_FALLBACK",
                        "CSV 使用 ${decoded.charset} 解码（兼容中文 Excel 导出的非 UTF-8 文件）"
                    )
                }
                ParseOutcome(parsed.records, issues)
            }
            ImportSource.ICS -> {
                onProgress?.invoke("正在解析 ICS 日历…")
                val decoded = TextDecoding.decode(bytes)
                val parsed = IcsTimetableImporter.import(decoded.text, semester.startDate, periods)
                ParseOutcome(parsed.records, parsed.issues)
            }
        }

        currentCoroutineContext().ensureActive()
        onProgress?.invoke("正在校验导入结果…")
        val records = outcome.records
        val issues = mutableListOf<ImportIssue>()
        issues += outcome.issues
        if (records.isEmpty()) {
            issues += ImportIssue(
                IssueSeverity.FATAL,
                "NO_RECORD",
                if (source == ImportSource.IMAGE || source == ImportSource.PDF) {
                    "没有解析出课程：请确认图片/PDF 是完整清晰的课表，或换用 CSV/XLSX/HTML 模板"
                } else {
                    "没有解析出课程，请确认文件内容或换用模板/AI 转换"
                }
            )
        }
        issues += ImportValidator.validate(records, semester, periods.size)
        val distinctIssues = issues.distinctBy { listOf(it.severity, it.code, it.message, it.recordIndex) }
        CourseImportResult(
            source = source,
            records = records,
            issues = distinctIssues,
            schoolTemplate = outcome.schoolTemplate,
            rawTextLength = outcome.rawTextLength.takeIf { it > 0 } ?: bytes.size,
            elapsedMs = System.currentTimeMillis() - startedAt
        )
    }

    // ---------------------------------------------------------------- PDF

    private suspend fun importPdf(
        context: Context,
        bytes: ByteArray,
        periods: List<Period>,
        onProgress: ((String) -> Unit)?
    ): ParseOutcome {
        onProgress?.invoke("正在判断 PDF 类型…")
        val inspection = PdfTypeDetector.inspect(context, ByteArrayInputStream(bytes))
        val records = mutableListOf<CourseImportRecord>()
        val issues = mutableListOf<ImportIssue>()
        var schoolTemplate: String? = null

        if (inspection.kind != PdfDocumentKind.SCANNED) {
            onProgress?.invoke("正在提取 PDF 文字与坐标…")
            try {
                val layout = PdfLayoutExtractor.extract(context, ByteArrayInputStream(bytes))
                val adapted = CugWhTableAdapter().adapt(layout)
                records += adapted.records
                // NO_RECORD 由统一入口在最终记录为空时给出，避免重复
                issues += adapted.issues.filter { it.code != "NO_RECORD" }
                if (adapted.records.isNotEmpty()) schoolTemplate = "中国地质大学（武汉）"
                issues += ImportIssue(
                    IssueSeverity.WARNING, "PDF_TYPE",
                    "${inspection.summary}；已按学校模板解析"
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                issues += ImportIssue(
                    IssueSeverity.WARNING, "PDF_TEXT_FAILED",
                    "PDF 文字层提取失败：${e.message ?: "未知错误"}，将尝试本地 OCR"
                )
            }
        }

        val needOcr = inspection.kind == PdfDocumentKind.SCANNED ||
            (inspection.kind == PdfDocumentKind.MIXED && records.isEmpty())
        val fallbackOcr = inspection.kind == PdfDocumentKind.TEXT && records.isEmpty()
        if (needOcr || fallbackOcr) {
            try {
                onProgress?.invoke(
                    if (inspection.kind == PdfDocumentKind.SCANNED) "扫描版 PDF：正在本地 OCR 逐页识别…"
                    else "PDF 文字层解析不出课程：正在本地 OCR 兜底…"
                )
                val pages = OcrImagePipeline.recognizeScannedPdf(
                    context = context,
                    bytes = bytes,
                    maxPages = OCR_MAX_PAGES,
                    onProgress = { done, total -> onProgress?.invoke("正在 OCR 第 $done/$total 页…") }
                )
                val ocr = parseOcrPages(pages, periods, ImportSource.PDF, onProgress)
                if (fallbackOcr) {
                    records += ocr.records
                    issues += ocr.issues
                    if (ocr.records.isNotEmpty()) {
                        issues += ImportIssue(
                            IssueSeverity.WARNING, "OCR_FALLBACK",
                            "PDF 文字层未解析出课程，已回退本地 OCR 识别（请逐条核对）"
                        )
                    }
                } else if (inspection.kind == PdfDocumentKind.MIXED) {
                    val known = records.map { OcrCourseRecordParser.identity(it) }.toHashSet()
                    var added = 0
                    ocr.records.forEach { record ->
                        if (known.add(OcrCourseRecordParser.identity(record))) {
                            records += record
                            added++
                        }
                    }
                    issues += ocr.issues
                    issues += ImportIssue(
                        IssueSeverity.WARNING, "PDF_MIXED",
                        "混合型 PDF：文字层 + OCR 共合并 $added 条课程，请核对"
                    )
                } else {
                    records.clear()
                    records += ocr.records
                    issues += ocr.issues
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: com.kexu.app.ocr.LocalOcrReader.BlankImageException) {
                issues += ImportIssue(
                    IssueSeverity.FATAL, "OCR_BLANK",
                    "扫描版 PDF 中没有识别到文字：请确认扫描清晰、页面方向正确"
                )
            } catch (e: Exception) {
                issues += ImportIssue(
                    IssueSeverity.FATAL, "OCR_FAILED",
                    "本地 OCR 识别失败：${e.message ?: "未知错误"}"
                )
            }
        }
        return ParseOutcome(
            records = records,
            issues = issues,
            schoolTemplate = schoolTemplate,
            rawTextLength = inspection.totalChars
        )
    }

    // ---------------------------------------------------------------- 图片

    private suspend fun importImage(
        bytes: ByteArray,
        periods: List<Period>,
        onProgress: ((String) -> Unit)?
    ): ParseOutcome {
        onProgress?.invoke("正在本地识别图片文字…")
        val page = try {
            OcrImagePipeline.recognizeImage(bytes)
        } catch (e: com.kexu.app.ocr.LocalOcrReader.BlankImageException) {
            throw IllegalArgumentException("图片中没有识别到文字：请换一张更清晰、方向正确的课表截图")
        }
        return parseOcrPages(listOf(page), periods, ImportSource.IMAGE, onProgress)
    }

    // ---------------------------------------------------------------- OCR 公共

    private suspend fun parseOcrPages(
        pages: List<OcrPage>,
        periods: List<Period>,
        source: ImportSource,
        onProgress: ((String) -> Unit)?
    ): ParseOutcome {
        val axis = OcrPeriodAxis(periods)
        val structures = pages.mapIndexed { index, page ->
            currentCoroutineContext().ensureActive()
            onProgress?.invoke("正在恢复第 ${index + 1}/${pages.size} 页表格结构…")
            OcrTableRecovery.recover(page, axis, periods.size)
        }
        val parsed = OcrCourseRecordParser.parseAll(structures, source)
        val issues = parsed.issues.toMutableList()
        if (parsed.records.isEmpty()) {
            issues += ImportIssue(
                IssueSeverity.FATAL, "OCR_NO_COURSE",
                "本地 OCR 没有识别出课程：请确认图片/PDF 是课表且文字清晰，或改用 CSV/XLSX/HTML 模板"
            )
        }
        return ParseOutcome(
            records = parsed.records,
            issues = issues,
            rawTextLength = pages.sumOf { it.charCount }
        )
    }
}
