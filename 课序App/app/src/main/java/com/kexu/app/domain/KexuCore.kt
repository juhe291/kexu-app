package com.kexu.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 课序纯逻辑核心：不依赖 Android / Compose，可直接用 JVM 单元测试覆盖。
 * 对应 V8 原型中的 CORE 区块（日期、教学周、单双周、节次布局、配色对比度、下一节课）。
 */
object KexuCore {

    const val PX_PER_MIN = 1.5f
    const val MIN_PERIODS = 4
    const val MAX_PERIODS = 20
    const val DAY_MS = 86_400_000L
    val WEEKDAY_CN = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    /** 与原型一致的 12 色课程色板。 */
    val PALETTE = listOf(
        "#2563EB", "#0D9488", "#7C3AED", "#D97706", "#16A34A", "#DB2777",
        "#0891B2", "#65A30D", "#4F46E5", "#EA580C", "#9333EA", "#475569"
    )

    private val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun defaultPeriods(): List<Period> = listOf(
        "08:30" to "09:15", "09:20" to "10:05", "10:15" to "11:00", "11:05" to "11:50",
        "14:00" to "14:45", "14:50" to "15:35", "15:45" to "16:30", "16:35" to "17:20",
        "18:30" to "19:15", "19:20" to "20:05", "20:15" to "21:00", "21:05" to "21:50"
    ).map { Period(it.first, it.second) }

    fun minutesOf(hhmm: String): Int {
        val parts = hhmm.trim().split(":")
        if (parts.size != 2) return -1
        val h = parts[0].toIntOrNull() ?: return -1
        val m = parts[1].toIntOrNull() ?: return -1
        if (h !in 0..23 || m !in 0..59) return -1
        return h * 60 + m
    }

    fun timeOf(minutes: Int): String = TIME_FMT.format(LocalTime.of(minutes / 60, minutes % 60))

    fun weekdayIndex(date: LocalDate): Int = (date.dayOfWeek.value - 1 + 7) % 7

    fun startOfWeek(date: LocalDate): LocalDate = date.minusDays(weekdayIndex(date).toLong())

    fun dateForWeekDay(startDate: LocalDate, week: Int, weekday: Int): LocalDate =
        startOfWeek(startDate).plusWeeks((week - 1).toLong()).plusDays(weekday.toLong())

    fun weekIndexForDate(startDate: LocalDate, date: LocalDate): Int {
        val weeks = java.time.temporal.ChronoUnit.WEEKS.between(startOfWeek(startDate), startOfWeek(date))
        return weeks.toInt() + 1
    }

    fun fmtCN(date: LocalDate): String = "${date.monthValue} 月 ${date.dayOfMonth} 日"

    fun monthLabel(date: LocalDate): String = "${date.year} 年 ${date.monthValue} 月"

    fun weekMonthLabel(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        return if (monday.month == sunday.month && monday.year == sunday.year) {
            "${monday.year} 年 ${monday.monthValue} 月"
        } else if (monday.year == sunday.year) {
            "${monday.year} 年 ${monday.monthValue} 月 — ${sunday.monthValue} 月"
        } else {
            "${monday.year} 年 ${monday.monthValue} 月 — ${sunday.year} 年 ${sunday.monthValue} 月"
        }
    }

    fun weekRangeLabel(monday: LocalDate): String {
        val sunday = monday.plusDays(6)
        return if (monday.year != sunday.year) {
            "${monday.year} 年 ${fmtCN(monday)} — ${sunday.year} 年 ${fmtCN(sunday)}"
        } else {
            "${fmtCN(monday)} — ${fmtCN(sunday)}"
        }
    }

    fun parityMatch(parity: String?, week: Int): Boolean = when (parity) {
        null, "", "all" -> true
        "odd" -> week % 2 == 1
        "even" -> week % 2 == 0
        else -> true
    }

    fun meetingOccurs(meeting: Meeting, week: Int, weekday: Int): Boolean {
        if (meeting.weekday != weekday) return false
        if (!parityMatch(meeting.parity, week)) return false
        val weeks = meeting.weeks ?: return true
        return week in weeks
    }

    fun coursesOnDay(courses: List<Course>, week: Int, weekday: Int): List<Pair<Course, Meeting>> {
        val out = ArrayList<Pair<Course, Meeting>>()
        for (course in courses) {
            for (meeting in course.meetings) {
                if (meetingOccurs(meeting, week, weekday)) out.add(course to meeting)
            }
        }
        return out.sortedBy { it.second.startPeriod }
    }

