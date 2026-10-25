package com.kexu.app.data.imports

import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Period
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportIssue
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity
import com.kexu.app.domain.imports.RecordParseResult
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * ICS 日历解析：把 VEVENT 转成统一中间记录 [CourseImportRecord]。
 *
 * 覆盖 SUMMARY/LOCATION/DESCRIPTION/DTSTART/DTEND/RRULE(BYDAY/UNTIL/COUNT)/EXDATE 与折叠行；
 * 教学周按学期开始日期换算（无 RRULE 时取事件日期所在教学周，EXDATE 从周次中剔除）；
 * 时间用 [Period] 映射到节次，映射失败时保留记录、节次留空并给出带事件名的可诊断问题。
 *
 * 与旧 CourseImporter.parseIcs 对普通 RRULE 日历（周次/节次）保持兼容；区别是：
 * - 不再静默丢弃无法解析的事件或缺失课程名/日期的事件，而是保留记录并生成问题；
 * - UTC（Z 结尾）与 TZID 时间会换算到本地时区，未知时区按本地墙钟时间处理并给 WARNING；
 * - 支持 EXDATE、DTSTART 只有日期（全天事件）等旧实现未覆盖的情况。
 *
 * 纯 Kotlin + java.time，无 Android 依赖，可直接 JVM 单测。
 */
object IcsTimetableImporter {

    private data class Property(
        val name: String,
        val params: Map<String, String>,
        val value: String
    )

    private data class RawEvent(
        val line: Int,
        val properties: List<Property>,
        val closed: Boolean
    )

    private data class ParseProblem(
        val severity: IssueSeverity,
        val code: String,
        val message: String
    )

    private data class IcsDateTime(
        val date: LocalDate,
        val minuteOfDay: Int?
    )

    private data class RruleInfo(
        val raw: String?,
        val byDays: List<Int>,
        val badByDay: List<String>,
        val until: LocalDate?,
        val untilInvalid: Boolean,
        val count: Int,
        val freq: String?
    )

    fun import(text: String, semesterStart: LocalDate, periods: List<Period>): RecordParseResult {
        if (text.isBlank()) {
            return RecordParseResult(
                issues = listOf(
                    ImportIssue(IssueSeverity.FATAL, "ICS_EMPTY", "ICS 文件是空的，没有可解析的内容")
                )
            )
        }
        val events = scanEvents(unfold(text))
        if (events.isEmpty()) {
            return RecordParseResult(
                issues = listOf(
                    ImportIssue(IssueSeverity.FATAL, "ICS_NO_EVENT", "ICS 中没有找到 VEVENT 事件，请确认文件内容")
                )
            )
        }
        val records = mutableListOf<CourseImportRecord>()
        val issues = mutableListOf<ImportIssue>()
        events.forEach { event -> processEvent(event, semesterStart, periods, records, issues) }
        return RecordParseResult(records = records, issues = issues)
    }

    // ---------------------------------------------------------------- 事件扫描

    /** RFC 5545 折叠行：行首空格/制表符表示续上一行，拼接时去掉首个空白字符。 */
    private fun unfold(text: String): List<String> {
        val joined = StringBuilder()
        text.split('\n').forEach { raw ->
            val line = raw.trimEnd('\r')
            if ((line.startsWith(" ") || line.startsWith("\t")) && joined.isNotEmpty()) {
                joined.append(line.substring(1))
            } else {
                if (joined.isNotEmpty()) joined.append('\n')
                joined.append(line)
            }
        }
        return joined.toString().split('\n')
    }

    private fun scanEvents(lines: List<String>): List<RawEvent> {
        val events = mutableListOf<RawEvent>()
        var current: MutableList<Property>? = null
        var currentLine = 0
        lines.forEachIndexed { index, raw ->
            val line = raw.trim()
            when {
                line.equals("BEGIN:VEVENT", ignoreCase = true) -> {
                    current?.let { events += RawEvent(currentLine, it, closed = false) }
                    current = mutableListOf()
                    currentLine = index + 1
                }

                line.equals("END:VEVENT", ignoreCase = true) -> {
                    current?.let { events += RawEvent(currentLine, it, closed = true) }
                    current = null
                }

                else -> current?.let { properties ->
                    parseProperty(line)?.let { properties.add(it) }
                }
            }
        }
        current?.let { events += RawEvent(currentLine, it, closed = false) }
        return events
    }

