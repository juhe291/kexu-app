package com.kexu.app.ocr

import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportIssue
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity

/**
 * 把 [OcrTableRecovery] 恢复出的课程块解析成统一中间记录 [CourseImportRecord]。
 *
 * 原则：
 * - 只解析 OCR 里真实出现的信息，缺的字段留空（交给 ImportValidator 标致命/待确认），不编造；
 * - 一个课程块里出现多个「周数」段时分别生成记录（同一节次不同周次的不同课程不合并）；
 * - 列表兜底（没有星期/节次）也保留记录，只把星期/节次留空。
 */
object OcrCourseRecordParser {

    data class ParsedPage(val records: List<CourseImportRecord>, val issues: List<ImportIssue>)

    private val DETAIL_PREFIX = Regex("^(周数|周次|教师|老师|地点|教室|教学班|备注|课程性质|学分)\\s*[:：]?")
    private val TEACHER_REGEX = Regex("(?:教师|老师)\\s*[:：]?\\s*([^/／\\n]{1,20})")
    private val ROOM_REGEX = Regex("(?:地点|教室|上课地点)\\s*[:：]?\\s*([^/／\\n]{1,40})")
    private val WEEK_AFTER_MARKER = Regex("(?:周数|周次)\\s*[:：]?\\s*([^/／\\n]{1,60})")
    private val WEEK_INLINE = Regex(
        "((?:第)?\\s*\\d{1,2}\\s*(?:[-—–~至]\\s*\\d{1,2})?\\s*周(?:\\s*\\(\\s*[单双]\\s*\\))?(?:\\s*[,，、;；]\\s*(?:第)?\\s*\\d{1,2}\\s*(?:[-—–~至]\\s*\\d{1,2})?\\s*周(?:\\s*\\(\\s*[单双]\\s*\\))?)*)"
    )
    private val NOISE_TITLE = Regex("(课程表|课表|学期|学年|作息|节次|星期|周次|备注：|说明)")
    private val LEADING_PERIOD = Regex("^\\s*第?\\s*\\d{1,2}\\s*[-—–~]\\s*\\d{1,2}\\s*节?\\s*")
    private val TRAILING_DETAIL = Regex("(教师|老师|地点|教室|教学班|备注)\\s*[:：].*$")

    fun parse(structure: OcrPageStructure, source: ImportSource): ParsedPage {
        val records = mutableListOf<CourseImportRecord>()
        val issues = mutableListOf<ImportIssue>()
        structure.issues.forEach { issues += it.toImportIssue() }
        structure.blocks.forEach { block ->
            val parsed = parseBlock(block, structure.pageIndex, source)
            parsed.forEach { record ->
                val index = records.size
                if (record.dayOfWeek == null || record.startSection == null) {
                    issues += ImportIssue(
                        IssueSeverity.CONFIRM,
                        "OCR_NO_TIME",
                        "第 ${structure.pageIndex + 1} 页「${record.courseName.ifBlank { "未命名课程" }}」未识别到星期/节次，需要人工确认",
                        index
                    )
                }
                records += record
            }
        }
        return ParsedPage(records, issues)
    }

    /** 多页合并：逐页解析后按「课程名+教师+地点+星期+节次+周次」去重，并保留页号证据。 */
    fun parseAll(structures: List<OcrPageStructure>, source: ImportSource): ParsedPage {
        val records = mutableListOf<CourseImportRecord>()
        val issues = mutableListOf<ImportIssue>()
        structures.forEach { structure ->
            val parsed = parse(structure, source)
            parsed.issues.forEach { issue ->
                // 跨页去重后 index 会变，去掉单页 index，避免误导
                issues += issue.copy(recordIndex = null)
            }
            parsed.records.forEach { record ->
                val key = identity(record)
                if (records.any { identity(it) == key }) {
                    issues += ImportIssue(
                        IssueSeverity.WARNING,
                        "OCR_DUPLICATE_PAGE",
                        "第 ${structure.pageIndex + 1} 页「${record.courseName}」与前页记录重复，已去重"
                    )
                } else {
                    records += record
                }
            }
        }
        return ParsedPage(records, issues)
    }

    fun identity(r: CourseImportRecord): String = listOf(
        r.courseName, r.teacher, r.location, r.dayOfWeek, r.startSection, r.endSection, r.weeks.sorted()
    ).joinToString("|")

    // ---------------------------------------------------------------- 单块解析

    private fun parseBlock(block: OcrCourseBlock, pageIndex: Int, source: ImportSource): List<CourseImportRecord> {
        val lines = block.lines.map { it.compactText }.filter { it.isNotBlank() }
        if (lines.isEmpty()) return emptyList()
        val markerIndices = lines.indices.filter { TEXT_WEEK_MARKER.containsMatchIn(lines[it]) }
        val segments: List<Pair<String?, List<String>>> = if (markerIndices.isEmpty()) {
            // 没有「周数:」标记：整块作为一条记录，从任意一行里找周次表达式
            val name = lines.firstOrNull { isCourseNameLine(it) } ?: lines.firstOrNull()
            listOf(name to lines)
        } else {
            val result = mutableListOf<Pair<String?, List<String>>>()
            var previousName: String? = null
            markerIndices.forEachIndexed { segIndex, markerIndex ->
                val previousMarker = if (segIndex == 0) -1 else markerIndices[segIndex - 1]
                val nameIndex = (markerIndex - 1 downTo 0).firstOrNull { index ->
                    isCourseNameLine(lines[index]) && (segIndex == 0 || index > previousMarker)
                }
                val name = nameIndex?.let { lines[it] }
                    ?: previousName
                    ?: lines.indexOfFirst { isCourseNameLine(it) }.takeIf { it >= 0 }?.let { lines[it] }
                previousName = name
                val nextMarker = markerIndices.getOrNull(segIndex + 1)
                val endIndex = if (nextMarker == null) {
                    lines.lastIndex
                } else {
                    val nextNameIndex = (nextMarker - 1 downTo markerIndex + 1)
                        .firstOrNull { isCourseNameLine(lines[it]) }
                    ((nextNameIndex?.minus(1) ?: (nextMarker - 1))).coerceIn(markerIndex, lines.lastIndex)
                }
                result += name to lines.subList(markerIndex, endIndex + 1)
            }
            result
        }
        return segments.mapNotNull { (name, segmentLines) -> buildRecord(name, segmentLines, block, pageIndex, source) }
    }