    /**
     * 应用单次例外后的某天课程：
     * - 停课（cancel）：原时间不再出现；
     * - 调课（move）：从原时间消失，按新的星期/节次/教室出现在目标位置；
     * - 没有例外的课保持原样。
     */
    fun effectiveCoursesOnDay(
        courses: List<Course>,
        exceptions: List<CourseException>,
        week: Int,
        weekday: Int
    ): List<CourseOccurrence> {
        if (courses.isEmpty()) return emptyList()
        val weekExceptions = exceptions.filter { it.week == week }
        val byKey = LinkedHashMap<String, CourseException>()
        weekExceptions.forEach { exception ->
            byKey[CourseException.occurrenceKey(exception.courseId, exception.week, exception.weekday, exception.startPeriod, exception.endPeriod)] = exception
        }
        val out = ArrayList<CourseOccurrence>()
        for (course in courses) {
            for (meeting in course.meetings) {
                if (!meetingOccurs(meeting, week, weekday)) continue
                val key = CourseException.occurrenceKey(course.id, week, meeting.weekday, meeting.startPeriod, meeting.endPeriod)
                val exception = byKey[key]
                if (exception != null) {
                    // 停课保留为“幽灵块”，让用户还能点开撤销；调课则从原时段消失
                    if (exception.isCancel) {
                        out += CourseOccurrence(
                            course = course,
                            meeting = meeting,
                            week = week,
                            weekday = weekday,
                            startPeriod = meeting.startPeriod,
                            endPeriod = meeting.endPeriod,
                            room = meeting.room ?: course.room,
                            exceptionId = exception.id,
                            cancelled = true,
                            note = exception.note
                        )
                    }
                    continue
                }
                out += CourseOccurrence(
                    course = course,
                    meeting = meeting,
                    week = week,
                    weekday = weekday,
                    startPeriod = meeting.startPeriod,
                    endPeriod = meeting.endPeriod,
                    room = meeting.room ?: course.room
                )
            }
        }
        for (exception in weekExceptions) {
            if (!exception.isMove || exception.newWeekday != weekday) continue
            val course = courses.firstOrNull { it.id == exception.courseId } ?: continue
            val meeting = course.meetings.firstOrNull {
                it.weekday == exception.weekday && it.startPeriod == exception.startPeriod &&
                    it.endPeriod == exception.endPeriod && meetingOccurs(it, week, exception.weekday)
            } ?: continue
            out += CourseOccurrence(
                course = course,
                meeting = meeting,
                week = week,
                weekday = weekday,
                startPeriod = exception.newStartPeriod ?: meeting.startPeriod,
                endPeriod = exception.newEndPeriod ?: meeting.endPeriod,
                room = exception.newRoom?.takeIf { it.isNotBlank() } ?: meeting.room ?: course.room,
                exceptionId = exception.id,
                moved = true,
                note = exception.note
            )
        }
        return out.sortedWith(compareBy({ it.startPeriod }, { it.course.name }))
    }

    /** 整周（周一~周日）应用单次例外后的课程，供课表渲染。 */
    fun effectiveCoursesOnWeek(courses: List<Course>, exceptions: List<CourseException>, week: Int): List<List<CourseOccurrence>> =
        (0..6).map { day -> effectiveCoursesOnDay(courses, exceptions, week, day) }

    /** 给某个「课次」找出对应例外（用于详情弹窗显示“已停课/已调课”）。 */
    fun exceptionFor(
        exceptions: List<CourseException>,
        courseId: String,
        week: Int,
        meeting: Meeting
    ): CourseException? = exceptions.firstOrNull {
        it.courseId == courseId && it.week == week &&
            it.weekday == meeting.weekday && it.startPeriod == meeting.startPeriod && it.endPeriod == meeting.endPeriod
    }

    data class PeriodRow(
        val index: Int,
        val from: String,
        val to: String,
        val fromMin: Int,
        val toMin: Int,
        val gapMin: Int,
        val gapPx: Float,
        val topPx: Float,
        val heightPx: Float,
        val gapLabel: String
    )

    data class PeriodLayout(
        val rows: List<PeriodRow>,
        val totalPx: Float,
        val dayStartMin: Int
    )

