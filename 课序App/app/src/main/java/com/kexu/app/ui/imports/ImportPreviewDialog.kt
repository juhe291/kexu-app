package com.kexu.app.ui.imports

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.ImportedCourse
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Semester
import com.kexu.app.domain.WeekRuleParser
import com.kexu.app.domain.imports.CourseImportRecord
import com.kexu.app.domain.imports.CourseImportResult
import com.kexu.app.domain.imports.ImportSource
import com.kexu.app.domain.imports.IssueSeverity
import com.kexu.app.ui.components.KStepper
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.theme.LocalKexuColors

/**
 * 导入预览对话框：按课程分组展示识别结果，可逐条勾选、编辑并重新校验；
 * 确认后把选中的课程交给调用方（SettingsScreen）写入课表。
 */
@Composable
fun ImportPreviewDialog(
    result: CourseImportResult,
    semester: Semester,
    maxPeriods: Int,
    onDismiss: () -> Unit,
    onConfirm: (List<ImportedCourse>) -> Unit
) {
    val colors = LocalKexuColors.current
    val draft = remember(result, semester, maxPeriods) { ImportDraft(result.records, semester, maxPeriods) }
    var rows by remember(draft) { mutableStateOf(draft.rows) }
    var editingIndex by remember(draft) { mutableStateOf<Int?>(null) }

    fun sync() {
        rows = draft.rows
    }

    val summary = draft.summary
    val groups = draft.groups()
    val importableCount = draft.selectedCourses().size
    val confirmEnabled = draft.canImport && importableCount > 0
    val globalIssues = result.issues.filter { it.recordIndex == null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "导入预览 · 确认后写入课表",
                    color = colors.ink,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "来源：" + sourceLabel(result.source) +
                        (result.schoolTemplate?.let { " · $it 模板" } ?: ""),
                    color = colors.muted,
                    fontSize = 10.5.sp
                )
            }
        },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.surface2)
                        .padding(10.dp)
                ) {
                    Text(
                        "识别 " + groups.size + " 门课程 / " + summary.total + " 个课次",
                        color = colors.ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        SummaryItem("错误", summary.fatal, colors.danger)
                        SummaryItem("待确认", summary.confirm, colors.accent3)
                        SummaryItem("警告", summary.warning, colors.warn)
                        SummaryItem("冲突", summary.conflicts, colors.warn)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "已选 " + summary.selected + " / " + summary.total + " 条",
                        color = colors.muted,
                        fontSize = 10.5.sp
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "全选",
                        color = colors.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                draft.selectAll(true)
                                sync()
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "全不选",
                        color = colors.muted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .clickable {
                                draft.selectAll(false)
                                sync()
                            }
                            .padding(horizontal = 4.dp, vertical = 4.dp)
                    )
                }
                globalIssues.take(4).forEach { issue ->
                    Text(
                        issue.message,
                        color = issueTone(issue.severity),
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (globalIssues.size > 4) {
                    Text(
                        "还有 " + (globalIssues.size - 4) + " 条解析提示",
                        color = colors.muted,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (rows.isEmpty()) {
                    Text(
                        "没有可导入的课程记录，请返回检查文件内容或换用模板。",
                        color = colors.muted,
                        fontSize = 11.5.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
                groups.forEach { group ->
                    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    group.courseName.ifBlank { "（未命名课程）" },
                                    color = colors.ink,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                                val meta = listOf(group.teacher, group.location)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" · ")
                                if (meta.isNotEmpty()) {
                                    Text(meta, color = colors.muted, fontSize = 9.5.sp)
                                }
                            }
                            Text(group.size.toString() + " 条", color = colors.muted, fontSize = 10.sp)
                        }
                        group.rowIndices.forEach { index ->
                            rows.getOrNull(index)?.let { row ->
                                DraftRowItem(
                                    row = row,
                                    onClick = { editingIndex = index },
                                    onToggle = {
                                        draft.toggleRow(index)
                                        sync()
                                    }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "点按任意一条可编辑课程名、教师、地点、时间与周次；存在严重错误的记录修正后才能勾选。",
                    color = colors.muted,
                    fontSize = 10.sp,
                    lineHeight = 15.sp
                )
                if (rows.isNotEmpty() && !confirmEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (summary.selected == 0) "请至少勾选一条记录后再导入。"
                        else "选中的记录存在严重错误或缺少完整时间，暂不能导入。",
                        color = colors.danger,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = colors.muted, fontSize = 12.5.sp)
                }
                Spacer(Modifier.width(4.dp))
                DialogPrimaryButton(
                    text = "确认导入 (" + importableCount + ")",
                    enabled = confirmEnabled,
                    onClick = { onConfirm(draft.selectedCourses()) }
                )
            }
        },
        containerColor = colors.surface
    )

    val editIndex = editingIndex
    val editRow = editIndex?.let { rows.getOrNull(it) }
    if (editIndex != null && editRow != null) {
        val targetIndex: Int = editIndex
        EditRecordDialog(
            row = editRow,
            maxPeriods = maxPeriods,
            onDismiss = { editingIndex = null },
            onSave = { updated ->
                draft.updateRecord(targetIndex, updated)
                sync()
                editingIndex = null
            },
            onDelete = {
                draft.deleteRow(targetIndex)
                sync()
                editingIndex = null
            }
        )
    }
}

@Composable
private fun SummaryItem(label: String, count: Int, tone: Color) {
    val colors = LocalKexuColors.current
    Text(
        label + " " + count,
        color = if (count == 0) colors.muted else tone,
        fontSize = 10.5.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun issueTone(severity: IssueSeverity): Color {
    val colors = LocalKexuColors.current
    return when (severity) {
        IssueSeverity.FATAL -> colors.danger
        IssueSeverity.CONFIRM -> colors.accent3
        IssueSeverity.WARNING -> colors.warn
    }
}

@Composable
private fun DraftRowItem(row: DraftRow, onClick: () -> Unit, onToggle: () -> Unit) {
    val colors = LocalKexuColors.current
    val record = row.record
    val name = record.courseName.ifBlank { "（未命名课程）" }
    val severityColor = row.severity?.let { issueTone(it) } ?: colors.muted
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .padding(top = 2.dp)
                .size(18.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(if (row.selected) colors.accent else Color.Transparent)
                .border(
                    1.dp,
                    if (row.selectable) colors.accent else colors.muted,
                    RoundedCornerShape(5.dp)
                )
                .clickable { onToggle() },
            contentAlignment = Alignment.Center
        ) {
            if (row.selected) {
                Text("✓", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    name,
                    color = if (row.severity == IssueSeverity.FATAL) colors.danger else colors.ink,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (row.conflict) {
                    Spacer(Modifier.width(5.dp))
                    Text(
                        "⚠ 冲突",
                        color = colors.warn,
                        fontSize = 9.5.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Text(
                timeLabel(record),
                color = severityColor,
                fontSize = 10.5.sp,
                lineHeight = 15.sp
            )
            Text(
                "周次 " + weeksLabel(record) + " · " +
                    record.teacher.ifBlank { "教师待确认" } + " · " +
                    record.location.ifBlank { "地点待确认" },
                color = colors.muted,
                fontSize = 10.sp,
                lineHeight = 14.sp
            )
            row.issues.take(2).forEach { issue ->
                Text(
                    issue.message,
                    color = issueTone(issue.severity),
                    fontSize = 10.sp,
                    lineHeight = 14.sp
                )
            }
            if (row.issues.size > 2) {
                Text(
                    "还有 " + (row.issues.size - 2) + " 条提示",
                    color = colors.muted,
                    fontSize = 9.5.sp
                )
            }
        }
    }
}

@Composable
private fun EditRecordDialog(
    row: DraftRow,
    maxPeriods: Int,
    onDismiss: () -> Unit,
    onSave: (CourseImportRecord) -> Unit,
    onDelete: () -> Unit
) {
    val colors = LocalKexuColors.current
    val limit = maxPeriods.coerceAtLeast(1)
    var name by remember(row) { mutableStateOf(row.record.courseName) }
    var teacher by remember(row) { mutableStateOf(row.record.teacher) }
    var location by remember(row) { mutableStateOf(row.record.location) }
    var day by remember(row) { mutableStateOf((row.record.dayOfWeek ?: 0).coerceIn(0, 6)) }
    var start by remember(row) { mutableStateOf((row.record.startSection ?: 1).coerceIn(1, limit)) }
    var end by remember(row) {
        mutableStateOf((row.record.endSection ?: row.record.startSection ?: 1).coerceIn(1, limit))
    }
    var weeksText by remember(row) { mutableStateOf(initialWeeksText(row.record)) }

    val parsedWeeks = weeksText.takeIf { it.isNotBlank() }?.let { WeekRuleParser.parse(it) }
    val weeksError = if (weeksText.isNotBlank()) parsedWeeks?.error else null
    val startSafe = start.coerceIn(1, limit)
    val endSafe = end.coerceIn(startSafe, limit)
    val canSave = name.isNotBlank() && weeksError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "编辑导入记录",
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column(Modifier.heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                KTextField(name, { name = it }, label = "课程名", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KTextField(teacher, { teacher = it }, label = "教师", modifier = Modifier.weight(1f))
                    KTextField(location, { location = it }, label = "地点", modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Text("星期", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KexuCore.WEEKDAY_CN.forEachIndexed { index, label ->
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(7.dp))
                                .background(if (day == index) colors.accentSoft else colors.surface2)
                                .clickable { day = index }
                                .padding(vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                label,
                                color = if (day == index) colors.accent else colors.muted,
                                fontSize = 9.5.sp
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                EditFieldRow("开始节次") {
                    KStepper(startSafe, 1, limit, 1) { value ->
                        start = value
                        if (end < value) end = value
                    }
                }
                EditFieldRow("结束节次") {
                    KStepper(endSafe, startSafe, limit, 1) { value -> end = value }
                }
                Spacer(Modifier.height(6.dp))
                KTextField(
                    weeksText,
                    { weeksText = it },
                    label = "周次表达式",
                    placeholder = "如 1-16周(单)",
                    modifier = Modifier.fillMaxWidth()
                )
                if (weeksError != null) {
                    Text(
                        weeksError,
                        color = colors.danger,
                        fontSize = 10.5.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                } else {
                    Text(
                        if (weeksText.isBlank()) "留空表示周次待确认；填写后按周次规则解析。"
                        else "已解析 " + (parsedWeeks?.weeks?.size ?: 0) + " 个教学周",
                        color = colors.muted,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
                if (!row.record.hasTime) {
                    Text(
                        "原记录缺少完整时间，保存后将使用上面选择的星期与节次。",
                        color = colors.warn,
                        fontSize = 10.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "删除此条",
                    color = colors.danger,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .clickable { onDelete() }
                        .padding(vertical = 8.dp, horizontal = 4.dp)
                )
            }
        },
        confirmButton = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = colors.muted, fontSize = 12.5.sp)
                }
                Spacer(Modifier.width(4.dp))
                DialogPrimaryButton(text = "保存", enabled = canSave) {
                    val rule = if (weeksText.isBlank()) null else WeekRuleParser.parse(weeksText)
                    onSave(
                        row.record.copy(
                            courseName = name.trim(),
                            teacher = teacher.trim(),
                            location = location.trim(),
                            dayOfWeek = day,
                            startSection = startSafe,
                            endSection = endSafe,
                            weeks = rule?.weeks ?: emptySet(),
                            originalWeekExpression = weeksText.trim().ifBlank { null },
                            weeksError = rule?.error
                        )
                    )
                }
            }
        },
        containerColor = colors.surface
    )
}

@Composable
private fun EditFieldRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = LocalKexuColors.current.muted,
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        content()
    }
}

@Composable
private fun DialogPrimaryButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = colors.accent,
            contentColor = Color.White,
            disabledContainerColor = colors.line,
            disabledContentColor = colors.muted
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

private fun sourceLabel(source: ImportSource): String = when (source) {
    ImportSource.PDF -> "PDF"
    ImportSource.CSV -> "CSV"
    ImportSource.ICS -> "ICS"
    ImportSource.XLSX -> "Excel (XLSX)"
    ImportSource.IMAGE -> "图片 (OCR)"
    ImportSource.HTML -> "HTML"
    ImportSource.JSON -> "JSON 模板"
}

private fun timeLabel(record: CourseImportRecord): String {
    val day = record.dayOfWeek?.let { KexuCore.WEEKDAY_CN.getOrNull(it) } ?: "星期待确认"
    val sections = if (record.startSection != null && record.endSection != null) {
        "第 " + record.startSection + "–" + record.endSection + " 节"
    } else {
        "节次待确认"
    }
    return day + " · " + sections
}

private fun weeksLabel(record: CourseImportRecord): String {
    val raw = record.originalWeekExpression?.trim().orEmpty()
    if (raw.isNotEmpty()) return raw
    if (record.weeks.isNotEmpty()) return record.weeks.sorted().joinToString(",") + " 周"
    return "周次待确认"
}

private fun initialWeeksText(record: CourseImportRecord): String {
    val raw = record.originalWeekExpression?.trim().orEmpty()
    if (raw.isNotEmpty()) return raw
    if (record.weeks.isNotEmpty()) return record.weeks.sorted().joinToString(",")
    return ""
}
