package com.kexu.app.data.imports

import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportIssue
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity
import com.kexu.app.domain.imports.RecordParseResult

/**
 * CSV 课表解析：把课程表 CSV 的每一行转成统一中间记录 [CourseImportRecord]。
 *
 * 与旧 CourseImporter.parseCsv 的列序、表头识别与节次/单双周口径保持一致，区别在于：
 * - 一行 = 一条记录（同一门课多次上课不合并，留给预览页展示与校验）；
 * - 任何含内容的非空数据行都不丢弃：课程名/星期/节次缺失或非法时字段留空并生成 [ImportIssue]，
 *   问题消息带原始行号，供预览页定位修正；
 * - 周次统一交给 [WeekRuleParser]，解析失败不编造周次，并把错误写入 weeksError。
 *
 * 纯 Kotlin + java.time，无 Android 依赖，可直接 JVM 单测。
 */
object CsvTimetableImporter {

    private const val KEY_NAME = "name"
    private const val KEY_TEACHER = "teacher"
    private const val KEY_ROOM = "room"
    private const val KEY_WEEKDAY = "weekday"
    private const val KEY_START = "start"
    private const val KEY_END = "end"
    private const val KEY_WEEKS = "weeks"
    private const val KEY_PARITY = "parity"

    /** 星期别名，与旧 CourseImporter.parseWeekday 保持一致；键为小写。 */
    private val WEEKDAY_ALIASES = mapOf(
        "周一" to 0, "星期一" to 0, "礼拜一" to 0, "mon" to 0, "monday" to 0,
        "周二" to 1, "星期二" to 1, "礼拜二" to 1, "tue" to 1, "tuesday" to 1,
        "周三" to 2, "星期三" to 2, "礼拜三" to 2, "wed" to 2, "wednesday" to 2,
        "周四" to 3, "星期四" to 3, "礼拜四" to 3, "thu" to 3, "thursday" to 3,
        "周五" to 4, "星期五" to 4, "礼拜五" to 4, "fri" to 4, "friday" to 4,
        "周六" to 5, "星期六" to 5, "礼拜六" to 5, "sat" to 5, "saturday" to 5,
        "周日" to 6, "周天" to 6, "星期日" to 6, "星期天" to 6, "sun" to 6, "sunday" to 6
    )

    private data class SectionParse(
        val start: Int?,
        val end: Int?,
        val missing: Boolean,
        val reason: String?
    )

    private data class WeekParse(
        val weeks: Set<Int>,
        val expression: String?,
        val error: String?
    )

