package com.kexu.app.data.imports

import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportSource
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * 通用 HTML 课表解析：读取 table 结构、rowspan/colspan，按“星期表头列 + 节次行”映射单元格。
 * 适用于教务系统导出的 HTML 网格课表；内容格式按“课程名 + 周数/地点/教师”文本解析。
 */
object HtmlTimetableImporter {

    private val WEEKDAY = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天")
    private val PERIOD = Regex("^(\\d{1,2})\\s*[-—–~]\\s*(\\d{1,2})$")

    fun import(html: String): List<CourseImportRecord> {
        val document = Jsoup.parse(html)
        val table = document.select("table")
            .maxByOrNull { table -> table.select("td,th").count { cell -> WEEKDAY.any { cell.text().contains(it) } } }
            ?: return emptyList()
        val grid = expand(table)
        if (grid.isEmpty()) return emptyList()

        val headerRow = grid.indexOfFirst { row -> row.any { cell -> WEEKDAY.any { cell.text.contains(it) } } }
        if (headerRow < 0) return emptyList()
        val dayByColumn = mutableMapOf<Int, Int>()
        grid[headerRow].forEachIndexed { col, cell ->
            val text = cell.text.replace(" ", "")
            val idx = WEEKDAY.indexOfFirst { text.contains(it) }
            if (idx >= 0) dayByColumn[col] = if (idx == 7) 6 else idx
        }

        val records = mutableListOf<CourseImportRecord>()
        grid.forEachIndexed { rowIndex, row ->
            if (rowIndex <= headerRow) return@forEachIndexed
            val periodText = row.getOrNull(0)?.text?.trim().orEmpty()
            val period = PERIOD.matchEntire(periodText)?.let { m ->
                val s = m.groupValues[1].toIntOrNull() ?: return@let null
                val e = m.groupValues[2].toIntOrNull() ?: return@let null
                if (s < 1 || e < s) null else s to e
            } ?: return@forEachIndexed
            row.forEachIndexed { col, cell ->
                val day = dayByColumn[col] ?: return@forEachIndexed
                records += parseCell(cell.text, day, period)
            }
        }
        return records.distinctBy { listOf(it.courseName, it.dayOfWeek, it.startSection, it.endSection, it.weeks, it.location, it.teacher).joinToString("|") }
    }

    private fun parseCell(raw: String, day: Int, period: Pair<Int, Int>): List<CourseImportRecord> {
        val text = raw.replace("\r", "").trim()
        if (text.isBlank() || !text.contains("周数")) return emptyList()
        val matches = Regex("周数[:：]").findAll(text).toList()
        return matches.mapIndexedNotNull { index, match ->
            val start = match.range.first
            val end = matches.getOrNull(index + 1)?.range?.first ?: text.length
            val beforeStart = if (index == 0) 0 else matches[index - 1].range.last + 1
            val before = text.substring(beforeStart.coerceAtMost(start), start)
            val name = before.lines().lastOrNull { it.isNotBlank() }?.trim()
                ?.replace("*", "")?.replace(Regex("^\\d+\\s*[-—–~]\\s*\\d+\\s*"), "")?.trim()
            if (name.isNullOrBlank()) return@mapIndexedNotNull null
            val detail = text.substring(start, end)
            val weeksRaw = Regex("周数[:：]\\s*([^/\\n]+)").find(detail)?.groupValues?.get(1)?.trim()
            val rule = weeksRaw?.let { WeekRuleParser.parse(it) }
            val teacher = Regex("教师[:：]\\s*([^/\\n]+)").find(detail)?.groupValues?.get(1)?.trim().orEmpty()
            val room = Regex("地点[:：]\\s*([^/\\n]+)").find(detail)?.groupValues?.get(1)?.trim()
                ?: Regex("教室[:：]\\s*([^/\\n]+)").find(detail)?.groupValues?.get(1)?.trim().orEmpty()
            CourseImportRecord(
                courseName = name,
                teacher = teacher,
                location = room,
                dayOfWeek = day,
                startSection = period.first,
                endSection = period.second,
                weeks = rule?.weeks ?: emptySet(),
                originalWeekExpression = weeksRaw,
                source = ImportSource.HTML,
                weeksError = rule?.error,
                evidence = listOf("HTML 表格单元格映射"),
                confidence = listOf(rule?.isValid == true, teacher.isNotBlank(), room.isNotBlank()).count { it } * 20 + 40
            )
        }
    }

    private data class Cell(val text: String, val rowspan: Int, val colspan: Int)

    /** rowspan/colspan 展开成二维网格。 */
    private fun expand(table: Element): List<List<Cell>> {
        val rows = table.select("tr")
        val grid = mutableListOf<MutableList<Cell?>>()
        rows.forEachIndexed { r, tr ->
            while (grid.size <= r) grid.add(mutableListOf())
            var c = 0
            tr.children().forEach { td ->
                if (td.tagName() != "td" && td.tagName() != "th") return@forEach
                while (grid[r].getOrNull(c) != null) c++
                val cell = Cell(
                    td.text().replace("\u00a0", " ").trim(),
                    td.attr("rowspan").toIntOrNull()?.coerceAtLeast(1) ?: 1,
                    td.attr("colspan").toIntOrNull()?.coerceAtLeast(1) ?: 1
                )
                for (dr in 0 until cell.rowspan) {
                    while (grid.size <= r + dr) grid.add(mutableListOf())
                    for (dc in 0 until cell.colspan) {
                        while (grid[r + dr].size <= c + dc) grid[r + dr].add(null)
                        grid[r + dr][c + dc] = cell
                    }
                }
                c += cell.colspan
            }
        }
        return grid.map { row -> row.map { it ?: Cell("", 1, 1) } }
    }
}
