package com.kexu.app.domain

/**
 * 教学周规则解析器（独立于文件格式与学校）。
 * 支持：1-16周 / 1-8周(单) / 2-16周(双) / 1-5周,7-11周 /
 * 1-3周(单),4-6周(双),7-14周 / 第1至8周 / 1、3、5、7周 / 16周。
 * 解析失败时保留原始字符串并给出可诊断错误，不编造周次。
 */
object WeekRuleParser {

    data class WeekRule(
        val weeks: Set<Int> = emptySet(),
        val raw: String = "",
        val error: String? = null
    ) {
        val isValid: Boolean get() = error == null && weeks.isNotEmpty()
        val csv: String? get() = weeks.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
    }

    private val FULL_WIDTH_DIGITS = mapOf(
        '０' to '0', '１' to '1', '２' to '2', '３' to '3', '４' to '4',
        '５' to '5', '６' to '6', '７' to '7', '８' to '8', '９' to '9'
    )

    fun parse(expression: String): WeekRule {
        val raw = expression.trim()
        if (raw.isBlank()) return WeekRule(raw = raw, error = "周次表达式为空")
        val normalized = normalize(raw)
        val weeks = sortedSetOf<Int>()
        var parsedAny = false
        normalized.split(',', ';', '/').forEach { segment ->
            val part = segment.trim()
            if (part.isEmpty()) return@forEach
            val parity = when {
                part.contains("单") -> 1
                part.contains("双") -> 0
                else -> -1
            }
            val numbers = Regex("\\d+").findAll(part).map { it.value.toInt() }.toList()
            if (numbers.isEmpty()) return@forEach
            val from = numbers.first()
            val to = if (numbers.size >= 2) numbers[1] else numbers.first()
            if (from < 1 || to < from || to > 30) {
                return WeekRule(raw = raw, error = "周次范围无效：$part")
            }
            parsedAny = true
            for (week in from..to) {
                when (parity) {
                    1 -> if (week % 2 == 1) weeks += week
                    0 -> if (week % 2 == 0) weeks += week
                    else -> weeks += week
                }
            }
        }
        if (!parsedAny || weeks.isEmpty()) return WeekRule(raw = raw, error = "无法解析周次表达式，请手动确认")
        return WeekRule(weeks = weeks, raw = raw)
    }

    /** 统一全角字符、中文连接词与分隔符，供词法解析使用。 */
    fun normalize(input: String): String {
        val sb = StringBuilder()
        input.forEach { ch ->
            when (val c = FULL_WIDTH_DIGITS[ch] ?: ch) {
                '第', '周', '教', '学', '业' -> Unit
                '（' -> sb.append('(')
                '）' -> sb.append(')')
                '，', '、', '；', '　' -> sb.append(',')
                '至', '到', '~', '—', '–', '－' -> sb.append('-')
                ' ', '\t', '\n' -> Unit
                '单' -> sb.append("单")
                '双' -> sb.append("双")
                else -> sb.append(c)
            }
        }
        return sb.toString()
            .replace("()", "")
            .replace("(单)".toRegex(), "(单)")
    }
}