    private fun isCourseNameLine(text: String): Boolean =
        !DETAIL_PREFIX.containsMatchIn(text) && !TEXT_WEEK_MARKER.containsMatchIn(text) &&
            !NOISE_TITLE.containsMatchIn(text) && looksLikeCourseName(text)

    private fun looksLikeCourseName(text: String): Boolean {
        val t = text.trim()
        if (t.length !in 2..40) return false
        if (Regex("^[\\d\\s\\-—–~:：/、,，.。()（）]+$").matches(t)) return false
        return true
    }

    private fun isTeacherOnly(text: String): Boolean =
        Regex("^[\\u4e00-\\u9fa5]{2,4}老师?$").matches(text)

    private fun buildRecord(
        name: String?,
        segmentLines: List<String>,
        block: OcrCourseBlock,
        pageIndex: Int,
        source: ImportSource
    ): CourseImportRecord? {
        val segmentText = segmentLines.joinToString("\n")
        val cleanedName = cleanName(name ?: segmentLines.firstOrNull().orEmpty())
        if (cleanedName.isBlank() && segmentText.isBlank()) return null
        val weeksRaw = findWeekExpression(segmentText)
        val rule = weeksRaw?.let { WeekRuleParser.parse(it) }
        val teacher = cleanField(TEACHER_REGEX.find(segmentText)?.groupValues?.get(1))
        val room = cleanField(ROOM_REGEX.find(segmentText)?.groupValues?.get(1))
        val evidence = mutableListOf("第 ${pageIndex + 1} 页 OCR")
        if (block.day != null) evidence += "星期${weekdayName(block.day)}"
        if (block.startSection != null) evidence += "第${block.startSection}-${block.endSection}节"
        evidence += "OCR原文：" + segmentLines.joinToString(" / ").take(160)
        val confidence = buildConfidence(block, rule?.isValid == true, teacher, room)
        return CourseImportRecord(
            courseName = cleanedName,
            teacher = teacher,
            location = room,
            dayOfWeek = block.day,
            startSection = block.startSection,
            endSection = block.endSection,
            weeks = rule?.weeks ?: emptySet(),
            originalWeekExpression = weeksRaw,
            source = source,
            sourcePage = pageIndex + 1,
            weeksError = rule?.error,
            evidence = evidence,
            confidence = confidence
        )
    }

    private fun buildConfidence(block: OcrCourseBlock, weeksValid: Boolean, teacher: String, room: String): Int {
        var score = 30
        if (block.day != null) score += 15
        if (block.startSection != null) score += 15
        if (weeksValid) score += 20
        if (teacher.isNotBlank()) score += 8
        if (room.isNotBlank()) score += 8
        return score.coerceIn(0, 95)
    }

    private fun findWeekExpression(text: String): String? {
        WEEK_AFTER_MARKER.find(text)?.groupValues?.get(1)?.let { raw ->
            val cleaned = raw.replace(TRAILING_DETAIL, "").trim()
            if (cleaned.any { it.isDigit() }) return cleaned
        }
        WEEK_INLINE.find(text)?.groupValues?.get(1)?.trim()?.let { raw ->
            if (raw.any { it.isDigit() }) return raw
        }
        return null
    }

    private fun cleanName(raw: String): String {
        var text = raw.trim()
            .replace("*", "")
            .replace(Regex("\\s+"), "")
            .removePrefix("课程名称：")
            .removePrefix("课程名称:")
        text = LEADING_PERIOD.replace(text, "")
        text = text.substringBefore("周数").substringBefore("周次")
            .substringBefore("教师").substringBefore("老师")
            .substringBefore("地点").substringBefore("教室").trim()
        return text.trim(' ', '：', ':', '/', '、', ',', '，')
    }

    private fun cleanField(raw: String?): String {
        if (raw == null) return ""
        return raw.trim()
            .replace(TRAILING_DETAIL, "")
            .trim(' ', '：', ':', '/', '、', ',', '，', ';', '；')
            .take(40)
    }

    private fun weekdayName(day: Int): String = listOf("一", "二", "三", "四", "五", "六", "日").getOrElse(day) { "?" }

    private fun OcrStructureIssue.toImportIssue(): ImportIssue {
        val severity = when (code) {
            OcrStructureCode.NO_WEEKDAY_HEADER, OcrStructureCode.NO_PERIOD_LABELS -> IssueSeverity.CONFIRM
            OcrStructureCode.LIST_FALLBACK, OcrStructureCode.NO_COURSE_BLOCK -> IssueSeverity.WARNING
            OcrStructureCode.MULTI_LINE_HEADER -> IssueSeverity.WARNING
        }
        return ImportIssue(severity, "OCR_" + code.name, message)
    }

    private val TEXT_WEEK_MARKER = Regex("周数|周次")
}