    fun compressGap(gapMin: Int): Float {
        if (gapMin <= 0) return 0f
        if (gapMin <= 10) return max(3f, gapMin * 0.5f)
        return min(30f, max(12f, 6f + gapMin * 0.18f))
    }

    fun gapLabel(gapMin: Int, fromMin: Int): String {
        if (gapMin < 20) return ""
        val hour = fromMin / 60
        return when {
            hour in 11..14 -> "午休"
            hour in 16..19 -> "晚餐"
            else -> "休息"
        }
    }

    fun periodLayout(periods: List<Period>): PeriodLayout {
        if (periods.isEmpty()) return PeriodLayout(emptyList(), 0f, 0)
        val dayStart = periods.first().fromMin
        var cursor = dayStart
        var top = 0f
        val rows = ArrayList<PeriodRow>(periods.size)
        periods.forEachIndexed { index, period ->
            val gapMin = if (index == 0) 0 else max(0, period.fromMin - cursor)
            val gapPx = compressGap(gapMin)
            top += gapPx
            val height = max(0, period.toMin - period.fromMin) * PX_PER_MIN
            rows.add(
                PeriodRow(
                    index = index,
                    from = period.from,
                    to = period.to,
                    fromMin = period.fromMin,
                    toMin = period.toMin,
                    gapMin = gapMin,
                    gapPx = gapPx,
                    topPx = top,
                    heightPx = height,
                    gapLabel = gapLabel(gapMin, period.fromMin)
                )
            )
            top += height
            cursor = period.toMin
        }
        return PeriodLayout(rows, top, dayStart)
    }

    fun meetingRect(periods: List<Period>, startPeriod: Int, endPeriod: Int): Pair<Float, Float> {
        val layout = periodLayout(periods)
        if (layout.rows.isEmpty()) return 0f to 0f
        val s = (startPeriod - 1).coerceIn(0, periods.lastIndex)
        val e = (endPeriod - 1).coerceIn(s, periods.lastIndex)
        val top = layout.rows[s].topPx
        val bottom = layout.rows[e].topPx + layout.rows[e].heightPx
        return top to max(bottom - top, 26f)
    }

    fun validatePeriods(periods: List<Period>): List<String> {
        val errors = ArrayList<String>()
        if (periods.size !in MIN_PERIODS..MAX_PERIODS) {
            return listOf("节次数量需在 $MIN_PERIODS 到 $MAX_PERIODS 之间")
        }
        periods.forEachIndexed { index, period ->
            val from = period.fromMin
            val to = period.toMin
            if (from < 0 || to < 0) errors.add("第 ${index + 1} 节时间格式无效")
            else {
                if (to > 24 * 60) errors.add("第 ${index + 1} 节需在一天之内")
                if (to <= from) errors.add("第 ${index + 1} 节的结束需晚于开始")
                if (index > 0 && from < periods[index - 1].toMin) {
                    errors.add("第 $index 与 ${index + 1} 节时间重叠")
                }
            }
        }
        return errors
    }

    fun overlaps(aStart: Int, aEnd: Int, bStart: Int, bEnd: Int): Boolean =
        aStart < bEnd && bStart < aEnd

    fun nextCourse(
        now: LocalDateTime,
        semester: Semester,
        courses: List<Course>,
        periods: List<Period>,
        exceptions: List<CourseException> = emptyList()
    ): NextCourse? {
        if (periods.isEmpty()) return null
        val today = now.toLocalDate()
        val nowMin = now.hour * 60 + now.minute
        for (offset in 0..8) {
            val day = today.plusDays(offset.toLong())
            val w = weekIndexForDate(semester.startDate, day)
            val wd = weekdayIndex(day)
            for (occurrence in effectiveCoursesOnDay(courses, exceptions, w, wd)) {
                if (occurrence.cancelled) continue
                val start = periods.getOrNull(occurrence.startPeriod - 1)?.fromMin ?: continue
                val end = periods.getOrNull(occurrence.endPeriod - 1)?.toMin ?: continue
                val startsIn = (offset * 1440 + start) - nowMin
                if (startsIn > 0) {
                    return NextCourse(
                        course = occurrence.course,
                        meeting = occurrence.meeting,
                        startMin = start,
                        endMin = end,
                        startsInMin = startsIn,
                        week = w,
                        weekday = wd,
                        startPeriod = occurrence.startPeriod,
                        endPeriod = occurrence.endPeriod,
                        moved = occurrence.moved,
                        room = occurrence.room
                    )
                }
            }
        }
        return null
    }

