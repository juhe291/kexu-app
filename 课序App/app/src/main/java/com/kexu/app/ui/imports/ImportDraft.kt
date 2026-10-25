package com.kexu.app.ui.imports

import com.kexu.app.domain.ImportedCourse
import com.kexu.app.domain.Semester
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportIssue
import com.kexu.app.domain.imports.ImportMapper
import com.kexu.app.domain.imports.ImportValidator
import com.kexu.app.domain.imports.IssueSeverity

/** 导入预览的一行：原始记录 + 勾选状态 + 当前校验问题（时间冲突另用 [conflict] 标记）。 */
data class DraftRow(
    val record: CourseImportRecord,
    val selected: Boolean = true,
    val issues: List<ImportIssue> = emptyList(),
    val conflict: Boolean = false
) {
    val fatalIssues: List<ImportIssue> get() = issues.filter { it.severity == IssueSeverity.FATAL }
    val confirmIssues: List<ImportIssue> get() = issues.filter { it.severity == IssueSeverity.CONFIRM }
    val warningIssues: List<ImportIssue> get() = issues.filter { it.severity == IssueSeverity.WARNING }

    /** 存在 FATAL 的行不可勾选，必须先编辑修复。 */
    val selectable: Boolean get() = fatalIssues.isEmpty()

    /** 用于 UI 着色：取本行最严重的问题级别。 */
    val severity: IssueSeverity?
        get() = when {
            fatalIssues.isNotEmpty() -> IssueSeverity.FATAL
            confirmIssues.isNotEmpty() -> IssueSeverity.CONFIRM
            warningIssues.isNotEmpty() -> IssueSeverity.WARNING
            else -> null
        }
}

/** 预览顶部摘要：总条数 / 选中条数 / 致命 / 待确认 / 警告 / 冲突数。 */
data class ImportSummary(
    val total: Int = 0,
    val selected: Int = 0,
    val fatal: Int = 0,
    val confirm: Int = 0,
    val warning: Int = 0,
    val conflicts: Int = 0
)

/** 同“课程名 + 教师 + 地点”合并后的显示分组，rowIndices 指向 [ImportDraft.rows] 下标。 */
data class DraftGroup(
    val courseName: String,
    val teacher: String,
    val location: String,
    val rowIndices: List<Int>
) {
    val size: Int get() = rowIndices.size
}

/**
 * 可编辑的导入草稿：纯 Kotlin，不依赖 Android/Compose，可直接在 JVM 单测中验证。
 *
 * 校验范围：勾选记录作为一组校验（去重与时间冲突只在将要导入的记录之间判定）；
 * 未勾选行只单独校验自身字段问题，便于定位并修复 FATAL，不参与冲突判定。
 * 存在 FATAL 的行在 [revalidate] 后自动取消勾选，修复后才可重新勾选。
 */