    private fun parseProperty(line: String): Property? {
        if (line.isEmpty()) return null
        val colon = firstUnquotedIndex(line, ':')
        if (colon <= 0) return null
        val head = line.substring(0, colon)
        val value = line.substring(colon + 1)
        val parts = splitUnquoted(head, ';')
        val name = parts.firstOrNull()?.trim()?.uppercase() ?: return null
        if (name.isEmpty()) return null
        val params = mutableMapOf<String, String>()
        parts.drop(1).forEach { part ->
            val separator = part.indexOf('=')
            if (separator > 0) {
                val key = part.substring(0, separator).trim().uppercase()
                var paramValue = part.substring(separator + 1).trim()
                if (paramValue.length >= 2 && paramValue.startsWith("\"") && paramValue.endsWith("\"")) {
                    paramValue = paramValue.substring(1, paramValue.length - 1)
                }
                params[key] = paramValue
            }
        }
        return Property(name, params, value)
    }

    private fun firstUnquotedIndex(text: String, target: Char): Int {
        var inQuotes = false
        text.forEachIndexed { index, ch ->
            when {
                ch == '"' -> inQuotes = !inQuotes
                ch == target && !inQuotes -> return index
            }
        }
        return -1
    }

    private fun splitUnquoted(text: String, separator: Char): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        text.forEach { ch ->
            when {
                ch == '"' -> {
                    inQuotes = !inQuotes
                    current.append(ch)
                }

                ch == separator && !inQuotes -> {
                    parts += current.toString()
                    current.clear()
                }

                else -> current.append(ch)
            }
        }
        parts += current.toString()
        return parts
    }

    // ---------------------------------------------------------------- 事件解析

    private fun processEvent(
        event: RawEvent,
        semesterStart: LocalDate,
        periods: List<Period>,
        records: MutableList<CourseImportRecord>,
        issues: MutableList<ImportIssue>
    ) {
        val baseIndex = records.size
        fun report(severity: IssueSeverity, code: String, message: String) {
            issues += ImportIssue(severity, code, message, baseIndex)
        }

        val summaryRaw = event.properties.lastOrNull { it.name == "SUMMARY" }
            ?.value?.let { unescape(it) }?.trim().orEmpty()
        val summaryLabel = summaryRaw.ifBlank { "（无 SUMMARY）" }
        val location = event.properties.lastOrNull { it.name == "LOCATION" }
            ?.value?.let { unescape(it) }?.trim().orEmpty()
        val description = event.properties.lastOrNull { it.name == "DESCRIPTION" }
            ?.value?.let { unescape(it) }.orEmpty()
        val teacher = extractTeacher(description)

        if (!event.closed) {
            report(
                IssueSeverity.CONFIRM,
                "ICS_UNCLOSED_EVENT",
                "ICS 第 ${event.line} 行事件「$summaryLabel」缺少 END:VEVENT，已按事件结束处理"
            )
        }
        if (summaryRaw.isEmpty()) {
            report(
                IssueSeverity.FATAL,
                "ICS_MISSING_SUMMARY",
                "ICS 第 ${event.line} 行事件缺少 SUMMARY，课程名需在预览中确认"
            )
        }

        val startProperty = event.properties.lastOrNull { it.name == "DTSTART" }
        val endProperty = event.properties.lastOrNull { it.name == "DTEND" }
        val (start, startProblems) = parseIcsDateTime(startProperty, summaryLabel)
        val (end, endProblems) = parseIcsDateTime(endProperty, summaryLabel)
        (startProblems + endProblems).forEach { problem ->
            report(problem.severity, problem.code, problem.message)
        }
        if (startProperty == null) {
            report(
                IssueSeverity.FATAL,
                "ICS_MISSING_DTSTART",
                "ICS 第 ${event.line} 行事件「$summaryLabel」缺少 DTSTART，日期与节次需在预览中确认"
            )
        }

        val rrule = parseRrule(event.properties)
        if (rrule.badByDay.isNotEmpty()) {
            report(
                IssueSeverity.WARNING,
                "ICS_BAD_BYDAY",
                "事件「$summaryLabel」的 RRULE BYDAY 存在无法识别的值：${rrule.badByDay.joinToString(",")}，已回退到开始日期星期"
            )
        }
        if (rrule.untilInvalid) {
            report(
                IssueSeverity.CONFIRM,
                "ICS_BAD_UNTIL",
                "事件「$summaryLabel」的 RRULE UNTIL 无法解析，教学周需在预览中确认"
            )
        }
        if (rrule.freq != null && rrule.freq != "WEEKLY") {
            report(
                IssueSeverity.WARNING,
                "ICS_UNSUPPORTED_FREQ",
                "事件「$summaryLabel」的重复频率 ${rrule.freq} 不是 WEEKLY，仅按每周重复换算教学周"
            )
        }
        val exdates = parseExdates(event.properties)
        exdates.second.forEach { token ->
            report(
                IssueSeverity.WARNING,
                "ICS_BAD_EXDATE",
                "事件「$summaryLabel」的 EXDATE 无法解析：$token，已忽略该排除日期"
            )
        }

        // 上课星期：优先 BYDAY（多值展开为多条记录），否则取开始日期的星期。
        val days = when {
            rrule.byDays.isNotEmpty() -> rrule.byDays.distinct()
            start != null -> listOf(KexuCore.weekdayIndex(start.date))
            else -> emptyList()
        }

        // 教学周换算。
        var weeks = sortedSetOf<Int>()
        var weeksError: String? = null
        val startDate = start?.date
        val recurring = rrule.raw != null
        if (startDate == null) {
            // 无可用日期：教学周无法换算，已由缺失/非法 DTSTART 问题覆盖。
        } else if (!recurring) {
            val week = KexuCore.weekIndexForDate(semesterStart, startDate)
            if (week < 1) {
                weeksError = "日期 $startDate 在学期开始（$semesterStart）之前，无法确定教学周"
                report(IssueSeverity.CONFIRM, "ICS_BEFORE_SEMESTER", "事件「$summaryLabel」$weeksError，请在预览中确认")
            } else {
                weeks += week
            }
        } else {
            val firstWeek = KexuCore.weekIndexForDate(semesterStart, startDate).coerceAtLeast(1)
            when {
                rrule.until != null -> {
                    val lastWeek = KexuCore.weekIndexForDate(semesterStart, rrule.until)
                    if (lastWeek < firstWeek) {
                        weeksError = "重复规则结束日期（${rrule.until}）早于开始日期，无法换算教学周"
                        report(IssueSeverity.CONFIRM, "ICS_BAD_WEEKS_RANGE", "事件「$summaryLabel」$weeksError，请在预览中确认")
                    } else {
                        for (week in firstWeek..lastWeek) weeks += week
                    }
                }

                rrule.count > 0 -> {
                    for (index in 0 until rrule.count) weeks += firstWeek + index
                }

                else -> {
                    // 无结束条件的重复事件：不编造总周数，weeks 留空（映射层空集表示每周）。
                }
            }
        }

        // EXDATE：按学期开始日期换算成教学周后剔除。
        val excludedWeeks = mutableSetOf<Int>()
        exdates.first.forEach { date ->
            val week = KexuCore.weekIndexForDate(semesterStart, date)
            if (week < 1) {
                report(
                    IssueSeverity.WARNING,
                    "ICS_EXDATE_OUTSIDE_SEMESTER",
                    "事件「$summaryLabel」的 EXDATE $date 在学期开始之前，已忽略"
                )
            } else {
                excludedWeeks += week
            }
        }
        if (excludedWeeks.isNotEmpty() && weeks.isNotEmpty()) {
            val before = weeks.size
            weeks.removeAll(excludedWeeks)
            if (weeks.isEmpty() && before > 0) {
                report(
                    IssueSeverity.CONFIRM,
                    "ICS_WEEKS_EMPTY_AFTER_EXDATE",
                    "事件「$summaryLabel」的 EXDATE 排除了所有教学周，请在预览中确认"
                )
            }
        } else if (excludedWeeks.isNotEmpty() && recurring && rrule.until == null && rrule.count <= 0) {
            report(
                IssueSeverity.WARNING,
                "ICS_EXDATE_IGNORED",
                "事件「$summaryLabel」的重复规则没有结束日期，EXDATE 无法换算成教学周，请手动确认"
            )
        }

        // 时间 -> 节次映射；映射失败保留记录、节次留空。
        var startSection: Int? = null
        var endSection: Int? = null
        var sectionError: String? = null
        val startMinute = start?.minuteOfDay
        val endMinute = end?.minuteOfDay
        if (startMinute != null) {
            val mappedStart = sectionForStart(periods, startMinute)
            if (mappedStart > 0) {
                if (endMinute != null) {
                    val mappedEnd = sectionForEnd(periods, endMinute)
                    if (mappedEnd > 0) {
                        startSection = mappedStart
                        endSection = mappedEnd.coerceAtLeast(mappedStart)
                    } else {
                        sectionError = "结束时间 ${KexuCore.timeOf(endMinute)} 不在已配置的节次范围内"
                    }
                } else {
                    startSection = mappedStart
                    endSection = mappedStart
                    report(
                        IssueSeverity.CONFIRM,
                        "ICS_MISSING_DTEND",
                        "事件「$summaryLabel」缺少可解析的 DTEND，已按开始节次 $mappedStart 单节处理"
                    )
                }
            } else {
                sectionError = "上课时间 ${KexuCore.timeOf(startMinute)} 不在已配置的节次范围内"
            }
        } else if (start != null) {
            report(
                IssueSeverity.CONFIRM,
                "ICS_DATE_ONLY",
                "事件「$summaryLabel」只有日期（${start.date}）没有时间，节次需在预览中确认"
            )
        }
        if (sectionError != null) {
            startSection = null
            endSection = null
            report(
                IssueSeverity.CONFIRM,
                "ICS_SECTION_OUT_OF_RANGE",
                "事件「$summaryLabel」$sectionError，节次留空，请在预览中修正"
            )
        }

        val expression = weeksExpression(weeks)
        val evidence = buildList {
            add("ICS 第 ${event.line} 行 VEVENT")
            rrule.raw?.let { add("RRULE:$it") }
        }
        fun confidence(day: Int?): Int {
            var score = 40
            if (day != null) score += 10
            if (startSection != null) score += 20
            if (weeks.isNotEmpty()) score += 20
            if (teacher.isNotBlank()) score += 5
            if (location.isNotBlank()) score += 5
            return score
        }

        if (days.isEmpty()) {
            records += buildRecord(
                name = summaryRaw,
                teacher = teacher,
                location = location,
                day = null,
                startSection = startSection,
                endSection = endSection,
                weeks = weeks,
                expression = expression,
                weeksError = weeksError,
                evidence = evidence,
                confidence = confidence(null)
            )
        } else {
            days.forEach { day ->
                records += buildRecord(
                    name = summaryRaw,
                    teacher = teacher,
                    location = location,
                    day = day,
                    startSection = startSection,
                    endSection = endSection,
                    weeks = weeks,
                    expression = expression,
                    weeksError = weeksError,
                    evidence = evidence,
                    confidence = confidence(day)
                )
            }
        }
    }

    private fun buildRecord(
        name: String,
        teacher: String,
        location: String,
        day: Int?,
        startSection: Int?,
        endSection: Int?,
        weeks: Set<Int>,
        expression: String?,
        weeksError: String?,
        evidence: List<String>,
        confidence: Int
    ): CourseImportRecord = CourseImportRecord(
        courseName = name,
        teacher = teacher,
        location = location,
        dayOfWeek = day,
        startSection = startSection,
        endSection = endSection,
        weeks = weeks,
        originalWeekExpression = expression,
        source = ImportSource.ICS,
        weeksError = weeksError,
        evidence = evidence,
        confidence = confidence
    )

    // ---------------------------------------------------------------- 时间解析

    private fun parseIcsDateTime(
        property: Property?,
        summaryLabel: String
    ): Pair<IcsDateTime?, List<ParseProblem>> {
        val target = property ?: return null to emptyList()
        fun failure(reason: String): Pair<IcsDateTime?, List<ParseProblem>> {
            val problem = ParseProblem(
                severity = if (target.name == "DTSTART") IssueSeverity.FATAL else IssueSeverity.CONFIRM,
                code = "ICS_BAD_DATETIME",
                message = "事件「$summaryLabel」的 ${target.name} 无法解析：${target.value.trim()}（$reason），请手动确认"
            )
            return null to listOf(problem)
        }

        val value = target.value.trim()
        if (value.isEmpty()) return failure("值为空")
        val upper = value.uppercase()
        val digits = value.filter { it.isDigit() }
        if (digits.length < 8) return failure("日期位数不足")
        val date = runCatching {
            LocalDate.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt()
            )
        }.getOrNull() ?: return failure("日期不合法")
        val hasTime = upper.contains('T') || digits.length >= 12
        if (!hasTime) {
            return IcsDateTime(date, minuteOfDay = null) to emptyList()
        }
        val timeDigits = if (upper.contains('T')) {
            upper.substringAfter('T').filter { it.isDigit() }
        } else {
            digits.substring(8)
        }
        if (timeDigits.length < 4) return failure("时间位数不足")
        val hour = timeDigits.substring(0, 2).toIntOrNull() ?: return failure("小时不合法")
        val minute = timeDigits.substring(2, 4).toIntOrNull() ?: return failure("分钟不合法")
        if (hour !in 0..23 || minute !in 0..59) return failure("时间不合法")
        var localDateTime = LocalDateTime.of(date, LocalTime.of(hour, minute))
        val problems = mutableListOf<ParseProblem>()
        val tzid = target.params["TZID"]
        if (upper.endsWith("Z")) {
            // UTC 时间换算到本地时区。
            localDateTime = ZonedDateTime.of(localDateTime, ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.systemDefault())
                .toLocalDateTime()
        } else if (!tzid.isNullOrBlank()) {
            val zone = runCatching { ZoneId.of(tzid) }.getOrNull()
            if (zone == null) {
                problems += ParseProblem(
                    IssueSeverity.WARNING,
                    "ICS_UNKNOWN_TZID",
                    "事件「$summaryLabel」的时区「$tzid」无法识别，已按本地时间 ${KexuCore.timeOf(hour * 60 + minute)} 处理"
                )
            } else {
                localDateTime = ZonedDateTime.of(localDateTime, zone)
                    .withZoneSameInstant(ZoneId.systemDefault())
                    .toLocalDateTime()
            }
        }
        return IcsDateTime(
            date = localDateTime.toLocalDate(),
            minuteOfDay = localDateTime.hour * 60 + localDateTime.minute
        ) to problems
    }

    /** 开始时间落在哪一节的“开始区间”内（与旧 CourseImporter 同一口径）。 */
    private fun sectionForStart(periods: List<Period>, minute: Int): Int =
        periods.indexOfFirst { period -> minute >= period.fromMin && minute < period.toMin } + 1

    /** 结束时间落在哪一节的“结束区间”内（与旧 CourseImporter 同一口径）。 */
    private fun sectionForEnd(periods: List<Period>, minute: Int): Int =
        periods.indexOfFirst { period -> minute > period.fromMin && minute <= period.toMin } + 1

    // ---------------------------------------------------------------- RRULE/EXDATE

    private fun parseRrule(properties: List<Property>): RruleInfo {
        val property = properties.lastOrNull { it.name == "RRULE" }
            ?: return RruleInfo(null, emptyList(), emptyList(), null, false, 0, null)
        val raw = property.value.trim()
        val byDays = mutableListOf<Int>()
        val badByDay = mutableListOf<String>()
        var until: LocalDate? = null
        var untilInvalid = false
        var count = 0
        var freq: String? = null
        raw.split(';').forEach { segment ->
            val separator = segment.indexOf('=')
            if (separator <= 0) return@forEach
            val key = segment.substring(0, separator).trim().uppercase()
            val rawValue = segment.substring(separator + 1).trim()
            when (key) {
                "FREQ" -> freq = rawValue.uppercase()
                "BYDAY" -> rawValue.split(',')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { token ->
                        val day = icsWeekday(token)
                        if (day == null) {
                            badByDay += token
                        } else if (!byDays.contains(day)) {
                            byDays += day
                        }
                    }

                "UNTIL" -> {
                    until = parseCompactDate(rawValue)
                    if (until == null) untilInvalid = true
                }

                "COUNT" -> count = rawValue.toIntOrNull()?.coerceAtLeast(0) ?: 0
            }
        }
        return RruleInfo(raw, byDays, badByDay, until, untilInvalid, count, freq)
    }

    private fun parseExdates(properties: List<Property>): Pair<List<LocalDate>, List<String>> {
        val dates = mutableListOf<LocalDate>()
        val badTokens = mutableListOf<String>()
        properties.filter { it.name == "EXDATE" }.forEach { property ->
            property.value.split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { token ->
                    val date = parseCompactDate(token)
                    if (date == null) badTokens += token else dates += date
                }
        }
        return dates to badTokens
    }

    private fun icsWeekday(code: String): Int? = when (code.trim().uppercase().takeLast(2)) {
        "MO" -> 0
        "TU" -> 1
        "WE" -> 2
        "TH" -> 3
        "FR" -> 4
        "SA" -> 5
        "SU" -> 6
        else -> null
    }

    private fun parseCompactDate(text: String): LocalDate? {
        val digits = text.filter { it.isDigit() }
        if (digits.length < 8) return null
        return runCatching {
            LocalDate.of(
                digits.substring(0, 4).toInt(),
                digits.substring(4, 6).toInt(),
                digits.substring(6, 8).toInt()
            )
        }.getOrNull()
    }

    // ---------------------------------------------------------------- 文本处理

    private fun extractTeacher(description: String): String {
        if (description.isBlank()) return ""
        val explicit = Regex("(?:教师|老师)\\s*[:：]\\s*([^\\n/]+)")
            .find(description)
            ?.groupValues
            ?.get(1)
            ?.trim()
        if (!explicit.isNullOrBlank()) return explicit
        return description.substringAfter("教师", "").substringBefore('\n').trim(' ', '：', ':', '/')
    }

    /** 还原 RFC 5545 文本转义：\n、\,、\;、\\。 */
    private fun unescape(raw: String): String {
        val builder = StringBuilder(raw.length)
        var index = 0
        while (index < raw.length) {
            val ch = raw[index]
            if (ch == '\\' && index + 1 < raw.length) {
                when (val next = raw[index + 1]) {
                    'n', 'N' -> builder.append('\n')
                    ',', ';', '\\' -> builder.append(next)
                    else -> builder.append(ch).append(next)
                }
                index += 2
            } else {
                builder.append(ch)
                index++
            }
        }
        return builder.toString()
    }

    /** 连续周次压缩成区间表达式，如 1..15 -> "1-15"，1,3,5 -> "1,3,5"。 */
    private fun weeksExpression(weeks: Set<Int>): String? {
        val sorted = weeks.sorted()
        if (sorted.isEmpty()) return null
        val parts = mutableListOf<String>()
        var start = sorted.first()
        var previous = sorted.first()
        for (index in 1 until sorted.size) {
            val week = sorted[index]
            if (week == previous + 1) {
                previous = week
            } else {
                parts += if (start == previous) "$start" else "$start-$previous"
                start = week
                previous = week
            }
        }
        parts += if (start == previous) "$start" else "$start-$previous"
        return parts.joinToString(",")
    }
}
