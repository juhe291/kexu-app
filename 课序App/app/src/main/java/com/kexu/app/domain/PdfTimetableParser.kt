package com.kexu.app.domain

/**
 * 从课表 PDF 文本中抽课程（纯文本启发式，方便单元测试）。
 * 目前针对中国地质大学（武汉）教务系统 PDF 的文本块结构。
 */
object PdfTimetableParser {

    private val WEEKDAYS = listOf("星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日", "星期天")
    private val PERIOD_LINE = Regex("(?m)^\\s*(\\d{1,2})\\s*[-—–~]\\s*(\\d{1,2})\\s+(.{1,60}?)\\s*\\*")

    data class PdfParseResult(
        val courses: List<ImportedCourse> = emptyList(),
        val warnings: List<String> = emptyList(),
        val textLength: Int = 0
    )

    fun parse(text: String, maxPeriods: Int = 12): PdfParseResult {
        if (text.isBlank()) return PdfParseResult(warnings = listOf("PDF 中没有可提取的文字（可能是扫描件或空白页）"))
        val warnings = mutableListOf<String>()
        val groups = LinkedHashMap<String, MutableList<ImportedMeeting>>()
        var matched = 0
        sections(text).forEach { (day, body) ->
            PERIOD_LINE.findAll(body).forEach { m ->
                val start = m.groupValues[1].toIntOrNull() ?: return@forEach
                val end = m.groupValues[2].toIntOrNull() ?: return@forEach
                if (start < 1 || end < start || end > maxPeriods) return@forEach
                val rawName = m.groupValues[3].trim().replace("*", "").trim()
                if (rawName.isBlank()) return@forEach
                val info = nearestInfo(body, m.range.first)
                val weeks = parseWeeks(info) ?: run {
                    warnings += "「$rawName」未解析到周数，已按每周处理，请核对"
                    null
                }
                val teacher = Regex("教师[:：]\\s*([^/\\n]{1,20})").find(info)?.groupValues?.get(1)?.trim().orEmpty()
                val room = Regex("地点[:：]\\s*([^/\\n]{1,30})").find(info)?.groupValues?.get(1)?.trim().orEmpty()
                if (teacher.isBlank()) warnings += "「$rawName」未解析到教师"
                if (room.isBlank()) warnings += "「$rawName」未解析到地点"
                val key = "$rawName|$teacher|$room"
                val meeting = ImportedMeeting(day, start, end, weeksCsv = weeks)
                groups.getOrPut(key) { mutableListOf() }.add(meeting)
                matched++
            }
        }
        if (matched == 0) {
            return PdfParseResult(warnings = warnings + "未识别出课程，可能不是支持的课表模板", textLength = text.length)
        }
        val courses = groups.map { (key, meetings) ->
            val parts = key.split('|')
            ImportedCourse(
                name = parts.getOrElse(0) { "未命名课程" },
                teacher = parts.getOrElse(1) { "" },
                room = parts.getOrElse(2) { "" },
                meetings = meetings.distinctBy { it.weekday to it.startPeriod to it.weeksCsv }
            )
        }
        return PdfParseResult(courses, warnings, text.length)
    }

    /** 按「星期X」切分页面文本，返回 weekday(0-6) → 该天文本。 */
    private fun sections(text: String): List<Pair<Int, String>> {
        val hits = mutableListOf<Pair<Int, Int>>()
        WEEKDAYS.forEachIndexed { nameIndex, name ->
            var from = 0
            while (true) {
                val idx = text.indexOf(name, from)
                if (idx < 0) break
                val day = if (name == "星期天") 6 else nameIndex
                hits += day to idx
                from = idx + name.length
            }
        }
        val sorted = hits.sortedBy { it.second }
        val result = mutableListOf<Pair<Int, String>>()
        sorted.forEachIndexed { i, (day, idx) ->
            val end = sorted.getOrNull(i + 1)?.second ?: text.length
            result += day to text.substring(idx, end)
        }
        return result
    }

    /** 取离节次行最近的「周数:」所在信息段。 */
    private fun nearestInfo(body: String, position: Int, window: Int = 500): String {
        val indices = Regex("周数[:：]").findAll(body).map { it.range.first }.toList()
        if (indices.isEmpty()) return ""
        val nearest = indices.minByOrNull { kotlin.math.abs(it - position) } ?: return ""
        val start = if (nearest > position) nearest else position
        return body.substring(start.coerceAtLeast(0), (start + window).coerceAtMost(body.length))
    }

    /**
     * 解析周数字符串：1-5周,7-11周 / 5-7周(单) / 4-6周(双) / 16周 / 1-3周(单),4-5周,7-16周。
     * 展开为具体周次 CSV，从而混合单双周与不连续周都能正确保存。
     */
    fun parseWeeks(info: String): String? {
        val raw = Regex("周数[:：]\\s*([^/\\n]{1,120})").find(info)?.groupValues?.get(1) ?: return null
        return WeekRuleParser.parse(raw).csv
    }
}
