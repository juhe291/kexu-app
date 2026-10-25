package com.kexu.app.pdf

import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportIssue
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity

/**
 * 中国地质大学（武汉）教务系统 PDF 课表适配器。
 * 版面：左列星期（纵向居中于每天的课程组）、第二列节次、第三列课程名、右侧详情（周数/地点/教师…）。
 * 采用“课程名行 → 最近周数块”锚定详情，避免按文本出现顺序错配字段。
 */
class CugWhTableAdapter {

    data class Result(val records: List<CourseImportRecord>, val issues: List<ImportIssue>, val pages: Int)

    private val WEEKDAY = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天")
    private val PERIOD = Regex("^(\\d{1,2})\\s*[-—–~]\\s*(\\d{1,2})$")

    fun adapt(layout: PdfDocumentLayout): Result {
        val records = mutableListOf<CourseImportRecord>()
        val issues = mutableListOf<ImportIssue>()
        var carriedDay: Int? = null
        layout.pages.forEach { page ->
            val width = if (page.rotation % 180 == 0) page.width else page.height
            val weekdayAnchors = page.words.mapNotNull { w ->
                val text = w.text.replace(" ", "")
                val idx = WEEKDAY.indexOfFirst { text.contains(it) }
                if (idx < 0) return@mapNotNull null
                val day = if (idx == 7) 6 else idx
                if (w.x > width * 0.15f) null else day to w.centerY
            }
            val periodAnchors = page.words.mapNotNull { w ->
                val m = PERIOD.matchEntire(w.text.trim()) ?: return@mapNotNull null
                if (w.x < width * 0.08f || w.x > width * 0.24f) return@mapNotNull null
                val s = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
                val e = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
                if (s < 1 || e < s) null else (s to e) to w.centerY
            }
            val nameWords = page.words.filter {
                it.centerX in width * 0.16f..width * 0.36f && it.text.isNotBlank()
            }
            val nameLines = groupByNameLine(nameWords).sortedBy { it.centerY }
            val detailWords = page.words.filter { it.centerX >= width * 0.34f }
            val weekRows = detailWords.filter { it.text.contains("周数") }.sortedBy { it.centerY }
            val pairByOrder = kotlin.math.abs(nameLines.size - weekRows.size) <= 2

            // 节次按 y 排序后，每当节次编号回退（如 9-10 后出现 1-2）即进入下一个星期
            val sortedPeriods = periodAnchors.sortedBy { it.second }
            val periodGroups = mutableListOf<MutableList<Pair<Pair<Int, Int>, Float>>>()
            var prevStart = Int.MAX_VALUE
            sortedPeriods.forEach { anchor ->
                val start = anchor.first.first
                val dayAnchors = weekdayAnchors.sortedBy { it.second }.map { it.first }
                if (periodGroups.isEmpty() || start <= prevStart) {
                    periodGroups += mutableListOf(anchor)
                } else {
                    periodGroups.last() += anchor
                }
                prevStart = start
            }
            val dayLabels = weekdayAnchors.sortedBy { it.second }.map { it.first }
            val groupDays: List<Int> = when {
                dayLabels.size == periodGroups.size -> dayLabels
                dayLabels.size < periodGroups.size -> {
                    val days = mutableListOf<Int>()
                    repeat(periodGroups.size - dayLabels.size) { days += (carriedDay ?: 0) }
                    days.addAll(dayLabels)
                    days
                }
                else -> dayLabels.take(periodGroups.size)
            }
            val periodsWithDay = periodGroups.flatMapIndexed { index, group ->
                val dayIdx = (groupDays.getOrElse(index) { 0 }).coerceIn(0, 6)
                group.map { Triple(it.first, it.second, dayIdx) }
            }

            nameLines.forEachIndexed { nameIndex, line ->
                val name = line.text.replace("*", "").replace(" ", "").trim()
                if (name.isBlank() || name.contains("周数")) return@forEach
                if (name.contains("课程设计") || name.contains("共") || name.contains(";")) {
                    records += parseOtherCourses(name, page.pageIndex + 1)
                    return@forEach
                }
                val y = line.centerY
                val anchor = periodsWithDay.minByOrNull { kotlin.math.abs(it.second - y) }
                val day = anchor?.third ?: carriedDay
                val period = anchor?.first
                val ownWeek = if (pairByOrder) {
                    weekRows.getOrNull(nameIndex)
                } else {
                    weekRows.filter { it.centerY >= y - 4f }.minByOrNull { it.centerY }
                        ?: weekRows.minByOrNull { kotlin.math.abs(it.centerY - y) }
                }
                val detail = if (ownWeek != null) {
                    val nextWeekY = weekRows.map { it.centerY }.filter { it > ownWeek.centerY + 2f }.minOrNull()
                        ?: (ownWeek.centerY + 42f)
                    detailWords.filter { it.centerY >= ownWeek.centerY - 6f && it.centerY < nextWeekY - 2f }
                        .sortedWith(compareBy({ it.centerY }, { it.x }))
                        .joinToString(" ") { it.text }
                } else ""
                records += buildRecord(name, day, period, detail, page.pageIndex + 1)
            }
            if (weekdayAnchors.isNotEmpty()) {
                carriedDay = weekdayAnchors.maxByOrNull { it.second }?.first ?: carriedDay
            }
        }
        val deduped = records.distinctBy {
            listOf(it.courseName, it.dayOfWeek, it.startSection, it.endSection, it.weeks.sorted(), it.location, it.teacher).joinToString("|")
        }
        if (deduped.isEmpty()) {
            issues += ImportIssue(IssueSeverity.FATAL, "NO_RECORD", "未识别到课程记录，可能不是中国地质大学（武汉）课表模板")
        }
        return Result(deduped, issues, layout.pages.size)
    }

