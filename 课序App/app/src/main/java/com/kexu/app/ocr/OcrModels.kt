package com.kexu.app.ocr

import com.kexu.app.domain.Period

/**
 * 本地 OCR 识别结果：文本 + 页面像素坐标（左上角为原点，y 向下）。
 * 该模型不依赖 Android，可在 JVM 单元测试中直接构造，用于验证表格结构恢复与课程语义解析。
 */
data class OcrLine(
    val text: String,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val width: Float get() = (right - left).toFloat()
    val height: Float get() = (bottom - top).toFloat()
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    /** 去掉所有空白（OCR 常把「星期一」识别成「星期 一」）。 */
    val compactText: String get() = text.filterNot { it.isWhitespace() }

    fun verticalDistanceTo(other: OcrLine): Float = kotlin.math.abs(centerY - other.centerY)
}

/** 一页 OCR 结果（图片或扫描 PDF 的一页），保留原始像素尺寸供版面恢复使用。 */
data class OcrPage(
    val pageIndex: Int,
    val width: Int,
    val height: Int,
    val lines: List<OcrLine>
) {
    val charCount: Int get() = lines.sumOf { it.compactText.length }
}

/** 版面恢复后的星期列（像素坐标）。 */
data class OcrColumn(
    val day: Int,
    val left: Float,
    val right: Float,
    val headerCenterX: Float
)

/** 版面恢复后的节次行（像素坐标，rows 按 top 升序）。 */
data class OcrRow(
    val startSection: Int,
    val endSection: Int,
    val top: Float,
    val bottom: Float,
    val labelCenterY: Float
)

/** 恢复出的一个课程块：星期 + 节次跨度 + 块内 OCR 行（用于语义解析）。 */
data class OcrCourseBlock(
    val day: Int?,
    val startSection: Int?,
    val endSection: Int?,
    val lines: List<OcrLine>,
    val rowIndex: Int? = null,
    val pageIndex: Int = 0
) {
    val text: String get() = lines.joinToString("\n") { it.compactText }
}

/**
 * 一页的课表版面恢复结果。
 * [issues] 只记录“结构恢复阶段”的问题（找不到星期表头、找不到节次标签等），不做业务校验。
 */
data class OcrPageStructure(
    val pageIndex: Int,
    val columns: List<OcrColumn>,
    val rows: List<OcrRow>,
    val blocks: List<OcrCourseBlock>,
    val headerBottom: Float?,
    val issues: List<OcrStructureIssue>
) {
    val hasWeekdayHeader: Boolean get() = columns.isNotEmpty()
    val hasPeriodRows: Boolean get() = rows.isNotEmpty()
}

enum class OcrStructureCode {
    NO_WEEKDAY_HEADER,
    NO_PERIOD_LABELS,
    NO_COURSE_BLOCK,
    LIST_FALLBACK,
    MULTI_LINE_HEADER
}

data class OcrStructureIssue(
    val code: OcrStructureCode,
    val message: String,
    val page: Int
)

/** 表格结构恢复使用的节次时间轴（与 App 的节次配置一致，用于把 OCR 的时间标签映射成节次）。 */
data class OcrPeriodAxis(val periods: List<Period>) {
    val size: Int get() = periods.size

    fun sectionAt(minutes: Int): Int? {
        val index = periods.indexOfFirst { minutes in it.fromMin until it.toMin }
        if (index >= 0) return index + 1
        return periods.indices.minByOrNull { i ->
            minOf(kotlin.math.abs(periods[i].fromMin - minutes), kotlin.math.abs(periods[i].toMin - minutes))
        }?.let { i ->
            val p = periods[i]
            val distance = minOf(kotlin.math.abs(p.fromMin - minutes), kotlin.math.abs(p.toMin - minutes))
            if (distance <= 20) i + 1 else null
        }
    }
}