class ImportDraft(
    initial: List<CourseImportRecord>,
    val semester: Semester,
    val maxPeriods: Int
) {

    private val items: MutableList<DraftRow> = initial.map { DraftRow(record = it) }.toMutableList()
    private var conflictCount: Int = 0

    init {
        revalidate()
    }

    /** 当前所有行（返回快照，避免外部直接改内部列表）。 */
    val rows: List<DraftRow> get() = items.toList()

    val summary: ImportSummary
        get() = ImportSummary(
            total = items.size,
            selected = items.count { it.selected },
            fatal = items.sumOf { it.fatalIssues.size },
            confirm = items.sumOf { it.confirmIssues.size },
            warning = items.sumOf { it.warningIssues.size },
            conflicts = conflictCount
        )

    /** 至少勾选一行，且勾选行中不含 FATAL，才允许写入课表。 */
    val canImport: Boolean
        get() = items.any { it.selected } && items.filter { it.selected }.all { it.selectable }

    /** 勾选/取消勾选；FATAL 行不可勾选（会被忽略）。 */
    fun toggleRow(index: Int) {
        val row = items.getOrNull(index) ?: return
        if (!row.selected && !row.selectable) return
        items[index] = row.copy(selected = !row.selected)
        revalidate()
    }

    /** 用编辑后的记录替换某一行，随后重新校验。 */
    fun updateRecord(index: Int, record: CourseImportRecord) {
        val row = items.getOrNull(index) ?: return
        items[index] = row.copy(record = record)
        revalidate()
    }

    /** 从草稿中删除一行，随后重新校验。 */
    fun deleteRow(index: Int) {
        if (index !in items.indices) return
        items.removeAt(index)
        revalidate()
    }

    /** 全选（跳过 FATAL 行）或全不选。 */
    fun selectAll(selected: Boolean) {
        items.indices.forEach { index ->
            val row = items[index]
            items[index] = row.copy(selected = selected && row.selectable)
        }
        revalidate()
    }

    /** 重新校验并按行归置问题；冲突只统计当前勾选行。 */
    fun revalidate() {
        val selectedIndices = items.indices.filter { items[it].selected }
        val selectedIndexSet = selectedIndices.toSet()
        val issuesByRow = HashMap<Int, MutableList<ImportIssue>>()
        // 勾选记录作为一组校验：去重与时间冲突只发生在将要导入的记录之间。
        ImportValidator.validate(selectedIndices.map { items[it].record }, semester, maxPeriods)
            .forEach { issue ->
                // 冲突统一由 conflictPairs 按勾选范围计算并标记，这里不重复挂到行上。
                if (issue.code == CODE_TIME_CONFLICT) return@forEach
                val index = issue.recordIndex?.let { selectedIndices.getOrNull(it) } ?: return@forEach
                issuesByRow.getOrPut(index) { mutableListOf() }.add(issue)
            }
        // 未勾选行只校验自身字段问题（便于发现并修复 FATAL），不参与去重与冲突判定。
        items.indices.filter { it !in selectedIndexSet }.forEach { index ->
            ImportValidator.validate(listOf(items[index].record), semester, maxPeriods)
                .forEach { issue ->
                    if (issue.recordIndex == null || issue.recordIndex == 0) {
                        issuesByRow.getOrPut(index) { mutableListOf() }.add(issue)
                    }
                }
        }
        val fatalIndices = items.indices.filter { index ->
            issuesByRow[index].orEmpty().any { it.severity == IssueSeverity.FATAL }
        }.toSet()
        // 出现 FATAL 的行自动取消勾选，冲突按取消后的实际勾选行计算。
        val effectiveSelected = selectedIndices.filter { it !in fatalIndices }
        val pairs = ImportValidator.conflictPairs(effectiveSelected.map { items[it].record })
        val conflictRows = mutableSetOf<Int>()
        pairs.forEach { (left, right) ->
            effectiveSelected.getOrNull(left)?.let { conflictRows.add(it) }
            effectiveSelected.getOrNull(right)?.let { conflictRows.add(it) }
        }
        conflictCount = pairs.size
        items.indices.forEach { index ->
            val row = items[index]
            items[index] = row.copy(
                issues = issuesByRow[index].orEmpty(),
                conflict = index in conflictRows,
                selected = row.selected && index !in fatalIndices
            )
        }
    }

    /** 勾选记录 → 现有课表写入模型；缺少完整时间的记录不会生成课程。 */
    fun selectedCourses(): List<ImportedCourse> =
        ImportMapper.toImportedCourses(items.filter { it.selected }.map { it.record })

    /** 按“课程名 + 教师 + 地点”分组，保持记录原始顺序。 */
    fun groups(): List<DraftGroup> {
        val grouped = LinkedHashMap<String, MutableList<Int>>()
        items.forEachIndexed { index, row ->
            val record = row.record
            val key = listOf(record.courseName, record.teacher, record.location).joinToString(KEY_SEPARATOR)
            grouped.getOrPut(key) { mutableListOf() }.add(index)
        }
        return grouped.values.map { indices ->
            val first = items[indices.first()].record
            DraftGroup(
                courseName = first.courseName,
                teacher = first.teacher,
                location = first.location,
                rowIndices = indices.toList()
            )
        }
    }

    private companion object {
        const val CODE_TIME_CONFLICT = "TIME_CONFLICT"
        const val KEY_SEPARATOR = "\u0001"
    }
}
