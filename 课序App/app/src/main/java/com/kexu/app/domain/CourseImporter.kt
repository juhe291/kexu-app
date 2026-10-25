package com.kexu.app.domain

import java.time.LocalDate
import java.time.LocalTime

/** 导入文件解析出的课程与上课安排，导入前统一在预览里确认。 */
data class ImportedMeeting(
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val weeksCsv: String? = null,
    val parity: String? = null
)

data class ImportedCourse(
    val name: String,
    val teacher: String = "",
    val room: String = "",
    val meetings: List<ImportedMeeting> = emptyList()
)

data class ImportResult(
    val courses: List<ImportedCourse> = emptyList(),
    val errors: List<String> = emptyList(),
    val skipped: Int = 0
)

/**
 * 课程表导入：支持课程 CSV（可带表头）与 ICS 日历。
 * 解析不依赖 Android，便于单元测试。
 */
object CourseImporter {

    private val WEEKDAY_ALIASES = mapOf(
        "周一" to 0, "星期一" to 0, "礼拜一" to 0, "mon" to 0, "monday" to 0,
        "周二" to 1, "星期二" to 1, "礼拜二" to 1, "tue" to 1, "tuesday" to 1,
        "周三" to 2, "星期三" to 2, "礼拜三" to 2, "wed" to 2, "wednesday" to 2,
        "周四" to 3, "星期四" to 3, "礼拜四" to 3, "thu" to 3, "thursday" to 3,
        "周五" to 4, "星期五" to 4, "礼拜五" to 4, "fri" to 4, "friday" to 4,
        "周六" to 5, "星期六" to 5, "礼拜六" to 5, "sat" to 5, "saturday" to 5,
        "周日" to 6, "周天" to 6, "星期日" to 6, "星期天" to 6, "sun" to 6, "sunday" to 6
    )

    fun parseCsv(text: String, maxPeriods: Int = 12): ImportResult {
        val lines = text.lineSequence()
            .map { it.trim().removePrefix("\uFEFF") }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return ImportResult(errors = listOf("CSV 文件是空的"))
        val errors = mutableListOf<String>()
        val rows = lines.map { splitCsvLine(it) }
        val header = rows.firstOrNull()?.takeIf { cells ->
            val first = cells.firstOrNull()?.trim()?.lowercase() ?: return@takeIf false
            first.contains("课程") || first.contains("名称") || first.contains("科目") || first == "name"
        }
        val col = if (header != null) columnMap(header) else null
        val dataRows = if (header != null) rows.drop(1) else rows

        val grouped = LinkedHashMap<String, ImportedCourse>()
        dataRows.forEachIndexed { index, cells ->
            if (cells.all { it.isBlank() }) return@forEachIndexed
            val rowNo = if (header != null) index + 2 else index + 1
            val name = value(cells, col?.get("name") ?: 0)
            if (name.isBlank()) {
                errors += "第 $rowNo 行缺少课程名，已跳过"
                return@forEachIndexed
            }
            val teacher = value(cells, col?.get("teacher") ?: 1)
            val room = value(cells, col?.get("room") ?: 2)
            val weekdayRaw = value(cells, col?.get("weekday") ?: 3)
            val weekday = parseWeekday(weekdayRaw)
            if (weekday == null) {
                errors += "第 $rowNo 行星期格式无法识别：$weekdayRaw"
                return@forEachIndexed
            }
            val startRaw = value(cells, col?.get("start") ?: 4)
            val endRaw = value(cells, col?.get("end") ?: 5)
            val periodText = if (startRaw.contains('-')) startRaw else null
            val start = periodText?.substringBefore('-')?.trim()?.toIntOrNull()
                ?: startRaw.trim().toIntOrNull()
            val end = periodText?.substringAfter('-', "")?.trim()?.toIntOrNull()?.takeIf { it > 0 }
                ?: endRaw.trim().toIntOrNull()
                ?: start
            if (start == null || start < 1 || start > maxPeriods || end == null || end < start || end > maxPeriods) {
                errors += "第 $rowNo 行节次无效：$startRaw–$endRaw"
                return@forEachIndexed
            }
            val compact = periodText != null && col == null
            val weeksRaw = value(cells, col?.get("weeks") ?: if (compact) 5 else 6)
            val parityRaw = value(cells, col?.get("parity") ?: if (compact) 6 else 7)
            val weeks = parseWeeks(weeksRaw, parityRaw)
            val key = "$name|$teacher|$room"
            val existing = grouped[key]
            val meeting = ImportedMeeting(weekday, start, end, weeksCsv = weeks.first, parity = weeks.second)
            grouped[key] = if (existing == null) {
                ImportedCourse(name, teacher, room, listOf(meeting))
            } else {
                existing.copy(meetings = existing.meetings + meeting)
            }
        }
        if (grouped.isEmpty() && errors.isEmpty()) errors += "没有解析出有效课程"
        return ImportResult(grouped.values.toList(), errors)
    }

