package com.kexu.app.domain.imports

import com.kexu.app.domain.ImportedCourse
import com.kexu.app.domain.ImportedMeeting
import com.kexu.app.domain.Period
import com.kexu.app.domain.Semester

/** 导入来源：解析器与学校适配器都只依赖本文件中的统一模型。 */
enum class ImportSource { PDF, CSV, ICS, XLSX, IMAGE, HTML, JSON }

enum class IssueSeverity { FATAL, CONFIRM, WARNING }

data class ImportIssue(
    val severity: IssueSeverity,
    val code: String,
    val message: String,
    val recordIndex: Int? = null
)

/** 一条“课程 + 一次上课安排”的中间记录，保留原始信息与证据，便于预览和诊断。 */
data class CourseImportRecord(
    val courseName: String,
    val teacher: String = "",
    val location: String = "",
    val dayOfWeek: Int? = null,
    val startSection: Int? = null,
    val endSection: Int? = null,
    val weeks: Set<Int> = emptySet(),
    val originalWeekExpression: String? = null,
    val source: ImportSource,
    val sourcePage: Int? = null,
    val weeksError: String? = null,
    val evidence: List<String> = emptyList(),
    val confidence: Int = 0
) {
    val hasTime: Boolean
        get() = dayOfWeek != null && startSection != null && endSection != null
}

data class CourseImportResult(
    val source: ImportSource,
    val records: List<CourseImportRecord> = emptyList(),
    val issues: List<ImportIssue> = emptyList(),
    val schoolTemplate: String? = null,
    val rawTextLength: Int = 0,
    val elapsedMs: Long = 0
) {
    val fatalIssues: List<ImportIssue> get() = issues.filter { it.severity == IssueSeverity.FATAL }
    val confirmIssues: List<ImportIssue> get() = issues.filter { it.severity == IssueSeverity.CONFIRM }
    val warnings: List<ImportIssue> get() = issues.filter { it.severity == IssueSeverity.WARNING }
    val canImport: Boolean get() = fatalIssues.isEmpty() && records.isNotEmpty()
}

/**
 * 单个格式解析器的统一输出：候选记录 + 解析期问题。
 * 解析器不在此阶段做去重/冲突（交给 ImportValidator），也不丢弃任何非空行。
 */
data class RecordParseResult(
    val records: List<CourseImportRecord> = emptyList(),
    val issues: List<ImportIssue> = emptyList()
)

/** 所有格式解析器实现的统一接口；禁止在解析器里操作 UI 或 Room。 */
interface TimetableImporter {
    val source: ImportSource
    val displayName: String

    /** 在校验之前只负责把文件变成候选记录。必须在后台线程调用。 */
    suspend fun import(request: ImportRequest): CourseImportResult
}

data class ImportRequest(
    val bytes: ByteArray,
    val fileName: String,
    val semester: Semester,
    val periods: List<Period>,
    val maxPeriods: Int = periods.size
)

/** 学校/教务系统适配器：输入“结构恢复后的文档”，输出候选记录。 */
interface SchoolTimetableAdapter<D> {
    val id: String
    val displayName: String
    fun matches(document: D): Boolean
    fun adapt(document: D): List<CourseImportRecord>
    fun issues(): List<ImportIssue>
}

/** 文件类型检测：根据扩展名与文件头判断，避免只信扩展名。 */
object ImportFormatDetector {
    fun detect(fileName: String, header: ByteArray): ImportSource? {
        val name = fileName.lowercase()
        val head = header.take(8).map { it.toInt() and 0xFF }
        if (head.size >= 4 && head[0] == 0x25 && head[1] == 0x50 && head[2] == 0x44 && head[3] == 0x46) return ImportSource.PDF
        if (head.size >= 2 && head[0] == 0x50 && head[1] == 0x4B) return if (name.endsWith(".xlsx")) ImportSource.XLSX else null
        if (head.isNotEmpty() && head[0] == 0xFF && head.size >= 2 && head[1] == 0xD8) return ImportSource.IMAGE
        if (head.size >= 4 && head[0] == 0x89 && head[1] == 0x50 && head[2] == 0x4E && head[3] == 0x47) return ImportSource.IMAGE
        if (head.size >= 2 && head[0] == 0x47 && head[1] == 0x49) return ImportSource.IMAGE
        val text = header.toString(Charsets.UTF_8)
        if (text.contains("BEGIN:VCALENDAR") || name.endsWith(".ics")) return ImportSource.ICS
        if (name.endsWith(".csv")) return ImportSource.CSV
        if (name.endsWith(".json")) return ImportSource.JSON
        if (name.endsWith(".html") || name.endsWith(".htm") || text.contains("<table")) return ImportSource.HTML
        return null
    }
}

/** 校验 + 去重 + 冲突检查；所有格式共用。 */
object ImportValidator {

