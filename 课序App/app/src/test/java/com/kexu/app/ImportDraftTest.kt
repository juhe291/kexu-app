package com.kexu.app

import com.kexu.app.domain.Semester
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.ui.imports.ImportDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ImportDraft 纯 JVM 单测：覆盖汇总、勾选、冲突重算、编辑后重校验、
 * selectedCourses 映射、FATAL 行不可选、删除与分组等行为。
 */
class ImportDraftTest {

    private val semester = Semester(totalWeeks = 20)
    private val maxPeriods = 12

    private fun record(
        name: String = "高等数学",
        teacher: String = "张三",
        location: String = "A101",
        day: Int? = 0,
        start: Int? = 1,
        end: Int? = 2,
        weeks: Set<Int> = (1..16).toSet(),
        weeksExpression: String? = null,
        source: ImportSource = ImportSource.CSV
    ) = CourseImportRecord(
        courseName = name,
        teacher = teacher,
        location = location,
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        weeks = weeks,
        originalWeekExpression = weeksExpression,
        source = source
    )

    private fun draftOf(vararg records: CourseImportRecord) =
        ImportDraft(records.toList(), semester, maxPeriods)

    @Test
    fun summaryCountsInitialRecords() {
        val draft = draftOf(record(), record(name = "大学英语", day = 1, start = 3, end = 4))
        assertEquals(2, draft.rows.size)
        assertEquals(2, draft.summary.total)
        assertEquals(2, draft.summary.selected)
        assertEquals(0, draft.summary.fatal)
        assertEquals(0, draft.summary.confirm)
        assertEquals(0, draft.summary.warning)
        assertEquals(0, draft.summary.conflicts)
        assertTrue(draft.canImport)
    }

    @Test
    fun fatalRowIsDeselectedAndCannotBeSelected() {
        val draft = draftOf(
            record(name = "  "),
            record(name = "大学英语", day = 3, start = 5, end = 6)
        )
        val fatalRow = draft.rows[0]
        assertFalse(fatalRow.selected)
        assertFalse(fatalRow.selectable)
        assertTrue(fatalRow.fatalIssues.isNotEmpty())
        assertEquals(1, draft.summary.fatal)
        assertEquals(1, draft.summary.selected)
        assertTrue(draft.canImport)

        draft.toggleRow(0)
        assertFalse(draft.rows[0].selected)
        assertEquals(1, draft.summary.selected)
    }

    @Test
    fun allFatalDraftCannotImport() {
        val draft = draftOf(record(name = ""), record(name = " "))
        assertEquals(0, draft.summary.selected)
        assertEquals(2, draft.summary.fatal)
        assertFalse(draft.canImport)
        assertTrue(draft.selectedCourses().isEmpty())
    }

    @Test
    fun conflictCountRecomputedWhenSelectionChanges() {
        val draft = draftOf(
            record(name = "线性代数", day = 2, start = 3, end = 4),
            record(name = "大学物理", day = 2, start = 4, end = 5)
        )
        assertEquals(1, draft.summary.conflicts)
        assertTrue(draft.rows[0].conflict)
        assertTrue(draft.rows[1].conflict)

        draft.toggleRow(1)
        assertEquals(0, draft.summary.conflicts)
        assertFalse(draft.rows[0].conflict)
        assertFalse(draft.rows[1].conflict)

        draft.toggleRow(1)
        assertEquals(1, draft.summary.conflicts)
    }

    @Test
    fun unselectedRowsDoNotParticipateInConflicts() {
        val draft = draftOf(
            record(name = "线性代数", day = 2, start = 3, end = 4),
            record(name = "大学物理", day = 2, start = 4, end = 5),
            record(name = "大学英语", day = 3, start = 6, end = 7)
        )
        assertEquals(1, draft.summary.conflicts)
        draft.toggleRow(0)
        assertEquals(0, draft.summary.conflicts)
        assertFalse(draft.rows[0].conflict)
        assertFalse(draft.rows[1].conflict)
        assertFalse(draft.rows[2].conflict)
        assertTrue(draft.rows[1].selected)
    }

    @Test
    fun editingWeeksUpdatesIssues() {
        val draft = draftOf(record(weeks = emptySet()))
        assertEquals(1, draft.summary.confirm)
        assertTrue(draft.rows[0].confirmIssues.any { it.code == "NO_WEEKS" })

        draft.updateRecord(
            0,
            draft.rows[0].record.copy(weeks = (1..8).toSet(), originalWeekExpression = "1-8周")
        )
        assertEquals(0, draft.summary.confirm)
        assertTrue(draft.rows[0].issues.isEmpty())
        assertTrue(draft.canImport)
        assertEquals((1..8).toSet(), draft.rows[0].record.weeks)
        assertEquals("1-8周", draft.rows[0].record.originalWeekExpression)
    }

