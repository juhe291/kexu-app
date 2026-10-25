package com.kexu.app.data.imports

import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportIssue
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity
import com.kexu.app.domain.imports.RecordParseResult
import org.json.JSONArray
import org.json.JSONObject

/**
 * 外部 AI / 其他学校课表模板的 JSON 导入（`kexu-timetable/v1`）。
 *
 * 结构（与 [com.kexu.app.domain.imports.ImportTemplates.jsonTemplate] 一致）：
 * ```
 * {
 *   "schema": "kexu-timetable/v1",
 *   "courses": [
 *     { "name": "...", "teacher": "...", "room": "...",
 *       "meetings": [ { "weekday": 1, "startPeriod": 1, "endPeriod": 2, "weeks": "1-16" } ] }
 *   ]
 * }
 * ```
 * - 允许被 Markdown 代码块或说明文字包裹，自动提取第一个完整 JSON 对象；
 * - 一门课程多个 meetings 生成多条记录；同一门课不同教室/不同周次不合并丢失；
 * - 非法字段保留记录并给出行号/课程序号诊断，不静默丢弃；不编造星期/节次/周次。
 */
object JsonTimetableImporter {

    const val SCHEMA = "kexu-timetable/v1"

    fun import(text: String, maxPeriods: Int): RecordParseResult {
        val issues = mutableListOf<ImportIssue>()
        val rawJson = extractJsonObject(text)
            ?: return RecordParseResult(
                issues = listOf(
                    ImportIssue(
                        IssueSeverity.FATAL, "JSON_NO_OBJECT",
                        "没有找到 JSON 对象：请粘贴完整模板内容（从 { 开始到 } 结束），或选择导出的 JSON 模板文件"
                    )
                )
            )
        val root = try {
            JSONObject(rawJson)
        } catch (e: Exception) {
            return RecordParseResult(
                issues = listOf(
                    ImportIssue(IssueSeverity.FATAL, "JSON_BROKEN", "JSON 解析失败：${e.message ?: "格式错误"}")
                )
            )
        }
        val schema = root.optString("schema").trim()
        if (schema.isNotBlank() && !schema.equals(SCHEMA, ignoreCase = true)) {
            issues += ImportIssue(
                IssueSeverity.WARNING, "JSON_UNKNOWN_SCHEMA",
                "schema 为「$schema」，不是当前支持的 $SCHEMA，仍按课程表模板尝试解析"
            )
        }
        val courses: JSONArray = root.optJSONArray("courses")
            ?: return RecordParseResult(
                issues = issues + ImportIssue(
                    IssueSeverity.FATAL, "JSON_NO_COURSES",
                    "模板缺少 courses 数组：请确认导出的是「课序课表导入模板」JSON"
                )
            )
        val records = mutableListOf<CourseImportRecord>()
        for (courseIndex in 0 until courses.length()) {
            val course = courses.optJSONObject(courseIndex)
            if (course == null) {
                issues += ImportIssue(
                    IssueSeverity.FATAL, "JSON_BAD_COURSE",
                    "courses 第 ${courseIndex + 1} 项不是对象，已跳过", courseIndex
                )
                continue
            }
            val name = course.optString("name").trim()
            val teacher = course.optString("teacher").trim()
            val room = course.optString("room").trim()
            if (name.isBlank()) {
                issues += ImportIssue(
                    IssueSeverity.FATAL, "JSON_MISSING_NAME",
                    "courses 第 ${courseIndex + 1} 项缺少课程名「name」", courseIndex
                )
            }
            val meetings = course.optJSONArray("meetings")
            if (meetings == null || meetings.length() == 0) {
                // 兼容「一门课只有一个上课安排、字段直接挂在课程上」的简化模板
                val record = buildRecord(course, name, teacher, room, courseIndex, null, maxPeriods, issues)
                if (record != null) records += record
                continue
            }
            for (meetingIndex in 0 until meetings.length()) {
                val meeting = meetings.optJSONObject(meetingIndex)
                if (meeting == null) {
                    issues += ImportIssue(
                        IssueSeverity.FATAL, "JSON_BAD_MEETING",
                        "「${name.ifBlank { "第 ${courseIndex + 1} 门课" }}」的第 ${meetingIndex + 1} 个 meetings 不是对象", courseIndex
                    )
                    continue
                }
                val record = buildRecord(meeting, name, teacher, room, courseIndex, meetingIndex, maxPeriods, issues)
                if (record != null) records += record
            }
        }
        if (records.isEmpty() && issues.none { it.severity == IssueSeverity.FATAL }) {
            issues += ImportIssue(IssueSeverity.FATAL, "JSON_NO_RECORD", "模板里没有可导入的课程")
        }
        return RecordParseResult(records, issues)
    }

