package com.kexu.app.data.imports

import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportSource
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.time.LocalDate
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 中国地质大学（武汉）教务系统 XLSX 课表读取器（Android/Vanilla JVM 均可用，不依赖 POI）。
 * 这份 Excel 用合并单元格排版：A 列星期、B 列节次（数值 formats 成 m-d，如 46024 -> 1-2）、C 列详情。
 */
class CugXlsxImporter {

    data class Cell(val ref: String, val value: String, val type: String?)
    data class Row(val cells: Map<Int, Cell>)

    fun import(bytes: ByteArray): List<CourseImportRecord> {
        val (shared, sheetXml) = readZip(bytes)
        val rows = parseSheet(sheetXml, shared)
        val records = mutableListOf<CourseImportRecord>()
        var day: Int? = null
        var period: Pair<Int, Int>? = null
        rows.forEach { row ->
            val a = row.cells[0]?.value?.trim().orEmpty()
            val b = row.cells[1]?.value?.trim().orEmpty()
            val c = row.cells[2]?.value?.trim().orEmpty()
            if (a.contains("其他课程")) {
                records += parseOtherCourses(a, records.size + 1)
                return@forEach
            }
            if (a.contains("*:") || c.contains("*: 讲课")) return@forEach
            parseWeekday(a)?.let { day = it }
            if (b.isNotBlank()) period = periodFromSerial(b) ?: period
            if (c.isBlank() || day == null || period == null) return@forEach
            if (!c.contains("周数")) return@forEach
            val weeks = Regex("周数[:：]\\s*([^/]+)").find(c)?.groupValues?.get(1)?.let { WeekRuleParser.parse(it) }
            val teacher = Regex("教师[:：]\\s*([^/]+)").find(c)?.groupValues?.get(1)?.trim().orEmpty()
            val room = Regex("地点[:：]\\s*([^/]+)").find(c)?.groupValues?.get(1)?.trim().orEmpty()
            val className = Regex("教学班[:：]\\s*([^/]+)").find(c)?.groupValues?.get(1)?.trim().orEmpty()
            val name = cleanName(className)
            if (name.isBlank()) return@forEach
            records += CourseImportRecord(
                courseName = name,
                teacher = teacher,
                location = room,
                dayOfWeek = day,
                startSection = period!!.first,
                endSection = period!!.second,
                weeks = weeks?.weeks ?: emptySet(),
                originalWeekExpression = weeks?.raw,
                source = ImportSource.XLSX,
                sourcePage = null,
                weeksError = weeks?.error,
                evidence = listOf("匹配中国地质大学（武汉）XLSX 模板"),
                confidence = listOf(weeks?.isValid == true, teacher.isNotBlank(), room.isNotBlank()).count { it } * 20 + 40
            )
        }
        return records
    }

    /** B 列数值按 Excel 序列日期解释，月-日即节次起止（如 46024 -> 2026-01-02 -> 1-2）。 */
    fun periodFromSerial(value: String): Pair<Int, Int>? {
        val serial = value.toDoubleOrNull() ?: return null
        if (serial <= 0) return null
        return runCatching {
            val date = LocalDate.of(1899, 12, 30).plusDays(serial.toLong())
            val start = date.monthValue
            val end = date.dayOfMonth
            if (start in 1..12 && end in start..12) start to end else null
        }.getOrNull()
    }

    private fun parseWeekday(text: String): Int? {
        val names = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天")
        val idx = names.indexOfFirst { text.contains(it) }
        if (idx < 0) return null
        return if (idx == 7) 6 else idx
    }

    private fun cleanName(raw: String): String {
        if (raw.isBlank()) return ""
        val base = raw.replace(" ", "")
            .replace("\\n", "")
            .substringBefore('-')
            .substringBefore('（')
            .substringBefore('(')
        return base.trim()
    }

    private fun parseOtherCourses(a: String, startIndex: Int): List<CourseImportRecord> {
        val text = a.substringAfter("其他课程", "").removePrefix("：").removePrefix(":")
        return text.split(';', '；').mapNotNull { entry ->
            val e = entry.trim()
            if (e.length < 2) return@mapNotNull null
            val weekText = Regex("(\\d{1,2})-").find(e)?.groupValues?.get(1)
            val range = Regex("(\\d{1,2})-(\\d{1,2})周").find(e)
            val weeks = range?.let { WeekRuleParser.parse(it.value) }
            val namePart = e.substringBefore('/').substringBefore("(").substringBefore("（").trim()
            val name = namePart.replace(Regex("[\\u4e00-\\u9fa5]{2,3}$"), "").trim()
            if (name.isBlank()) return@mapNotNull null
            CourseImportRecord(
                courseName = name,
                weeks = weeks?.weeks ?: emptySet(),
                originalWeekExpression = range?.value,
                source = ImportSource.XLSX,
                evidence = listOf("其他课程/无固定排课时间，需人工确认"),
                confidence = 30
            )
        }
    }

    private fun readZip(bytes: ByteArray): Pair<List<String>, String> {
        var shared = emptyList<String>()
        var sheet = ""
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            generateSequence { zip.nextEntry }.forEach { entry ->
                when {
                    entry.name == "xl/sharedStrings.xml" ->
                        shared = parseSharedStrings(zip.readBytes())
                    entry.name == "xl/worksheets/sheet1.xml" ->
                        sheet = String(zip.readBytes(), Charsets.UTF_8)
                }
            }
        }
        if (sheet.isBlank()) throw IllegalArgumentException("XLSX 中找不到工作表")
        return shared to sheet
    }

    private fun parseSharedStrings(xml: ByteArray): List<String> {
        val doc = newDoc(xml)
        val list = mutableListOf<String>()
        val items = doc.getElementsByTagNameNS(NS, "si")
        for (i in 0 until items.length) {
            val element = items.item(i) as Element
            val texts = element.getElementsByTagNameNS(NS, "t")
            val sb = StringBuilder()
            for (j in 0 until texts.length) sb.append(texts.item(j).textContent)
            list += sb.toString()
        }
        return list
    }

    private fun parseSheet(xml: String, shared: List<String>): List<Row> {
        val doc = newDoc(xml.toByteArray(Charsets.UTF_8))
        val rows = mutableListOf<Row>()
        val rowNodes = doc.getElementsByTagNameNS(NS, "row")
        for (i in 0 until rowNodes.length) {
            val rowEl = rowNodes.item(i) as Element
            val cells = mutableMapOf<Int, Cell>()
            val cNodes = rowEl.getElementsByTagNameNS(NS, "c")
            for (j in 0 until cNodes.length) {
                val c = cNodes.item(j) as Element
                val ref = c.getAttribute("r")
                val col = columnIndex(ref)
                val type = c.getAttribute("t").ifBlank { null }
                val v = c.getElementsByTagNameNS(NS, "v").item(0)?.textContent ?: continue
                val value = if (type == "s") shared.getOrNull(v.toIntOrNull() ?: -1) ?: "" else v
                cells[col] = Cell(ref, value, type)
            }
            rows += Row(cells)
        }
        return rows
    }

    private fun newDoc(bytes: ByteArray) =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))

    private fun columnIndex(ref: String): Int {
        var n = 0
        ref.takeWhile { it.isLetter() }.forEach { ch -> n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    companion object {
        private const val NS = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    }
}