    fun import(text: String, maxPeriods: Int): RecordParseResult {
        val rows = mutableListOf<Pair<Int, List<String>>>()
        text.split('\n').forEachIndexed { index, raw ->
            val line = raw.trimEnd('\r').trim().removePrefix("\uFEFF")
            if (line.isEmpty()) return@forEachIndexed
            rows += (index + 1) to splitCsvLine(line)
        }
        if (rows.isEmpty()) {
            return RecordParseResult(
                issues = listOf(
                    ImportIssue(IssueSeverity.FATAL, "CSV_EMPTY", "CSV 文件是空的，没有可解析的内容")
                )
            )
        }

        val headerCells = rows.first().second
        val header = headerCells.takeIf { isHeaderRow(it) }
        val columnMap = header?.let { columnMapOf(it) }
        val dataRows = if (header != null) rows.drop(1) else rows

        val records = mutableListOf<CourseImportRecord>()
        val issues = mutableListOf<ImportIssue>()

        dataRows.forEach { (lineNo, cells) ->
            // 纯占位行（例如只有逗号）视为空行，不属于“有内容的非空行”。
            if (cells.all { it.isBlank() }) return@forEach

            val recordIndex = records.size
            val name = value(cells, columnMap?.get(KEY_NAME) ?: 0)
            val teacher = value(cells, columnMap?.get(KEY_TEACHER) ?: 1)
            val location = value(cells, columnMap?.get(KEY_ROOM) ?: 2)
            val weekdayRaw = value(cells, columnMap?.get(KEY_WEEKDAY) ?: 3)
            val startRaw = value(cells, columnMap?.get(KEY_START) ?: 4)
            val endRaw = value(cells, columnMap?.get(KEY_END) ?: 5)

            // 无表头且开始节次写成 "3-4" 时，列序整体左移一列（与旧 CourseImporter 一致）。
            val compact = columnMap == null && normalizeDashes(startRaw).contains('-')
            val weeksRaw = value(cells, columnMap?.get(KEY_WEEKS) ?: if (compact) 5 else 6)
            val parityRaw = value(cells, columnMap?.get(KEY_PARITY) ?: if (compact) 6 else 7)

            val weekday = parseWeekday(weekdayRaw)
            val sections = parseSections(startRaw, endRaw, maxPeriods)
            val weekParse = parseWeeks(weeksRaw, parityRaw)

            if (name.isEmpty()) {
                issues += ImportIssue(
                    IssueSeverity.FATAL,
                    "CSV_MISSING_NAME",
                    "第 $lineNo 行缺少课程名，已保留该行，请在预览中补充",
                    recordIndex
                )
            }
            if (weekday == null) {
                if (weekdayRaw.isBlank()) {
                    issues += ImportIssue(
                        IssueSeverity.CONFIRM,
                        "CSV_BAD_WEEKDAY",
                        "第 $lineNo 行缺少星期，节次留空，请在预览中确认",
                        recordIndex
                    )
                } else {
                    issues += ImportIssue(
                        IssueSeverity.FATAL,
                        "CSV_BAD_WEEKDAY",
                        "第 $lineNo 行星期无法识别：$weekdayRaw，请在预览中修正",
                        recordIndex
                    )
                }
            }
            if (sections.missing) {
                issues += ImportIssue(
                    IssueSeverity.CONFIRM,
                    "CSV_BAD_SECTION",
                    "第 $lineNo 行缺少节次，请在预览中确认",
                    recordIndex
                )
            } else if (sections.reason != null) {
                issues += ImportIssue(
                    IssueSeverity.FATAL,
                    "CSV_BAD_SECTION",
                    "第 $lineNo 行${sections.reason}，请在预览中修正",
                    recordIndex
                )
            }
            if (weekParse.error != null) {
                issues += ImportIssue(
                    IssueSeverity.CONFIRM,
                    "CSV_BAD_WEEKS",
                    "第 $lineNo 行周次无法解析：${weekParse.expression ?: weeksRaw}（${weekParse.error}），请在预览中确认",
                    recordIndex
                )
            }

            records += CourseImportRecord(
                courseName = name,
                teacher = teacher,
                location = location,
                dayOfWeek = weekday,
                startSection = sections.start,
                endSection = sections.end,
                weeks = weekParse.weeks,
                originalWeekExpression = weekParse.expression,
                source = ImportSource.CSV,
                weeksError = weekParse.error,
                evidence = listOf("CSV 第 $lineNo 行"),
                confidence = confidence(weekday, sections, weekParse.weeks, teacher, location)
            )
        }

        if (records.isEmpty()) {
            issues += ImportIssue(
                IssueSeverity.FATAL,
                "CSV_NO_RECORD",
                "CSV 中没有可解析的课程行，请确认文件内容或换用模板"
            )
        }
        return RecordParseResult(records = records, issues = issues)
    }