    private data class NameLine(val text: String, val centerY: Float)

    private fun groupByNameLine(words: List<PdfWord>, tolerance: Float = 5f): List<NameLine> {
        val sorted = words.sortedBy { it.centerY }
        val groups = mutableListOf<MutableList<PdfWord>>()
        var lastY = Float.NaN
        sorted.forEach { w ->
            if (groups.isEmpty() || kotlin.math.abs(w.centerY - lastY) > tolerance) {
                groups += mutableListOf(w)
                lastY = w.centerY
            } else groups.last() += w
        }
        return groups.map { g ->
            val text = g.sortedBy { it.x }.joinToString("") { it.text.trim() }
            NameLine(text, g.map { it.centerY }.average().toFloat())
        }
    }

    private fun buildRecord(name: String, day: Int?, period: Pair<Int, Int>?, detail: String, page: Int): CourseImportRecord {
        val weeksRaw = Regex("周数[:：]\\s*([^/]+)").find(detail)?.groupValues?.get(1)?.trim()
        val rule = weeksRaw?.let { WeekRuleParser.parse(it) }
        val teacher = field(detail, "教师")
        val room = field(detail, "地点")
        val evidence = mutableListOf("匹配中国地质大学（武汉）模板", "第 $page 页")
        if (weeksRaw != null) evidence += "周次原文：$weeksRaw"
        if (day == null || period == null) evidence += "星期或节次缺失，需人工确认"
        return CourseImportRecord(
            courseName = name,
            teacher = teacher,
            location = room,
            dayOfWeek = day,
            startSection = period?.first,
            endSection = period?.second,
            weeks = rule?.weeks ?: emptySet(),
            originalWeekExpression = weeksRaw,
            source = ImportSource.PDF,
            sourcePage = page,
            weeksError = rule?.error,
            evidence = evidence,
            confidence = listOf(day != null, period != null, rule?.isValid == true, teacher.isNotBlank(), room.isNotBlank())
                .count { it } * 20
        )
    }

    /** 页尾“其他课程”：只有课程名/周数、没有明确星期节次，保留为待确认记录。 */
    private fun parseOtherCourses(text: String, page: Int): List<CourseImportRecord> {
        return text.split(';', '；').mapNotNull { entry ->
            val trimmed = entry.trim()
            if (trimmed.length < 2) return@mapNotNull null
            val weekText = Regex("(\\d{1,2})\\s*[-—–]\\s*(\\d{1,2})\\s*周").find(trimmed)?.value
            val rule = weekText?.let { WeekRuleParser.parse(it) }
            val namePart = trimmed.substringBefore("(").substringBefore("（").substringBefore("/").trim()
            val match = Regex("^(.+?)([\\u4e00-\\u9fa5]{2,3})?[（(]共").find(trimmed)
            val name = (match?.groupValues?.get(1) ?: namePart).trim().ifBlank { namePart }
            val teacher = match?.groupValues?.get(2)?.trim().orEmpty()
            CourseImportRecord(
                courseName = name,
                teacher = teacher,
                location = "",
                dayOfWeek = null,
                startSection = null,
                endSection = null,
                weeks = rule?.weeks ?: emptySet(),
                originalWeekExpression = weekText,
                source = ImportSource.PDF,
                sourcePage = page,
                weeksError = rule?.error,
                evidence = listOf("其他课程/无固定排课时间，需人工确认"),
                confidence = 40
            )
        }
    }

    private fun field(detail: String, key: String): String =
        Regex("$key[:：]\\s*([^/]+)").find(detail)?.groupValues?.get(1)?.trim().orEmpty()
}