    @Test
    fun editingToInvalidSectionMarksFatalAndDeselects() {
        val draft = draftOf(record())
        assertTrue(draft.rows[0].selected)

        draft.updateRecord(0, draft.rows[0].record.copy(startSection = 0, endSection = 1))
        val row = draft.rows[0]
        assertTrue(row.fatalIssues.any { it.code == "INVALID_SECTION" })
        assertFalse(row.selected)
        assertEquals(1, draft.summary.fatal)
        assertFalse(draft.canImport)
    }

    @Test
    fun selectedCoursesMapsOnlySelectedRows() {
        val draft = draftOf(
            record(name = "数据结构", day = 0, start = 1, end = 2),
            record(name = "数据结构", day = 2, start = 5, end = 6),
            record(name = "大学英语", day = 1, start = 3, end = 4)
        )
        val all = draft.selectedCourses()
        assertEquals(2, all.size)
        assertEquals(2, all.first { it.name == "数据结构" }.meetings.size)

        draft.toggleRow(0)
        val partial = draft.selectedCourses()
        assertEquals(2, partial.size)
        val dataStructure = partial.first { it.name == "数据结构" }
        assertEquals(1, dataStructure.meetings.size)
        assertEquals(5, dataStructure.meetings.first().startPeriod)
        assertEquals(
            (1..16).joinToString(","),
            dataStructure.meetings.first().weeksCsv
        )
    }

    @Test
    fun selectAllSkipsFatalRows() {
        val draft = draftOf(
            record(name = ""),
            record(name = "大学英语", day = 1, start = 3, end = 4),
            record(name = "大学物理", day = 4, start = 7, end = 8)
        )
        draft.selectAll(false)
        assertEquals(0, draft.summary.selected)
        assertFalse(draft.canImport)

        draft.selectAll(true)
        assertEquals(2, draft.summary.selected)
        assertFalse(draft.rows[0].selected)
        assertTrue(draft.canImport)
    }

    @Test
    fun deleteRowUpdatesSummaryAndGroups() {
        val draft = draftOf(
            record(name = "高等数学"),
            record(name = "大学英语", day = 1, start = 3, end = 4),
            record(name = "大学物理", day = 4, start = 7, end = 8)
        )
        assertEquals(3, draft.summary.total)

        draft.deleteRow(0)
        assertEquals(2, draft.summary.total)
        assertEquals(2, draft.summary.selected)
        assertEquals(2, draft.groups().size)
        assertEquals(listOf(0), draft.groups().first().rowIndices)

        draft.deleteRow(99)
        assertEquals(2, draft.rows.size)
    }

    @Test
    fun groupsMergeSameCourseTeacherAndLocation() {
        val draft = draftOf(
            record(name = "高等数学", day = 0, start = 1, end = 2),
            record(name = "高等数学", day = 2, start = 3, end = 4),
            record(name = "高等数学", teacher = "李四", day = 4, start = 5, end = 6)
        )
        val groups = draft.groups()
        assertEquals(2, groups.size)
        assertEquals(listOf(0, 1), groups[0].rowIndices)
        assertEquals(2, groups[0].size)
        assertEquals("张三", groups[0].teacher)
        assertEquals("A101", groups[0].location)
        assertEquals(listOf(2), groups[1].rowIndices)
    }

    @Test
    fun updatingRecordKeepsSourceMetadata() {
        val original = record(source = ImportSource.PDF)
        val draft = draftOf(original)
        draft.updateRecord(
            0,
            original.copy(courseName = "复变函数", dayOfWeek = 1, startSection = 3, endSection = 4)
        )
        assertEquals("复变函数", draft.rows[0].record.courseName)
        assertEquals(ImportSource.PDF, draft.rows[0].record.source)
        assertEquals(1, draft.rows[0].record.dayOfWeek ?: -1)
        assertTrue(draft.canImport)
    }

    @Test
    fun emptyDraftIsNotImportableAndIgnoresBadIndexes() {
        val draft = ImportDraft(emptyList(), semester, maxPeriods)
        assertEquals(0, draft.summary.total)
        assertFalse(draft.canImport)
        assertTrue(draft.selectedCourses().isEmpty())
        assertTrue(draft.groups().isEmpty())

        draft.toggleRow(0)
        draft.updateRecord(0, record())
        draft.deleteRow(0)
        assertTrue(draft.rows.isEmpty())
    }
}