    private fun buildRecord(
        source: JSONObject,
        name: String,
        teacher: String,
        room: String,
        courseIndex: Int,
        meetingIndex: Int?,
        maxPeriods: Int,
        issues: MutableList<ImportIssue>
    ): CourseImportRecord? {
        val label = "「${name.ifBlank { "第 ${courseIndex + 1} 门课" }}" +
            (meetingIndex?.let { " 第 ${it + 1} 个课次" } ?: "") + "」"
        val weekdayRaw = if (source.has("weekday")) source.optInt("weekday", -1) else -1
        var day: Int? = null
        if (weekdayRaw in 1..7) {
            day = weekdayRaw - 1
        } else if (weekdayRaw == 0) {
            // 兼容 0=周一 的旧写法，但给出提示要求核对
            day = 0
            issues += ImportIssue(
                IssueSeverity.WARNING, "JSON_WEEKDAY_ZERO",
                "$label weekday=0，已按「周一」处理；模板约定 weekday 用 1-7（1=周一）", courseIndex
            )
        } else if (weekdayRaw != -1) {
            issues += ImportIssue(
                IssueSeverity.FATAL, "JSON_BAD_WEEKDAY",
                "$label weekday=$weekdayRaw 无效，应为 1-7（1=周一）", courseIndex
            )
        }
        val startRaw = source.optInt("startPeriod", -1)
        val endRaw = source.optInt("endPeriod", -1)
        var start: Int? = null
        var end: Int? = null
        if (startRaw != -1 || endRaw != -1) {
            val s = startRaw
            val e = if (endRaw == -1) s else endRaw
            if (s in 1..maxPeriods && e in s..maxPeriods) {
                start = s
                end = e
            } else {
                issues += ImportIssue(
                    IssueSeverity.FATAL, "JSON_BAD_SECTION",
                    "$label 节次 $s-$e 无效（共 $maxPeriods 节）", courseIndex
                )
            }
        }
        if (day == null && start == null) {
            issues += ImportIssue(
                IssueSeverity.CONFIRM, "JSON_NO_TIME",
                "$label 缺少 weekday/startPeriod/endPeriod，星期与节次需要人工确认", courseIndex
            )
        }
        val weeksRaw = source.optString("weeks").trim()
        val parityRaw = source.optString("weekType").trim()
            .ifBlank { source.optString("parity").trim() }
        val expression = combineParity(weeksRaw, parityRaw)
        val rule = expression?.let { WeekRuleParser.parse(it) }
        if (expression != null && rule != null && !rule.isValid) {
            issues += ImportIssue(
                IssueSeverity.CONFIRM, "JSON_BAD_WEEKS",
                "$label 周次「$expression」解析失败，需要人工确认", courseIndex
            )
        }
        if (weeksRaw.isBlank()) {
            issues += ImportIssue(
                IssueSeverity.CONFIRM, "JSON_NO_WEEKS",
                "$label 缺少 weeks 教学周，需要人工确认", courseIndex
            )
        }
        val evidence = mutableListOf("JSON 模板 kexu-timetable/v1", "courses[$courseIndex]" + (meetingIndex?.let { " meetings[$it]" } ?: ""))
        if (expression != null) evidence += "周次原文：$expression"
        return CourseImportRecord(
            courseName = name,
            teacher = teacher,
            location = room,
            dayOfWeek = day,
            startSection = start,
            endSection = end,
            weeks = rule?.weeks ?: emptySet(),
            originalWeekExpression = expression,
            source = ImportSource.JSON,
            sourcePage = null,
            weeksError = rule?.error,
            evidence = evidence,
            confidence = listOf(day != null, start != null, rule?.isValid == true, teacher.isNotBlank(), room.isNotBlank())
                .count { it } * 18 + 10
        )
    }

    /** 把独立的单双周列折叠进周次表达式（"1-16" + "单" → "1-16(单)"）。 */
    private fun combineParity(weeks: String, parity: String): String? {
        if (weeks.isBlank()) return null
        if (parity.isBlank()) return weeks
        if (weeks.contains("单") || weeks.contains("双")) return weeks
        val marker = when {
            parity.contains("单") || parity.equals("odd", true) -> "单"
            parity.contains("双") || parity.equals("even", true) -> "双"
            else -> null
        } ?: return weeks
        return "$weeks($marker)"
    }

    /** 从夹杂说明文字或 Markdown 代码块的回答中提取第一个完整 JSON 对象（容忍字符串里的花括号）。 */
    fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until text.length) {
            val ch = text[index]
            if (inString) {
                when {
                    escaped -> escaped = false
                    ch == '\\' -> escaped = true
                    ch == '"' -> inString = false
                }
                continue
            }
            when (ch) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index + 1)
                }
            }
        }
        return null
    }
}