    fun parseIcs(
        text: String,
        semesterStart: LocalDate,
        periods: List<Period>,
        maxPeriods: Int = periods.size
    ): ImportResult {
        val joined = StringBuilder()
        text.lineSequence().forEach { line ->
            if (line.startsWith(" ") || line.startsWith("\t")) {
                joined.append(line.substring(1))
            } else {
                if (joined.isNotEmpty()) joined.append('\n')
                joined.append(line)
            }
        }
        val lines = joined.toString().split('\n')
        val errors = mutableListOf<String>()
        val grouped = LinkedHashMap<String, ImportedCourse>()
        var inEvent = false
        var summary = ""
        var location = ""
        var description = ""
        var startAt: LocalDate? = null
        var startMin = -1
        var endMin = -1
        var byDays: List<Int> = emptyList()
        var until: LocalDate? = null
        var count = 0

        fun flush() {
            if (!inEvent) return
            inEvent = false
            val name = summary.trim()
            if (name.isBlank() || startAt == null || startMin < 0) return
            val days = if (byDays.isNotEmpty()) byDays else listOf(KexuCore.weekdayIndex(startAt!!))
            val startPeriod = periods.indexOfFirst { p -> startMin >= p.fromMin && startMin < p.toMin } + 1
            val endPeriod = periods.indexOfFirst { p -> endMin > p.fromMin && endMin <= p.toMin } + 1
            if (startPeriod <= 0 || endPeriod <= 0) {
                errors += "「$name」的上课时间不在节次范围内，已跳过"
                return
            }
            val weeks = buildWeeks(semesterStart, startAt!!, until, count)
            val teacher = description.substringAfter("教师", "").substringBefore('\n').trim(' ', '：', ':')
            days.forEach { day ->
                val key = "$name|$teacher|$location"
                val meeting = ImportedMeeting(day, startPeriod, endPeriod.coerceAtLeast(startPeriod), weeksCsv = weeks)
                val existing = grouped[key]
                grouped[key] = if (existing == null) {
                    ImportedCourse(name, teacher, location, listOf(meeting))
                } else {
                    existing.copy(meetings = existing.meetings + meeting)
                }
            }
        }

        lines.forEach { raw ->
            val line = raw.trim()
            when {
                line == "BEGIN:VEVENT" -> {
                    inEvent = true; summary = ""; location = ""; description = ""
                    startAt = null; startMin = -1; endMin = -1; byDays = emptyList(); until = null; count = 0
                }
                line == "END:VEVENT" -> flush()
                !inEvent -> Unit
                line.startsWith("SUMMARY") -> summary = line.substringAfter(':', "").replace("\\,", ",")
                line.startsWith("LOCATION") -> location = line.substringAfter(':', "")
                line.startsWith("DESCRIPTION") -> description = line.substringAfter(':', "").replace("\\n", "\n")
                line.startsWith("DTSTART") -> {
                    val parsed = parseIcsDateTime(line)
                    startAt = parsed.first
                    startMin = parsed.second
                }
                line.startsWith("DTEND") -> endMin = parseIcsDateTime(line).second
                line.startsWith("RRULE") -> {
                    val rule = line.substringAfter(':', "")
                    byDays = rule.split(';').firstOrNull { it.startsWith("BYDAY=") }
                        ?.removePrefix("BYDAY=")
                        ?.split(',')
                        ?.mapNotNull { code -> icsWeekday(code.trim()) }
                        ?: emptyList()
                    until = rule.split(';').firstOrNull { it.startsWith("UNTIL=") }
                        ?.removePrefix("UNTIL=")?.take(8)?.toLocalDateOrNull()
                    count = rule.split(';').firstOrNull { it.startsWith("COUNT=") }
                        ?.removePrefix("COUNT=")?.toIntOrNull() ?: 0
                }
            }
        }
        if (grouped.isEmpty() && errors.isEmpty()) errors += "没有在 ICS 中找到可导入的课程"
        return ImportResult(grouped.values.toList(), errors)
    }

    fun maxPeriodsOf(periods: List<Period>): Int = periods.size.coerceIn(1, 20)