    fun validate(
        records: List<CourseImportRecord>,
        semester: Semester,
        maxPeriods: Int
    ): List<ImportIssue> {
        val issues = mutableListOf<ImportIssue>()
        val seen = mutableSetOf<String>()
        records.forEachIndexed { index, r ->
            if (r.courseName.isBlank()) {
                issues += ImportIssue(IssueSeverity.FATAL, "EMPTY_COURSE_NAME", "第 ${index + 1} 条记录缺少课程名", index)
            }
            val day = r.dayOfWeek
            if (day != null && day !in 0..6) {
                issues += ImportIssue(IssueSeverity.FATAL, "INVALID_DAY", "「${r.courseName}」星期无效", index)
            }
            val start = r.startSection
            val end = r.endSection
            if (r.hasTime) {
                if (start == null || start < 1 || end == null || end < start || end > maxPeriods) {
                    issues += ImportIssue(IssueSeverity.FATAL, "INVALID_SECTION", "「${r.courseName}」节次无效", index)
                }
            } else if (day != null || start != null || end != null) {
                issues += ImportIssue(IssueSeverity.CONFIRM, "INCOMPLETE_TIME", "「${r.courseName}」时间不完整，需要确认", index)
            }
            if (r.weeks.isEmpty()) {
                issues += ImportIssue(IssueSeverity.CONFIRM, "NO_WEEKS", "「${r.courseName}」缺少教学周，需要确认", index)
            } else {
                val outside = r.weeks.filter { it < 1 || it > semester.totalWeeks }
                if (outside.isNotEmpty()) {
                    issues += ImportIssue(IssueSeverity.WARNING, "WEEKS_OUT_OF_RANGE", "「${r.courseName}」周次超出学期范围：${outside.joinToString(",")}", index)
                }
            }
            if (r.teacher.isBlank()) {
                issues += ImportIssue(IssueSeverity.WARNING, "NO_TEACHER", "「${r.courseName}」缺少教师", index)
            }
            if (r.location.isBlank()) {
                issues += ImportIssue(IssueSeverity.WARNING, "NO_ROOM", "「${r.courseName}」缺少教室", index)
            }
            val key = listOf(r.courseName, r.teacher, r.location, r.dayOfWeek, r.startSection, r.endSection, r.weeks.sorted()).joinToString("|")
            if (!seen.add(key)) {
                issues += ImportIssue(IssueSeverity.WARNING, "DUPLICATE_RECORD", "「${r.courseName}」存在重复排课", index)
            }
        }
        val timed = records.withIndex().filter { it.value.hasTime && it.value.weeks.isNotEmpty() }
        for (i in timed.indices) {
            for (j in i + 1 until timed.size) {
                val a = timed[i].value
                val b = timed[j].value
                val sameDay = a.dayOfWeek == b.dayOfWeek
                val sectionOverlap = !(a.endSection!! < b.startSection!! || b.endSection!! < a.startSection!!)
                val weekOverlap = a.weeks.intersect(b.weeks).isNotEmpty()
                if (sameDay && sectionOverlap && weekOverlap) {
                    issues += ImportIssue(IssueSeverity.WARNING, "TIME_CONFLICT", "「${a.courseName}」与「${b.courseName}」时间冲突，可正常导入，课表会标记", timed[j].index)
                }
            }
        }
        return issues
    }

    /** 返回时间冲突的记录下标对；导入不阻止，只用于预览与课表标记。 */
    fun conflictPairs(records: List<CourseImportRecord>): List<Pair<Int, Int>> {
        val timed = records.withIndex().filter { it.value.hasTime && it.value.weeks.isNotEmpty() }
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in timed.indices) {
            for (j in i + 1 until timed.size) {
                val a = timed[i].value
                val b = timed[j].value
                val sameDay = a.dayOfWeek == b.dayOfWeek
                val sectionOverlap = !(a.endSection!! < b.startSection!! || b.endSection!! < a.startSection!!)
                val weekOverlap = a.weeks.intersect(b.weeks).isNotEmpty()
                if (sameDay && sectionOverlap && weekOverlap) pairs += timed[i].index to timed[j].index
            }
        }
        return pairs
    }

    fun dedupe(records: List<CourseImportRecord>): List<CourseImportRecord> =
        records.distinctBy { r -> listOf(r.courseName, r.teacher, r.location, r.dayOfWeek, r.startSection, r.endSection, r.weeks.sorted()).joinToString("|") }
}

/** 统一中间模型 → 现有 Room 写入模型；同课程多安排保留教室/周次差异。 */
object ImportMapper {
    fun toImportedCourses(records: List<CourseImportRecord>): List<ImportedCourse> {
        val grouped = LinkedHashMap<String, MutableList<ImportedMeeting>>()
        val meta = mutableMapOf<String, CourseImportRecord>()
        records.filter { it.hasTime }.forEach { r ->
            val key = listOf(r.courseName, r.teacher, r.location).joinToString("|")
            val meeting = ImportedMeeting(
                weekday = r.dayOfWeek!!,
                startPeriod = r.startSection!!,
                endPeriod = r.endSection!!,
                weeksCsv = r.weeks.takeIf { it.isNotEmpty() }?.sorted()?.joinToString(",")
            )
            grouped.getOrPut(key) { mutableListOf() }.add(meeting)
            meta[key] = r
        }
        return grouped.map { (key, meetings) ->
            val r = meta[key]!!
            ImportedCourse(
                name = r.courseName,
                teacher = r.teacher,
                room = r.location,
                meetings = meetings.distinctBy { listOf(it.weekday, it.startPeriod, it.endPeriod, it.weeksCsv) }
            )
        }
    }
}