    private fun parseWeekday(raw: String): Int? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        WEEKDAY_ALIASES[text]?.let { return it }
        val number = text.toIntOrNull()
        if (number != null && number in 1..7) return number - 1
        return null
    }

    private fun parseSections(startRaw: String, endRaw: String, maxPeriods: Int): SectionParse {
        val startText = normalizeDashes(startRaw).trim()
        val endText = normalizeDashes(endRaw).trim()
        if (startText.isEmpty() && endText.isEmpty()) {
            return SectionParse(null, null, missing = true, reason = null)
        }
        val combinedStart = startText.substringBefore('-').trim()
        val combinedEnd = if (startText.contains('-')) startText.substringAfter('-', "").trim() else ""
        val start = combinedStart.toIntOrNull()
        val endCandidate = combinedEnd.toIntOrNull() ?: endText.toIntOrNull()
        val end = endCandidate ?: start
        if (start == null || end == null || start < 1 || start > maxPeriods || end < start || end > maxPeriods) {
            val reason = "节次无效：${startRaw.trim()}–${endRaw.trim()}（有效范围 1–$maxPeriods）"
            return SectionParse(null, null, missing = false, reason = reason)
        }
        return SectionParse(start, end, missing = false, reason = null)
    }

    /**
     * 周次 = WeekRuleParser 解析结果 + 独立单双周列过滤。
     * originalWeekExpression 保留原文并把单双周并入（如 "1-16" + "单周" -> "1-16(单周)"），可再次被 WeekRuleParser 解析。
     */
    private fun parseWeeks(weeksRaw: String, parityRaw: String): WeekParse {
        val base = weeksRaw.trim()
        val parity = when {
            parityRaw.contains("单") || parityRaw.lowercase().contains("odd") -> "odd"
            parityRaw.contains("双") || parityRaw.lowercase().contains("even") -> "even"
            else -> null
        }
        var weeks: Set<Int> = emptySet()
        var error: String? = null
        if (base.isNotEmpty()) {
            val rule = WeekRuleParser.parse(base)
            weeks = rule.weeks
            error = rule.error
        }
        if (parity != null) {
            if (weeks.isNotEmpty()) {
                weeks = weeks.filter { week ->
                    if (parity == "odd") week % 2 == 1 else week % 2 == 0
                }.toSet()
                if (weeks.isEmpty() && error == null) error = "单双周与周次范围没有交集"
            } else if (base.isEmpty()) {
                error = "只有单双周标记、缺少周次范围"
            }
        }
        return WeekParse(weeks = weeks, expression = buildWeekExpression(base, parity), error = error)
    }

    private fun buildWeekExpression(base: String, parity: String?): String? {
        if (base.isEmpty() && parity == null) return null
        if (parity == null) return base
        val parityLabel = if (parity == "odd") "单周" else "双周"
        if (base.isEmpty()) return parityLabel
        val inlineParity = if (parity == "odd") base.contains("单") else base.contains("双")
        return if (inlineParity) base else "$base($parityLabel)"
    }

    private fun confidence(weekday: Int?, sections: SectionParse, weeks: Set<Int>, teacher: String, location: String): Int {
        var score = 20
        if (weekday != null) score += 20
        if (sections.start != null && sections.end != null) score += 20
        if (weeks.isNotEmpty()) score += 20
        if (teacher.isNotBlank()) score += 10
        if (location.isNotBlank()) score += 10
        return score
    }

    private fun isHeaderRow(cells: List<String>): Boolean {
        val first = cells.firstOrNull()?.trim()?.lowercase() ?: return false
        if (first.isEmpty()) return false
        return first.contains("课程") || first.contains("名称") || first.contains("科目") ||
            first == "name" || first.contains("course") || first == "subject"
    }

    private fun columnMapOf(header: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        header.forEachIndexed { index, raw ->
            val cell = raw.trim().lowercase()
            when {
                cell.contains("课程") || cell.contains("名称") || cell.contains("科目") ||
                    cell.contains("name") || cell.contains("course") || cell == "subject" -> map[KEY_NAME] = index

                cell.contains("教师") || cell.contains("老师") || cell.contains("teacher") ||
                    cell.contains("instructor") -> map[KEY_TEACHER] = index

                cell.contains("地点") || cell.contains("教室") || cell.contains("room") ||
                    cell.contains("location") || cell.contains("classroom") -> map[KEY_ROOM] = index

                cell.contains("开始") || cell.contains("起始") || cell.contains("start") -> map[KEY_START] = index

                cell.contains("结束") || cell.contains("终止") || cell.contains("end") -> map[KEY_END] = index

                cell.contains("周次") || cell.contains("周数") || cell.contains("教学周") ||
                    cell.contains("weeks") || cell == "week" -> map[KEY_WEEKS] = index

                cell.contains("单双") || cell.contains("周类型") || cell.contains("parity") ||
                    cell == "odd" || cell == "even" -> map[KEY_PARITY] = index

                cell.contains("星期") || cell.contains("周几") || cell.contains("weekday") ||
                    cell == "day" -> map[KEY_WEEKDAY] = index
            }
        }
        return map
    }

    /** 支持引号包裹的逗号与转义双引号；引号本身不进入字段值。 */
    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val ch = line[index]
            when {
                ch == '"' && inQuotes && index + 1 < line.length && line[index + 1] == '"' -> {
                    current.append('"')
                    index++
                }

                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result += current.toString()
                    current.clear()
                }

                else -> current.append(ch)
            }
            index++
        }
        result += current.toString()
        return result
    }

    private fun normalizeDashes(raw: String): String {
        val builder = StringBuilder(raw.length)
        raw.forEach { ch ->
            when (ch) {
                '—', '–', '－', '~', '～', '至', '到' -> builder.append('-')
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    private fun value(cells: List<String>, index: Int): String =
        cells.getOrNull(index)?.trim()?.removeSurrounding("\"") ?: ""
}