    private fun buildWeeks(start: LocalDate, firstDate: LocalDate, until: LocalDate?, count: Int): String? {
        val firstWeek = KexuCore.weekIndexForDate(start, firstDate).coerceAtLeast(1)
        val lastWeek = when {
            until != null -> KexuCore.weekIndexForDate(start, until)
            count > 0 -> firstWeek + count - 1
            else -> null
        } ?: return null
        if (lastWeek < firstWeek) return null
        val weeks = (firstWeek..lastWeek).toList()
        return weeks.joinToString(",")
    }

    private fun parseIcsDateTime(line: String): Pair<LocalDate?, Int> {
        val value = line.substringAfter(':', "")
        val digits = value.filter { it.isDigit() }
        if (digits.length < 8) return null to -1
        val date = runCatching {
            LocalDate.of(digits.substring(0, 4).toInt(), digits.substring(4, 6).toInt(), digits.substring(6, 8).toInt())
        }.getOrNull() ?: return null to -1
        if (digits.length >= 14) {
            val hour = digits.substring(8, 10).toIntOrNull() ?: 0
            val minute = digits.substring(10, 12).toIntOrNull() ?: 0
            return date to hour * 60 + minute
        }
        return date to -1
    }

    private fun icsWeekday(code: String): Int? = when (code.uppercase()) {
        "MO" -> 0; "TU" -> 1; "WE" -> 2; "TH" -> 3; "FR" -> 4; "SA" -> 5; "SU" -> 6
        else -> null
    }

    private fun String.toLocalDateOrNull(): LocalDate? = runCatching {
        LocalDate.of(substring(0, 4).toInt(), substring(4, 6).toInt(), substring(6, 8).toInt())
    }.getOrNull()

    fun parseWeekday(raw: String): Int? {
        val text = raw.trim().lowercase()
        if (text.isEmpty()) return null
        WEEKDAY_ALIASES[text]?.let { return it }
        val number = text.toIntOrNull()
        if (number != null && number in 1..7) return number - 1
        return null
    }

    /** 返回 (weeksCsv, parity)；支持 1-16、1,3,5、单周/双周。 */
    fun parseWeeks(weeksRaw: String, parityRaw: String): Pair<String?, String?> {
        val parityText = parityRaw.trim().lowercase()
        val parity = when {
            parityText.contains("单") || parityText.contains("odd") -> "odd"
            parityText.contains("双") || parityText.contains("even") -> "even"
            else -> null
        }
        val raw = weeksRaw.trim()
        if (raw.isEmpty()) return null to parity
        val weeks = sortedSetOf<Int>()
        raw.split(',', '，', '、', ' ').forEach { part ->
            val range = part.split('-', '~')
            if (range.size == 2) {
                val from = range[0].trim().toIntOrNull() ?: return@forEach
                val to = range[1].trim().toIntOrNull() ?: return@forEach
                if (from <= to) for (w in from..to.coerceAtMost(30)) weeks += w
            } else {
                part.trim().toIntOrNull()?.let { weeks += it }
            }
        }
        return weeks.joinToString(",").ifBlank { null } to parity
    }

    private fun columnMap(header: List<String>): Map<String, Int> {
        val map = mutableMapOf<String, Int>()
        header.forEachIndexed { index, raw ->
            val cell = raw.trim().lowercase()
            when {
                cell.contains("课程") || cell.contains("名称") || cell.contains("name") -> map["name"] = index
                cell.contains("教师") || cell.contains("老师") || cell.contains("teacher") -> map["teacher"] = index
                cell.contains("地点") || cell.contains("教室") || cell.contains("room") -> map["room"] = index
                cell.contains("开始") || cell.contains("起始") || cell.contains("start") -> map["start"] = index
                cell.contains("结束") || cell.contains("终止") || cell.contains("end") -> map["end"] = index
                cell.contains("周次") || cell.contains("周数") || cell.contains("weeks") -> map["weeks"] = index
                cell.contains("单双") || cell.contains("周类型") || cell.contains("parity") -> map["parity"] = index
                cell.contains("星期") || cell.contains("周几") || cell.contains("weekday") -> map["weekday"] = index
            }
        }
        return map
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        line.forEach { ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> {
                    result += current.toString(); current.clear()
                }
                else -> current.append(ch)
            }
        }
        result += current.toString()
        return result
    }

    private fun value(cells: List<String>, index: Int): String =
        cells.getOrNull(index)?.trim()?.removeSurrounding("\"") ?: ""
}