    /** 把一天中的分钟数映射到课表网格的 y 坐标（空档按压缩后的高度计算），用于显示任务块。 */
    fun minutesToY(periods: List<Period>, minutes: Int): Float {
        val layout = periodLayout(periods)
        if (layout.rows.isEmpty()) return 0f
        if (minutes <= layout.rows.first().fromMin) return 0f
        var prevBottom = 0f
        layout.rows.forEachIndexed { index, row ->
            if (minutes < row.fromMin) {
                val gapStart = if (index == 0) row.fromMin else layout.rows[index - 1].toMin
                val gapMin = (row.fromMin - gapStart).coerceAtLeast(1)
                val t = ((minutes - gapStart).toFloat() / gapMin).coerceIn(0f, 1f)
                return prevBottom + t * row.gapPx
            }
            if (minutes <= row.toMin) {
                return row.topPx + (minutes - row.fromMin) * PX_PER_MIN
            }
            prevBottom = row.topPx + row.heightPx
        }
        return layout.totalPx
    }

    fun defaultColor(index: Int): String = PALETTE[abs(index) % PALETTE.size]

    /** 直接以 hex 计算课程色块：浅底 + 可读文字色（对比度 ≥ 4.5）。 */
    fun toneFor(hex: String, dark: Boolean): Tone {
        val base = if (Regex("^#[0-9a-fA-F]{6}$").matches(hex)) hex else PALETTE[0]
        val ink = if (dark) "#EAF1FB" else "#0D1930"
        val surface = if (dark) "#152134" else "#FFFFFF"
        val bg = mixHex(base, surface, if (dark) 0.70f else 0.68f)
        val line = mixHex(base, surface, if (dark) 0.38f else 0.36f)
        var text = mixHex(base, ink, if (dark) 0.42f else 0.26f)
        var guard = 0
        while (contrastRatio(bg, text) < 4.5 && guard < 12) {
            text = mixHex(text, ink, 0.12f)
            guard++
        }
        return Tone(bg = bg, line = line, ink = text)
    }

    data class Tone(val bg: String, val line: String, val ink: String)

    fun hexToRgb(hex: String): Triple<Int, Int, Int> {
        val clean = hex.removePrefix("#")
        val value = clean.toLongOrNull(16) ?: return Triple(92, 127, 168)
        return Triple(((value shr 16) and 0xFF).toInt(), ((value shr 8) and 0xFF).toInt(), (value and 0xFF).toInt())
    }

    fun rgbToHex(r: Int, g: Int, b: Int): String = "#%02X%02X%02X".format(
        r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255)
    )

    fun mixHex(a: String, b: String, t: Float): String {
        val (ar, ag, ab) = hexToRgb(a)
        val (br, bg, bb) = hexToRgb(b)
        val k = t.coerceIn(0f, 1f)
        return rgbToHex(
            (ar + (br - ar) * k).toInt(),
            (ag + (bg - ag) * k).toInt(),
            (ab + (bb - ab) * k).toInt()
        )
    }

    private fun relLum(r: Int, g: Int, b: Int): Double {
        fun channel(v: Int): Double {
            val c = v / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b)
    }

    fun contrastRatio(a: String, b: String): Double {
        val (ar, ag, ab) = hexToRgb(a)
        val (br, bg, bb) = hexToRgb(b)
        val la = relLum(ar, ag, ab)
        val lb = relLum(br, bg, bb)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    fun readableInk(bgHex: String): String {
        val (r, g, b) = hexToRgb(bgHex)
        return if (relLum(r, g, b) > 0.42) "#0D1930" else "#EAF1FB"
    }

    fun formatDuration(minutes: Int?): String {
        if (minutes == null || minutes <= 0) return "未设置时长"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m 分钟"
            m == 0 -> "$h 小时"
            else -> "$h 小时 $m 分钟"
        }
    }

    fun countdownText(leftMs: Long): String {
        if (leftMs <= 0) return "即将开始"
        val min = (leftMs / 60000).toInt()
        if (min < 60) return "$min 分钟后"
        val h = min / 60
        val m = min % 60
        return if (h < 24) {
            if (m == 0) "$h 小时后" else "$h 小时 $m 分后"
        } else "${h / 24} 天后"
    }

    fun isoDate(date: LocalDate): String = date.format(DateTimeFormatter.ISO_LOCAL_DATE)
}
