package com.kexu.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.RecurringTaskPlanner
import com.kexu.app.domain.Task
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.components.KSegmented
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.theme.LocalKexuColors
import java.time.LocalDate

/** 编辑已有任务：标题、优先级、时长、截止、计划时间；也可删除。 */
@Composable
fun TaskEditDialog(
    state: AppUiState,
    task: Task,
    onDismiss: () -> Unit,
    onSave: (Task) -> Unit,
    onDelete: (Task) -> Unit
) {
    val colors = LocalKexuColors.current
    var title by remember(task.id) { mutableStateOf(task.title) }
    var priority by remember(task.id) { mutableStateOf(task.priority) }
    var minutes by remember(task.id) { mutableStateOf(task.estimatedMinutes ?: state.prefs.defaultTaskMinutes) }
    var deadline by remember(task.id) { mutableStateOf(task.deadline?.toString() ?: "") }
    var scheduled by remember(task.id) { mutableStateOf(task.from != null && task.to != null) }
    var weekday by remember(task.id) { mutableStateOf(task.weekday ?: KexuCore.weekdayIndex(LocalDate.now())) }
    var from by remember(task.id) { mutableStateOf(task.from ?: "") }
    var to by remember(task.id) { mutableStateOf(task.to ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var repeatKind by remember(task.id) { mutableStateOf(task.repeatKind) }
    var repeatUntil by remember(task.id) { mutableStateOf(task.repeatUntil?.toString() ?: "") }

    KexuDialog(title = "编辑任务", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            KTextField(title, { title = it }, label = "任务标题")
            Spacer(Modifier.height(10.dp))
            Text("优先级", color = colors.muted, fontSize = 11.5.sp)
            Spacer(Modifier.height(5.dp))
            KSegmented(
                options = listOf("low" to "低", "medium" to "中", "high" to "高"),
                selected = priority,
                onSelect = { priority = it }
            )
            Spacer(Modifier.height(10.dp))
            Text("预计时长：${KexuCore.formatDuration(minutes)}", color = colors.muted, fontSize = 11.5.sp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(25, 45, 60, 90, 120).forEach { value ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (minutes == value) colors.accentSoft else colors.surface2)
                            .clickable { minutes = value }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("$value", color = if (minutes == value) colors.accent else colors.muted, fontSize = 11.5.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            KTextField(deadline, { deadline = it }, label = "截止日期（可选，YYYY-MM-DD）")
            Spacer(Modifier.height(10.dp))
            Text("重复", color = colors.muted, fontSize = 11.5.sp)
            Spacer(Modifier.height(5.dp))
            KSegmented(
                options = listOf(
                    RecurringTaskPlanner.NONE to "不重复",
                    RecurringTaskPlanner.DAILY to "每天",
                    RecurringTaskPlanner.WEEKLY to "每周"
                ),
                selected = repeatKind,
                onSelect = { repeatKind = it }
            )
            if (repeatKind != RecurringTaskPlanner.NONE) {
                Spacer(Modifier.height(8.dp))
                KTextField(repeatUntil, { repeatUntil = it }, label = "重复截止（可选，YYYY-MM-DD）")
                Text(
                    "完成后会自动生成下一条（需要截止日期或已安排时间）",
                    color = colors.muted,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surface2)
                    .clickable { scheduled = !scheduled }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    if (scheduled) "已安排具体时间" else "未安排时间（在待安排中）",
                    color = colors.ink,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(if (scheduled) "取消安排" else "安排时间", color = colors.accent, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            }
            if (scheduled) {
                Spacer(Modifier.height(8.dp))
                Text("星期", color = colors.muted, fontSize = 11.5.sp)
                Spacer(Modifier.height(4.dp))
                KexuCore.WEEKDAY_CN.chunked(4).forEach { chunk ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chunk.forEach { label ->
                            val index = KexuCore.WEEKDAY_CN.indexOf(label)
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (weekday == index) colors.accentSoft else colors.surface2)
                                    .clickable { weekday = index }
                                    .padding(vertical = 7.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (weekday == index) colors.accent else colors.muted, fontSize = 11.5.sp)
                            }
                        }
                        if (chunk.size < 4) Spacer(Modifier.weight((4 - chunk.size).toFloat()))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KTextField(from, { from = it }, label = "开始", placeholder = "14:00", modifier = Modifier.weight(1f))
                    Text("—", color = colors.muted, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    KTextField(to, { to = it }, label = "结束", placeholder = "15:00", modifier = Modifier.weight(1f))
                }
            }
            if (error != null) {
                Text(error!!, color = colors.danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("保存修改", modifier = Modifier.fillMaxWidth()) {
                if (title.isBlank()) {
                    error = "请填写任务标题"
                } else {
                    val parsed = if (deadline.isBlank()) null else runCatching { LocalDate.parse(deadline.trim()) }.getOrNull()
                    val parsedRepeatUntil = if (repeatUntil.isBlank()) null else runCatching { LocalDate.parse(repeatUntil.trim()) }.getOrNull()
                    val seriesId = if (repeatKind == RecurringTaskPlanner.NONE) null else (task.repeatSeriesId ?: task.id)
                    when {
                        deadline.isNotBlank() && parsed == null -> error = "截止日期格式应为 YYYY-MM-DD"
                        repeatUntil.isNotBlank() && parsedRepeatUntil == null -> error = "重复截止日期格式应为 YYYY-MM-DD"
                        repeatKind != RecurringTaskPlanner.NONE && !scheduled && parsed == null ->
                            error = "重复任务需要设置截止日期或安排时间，否则无法计算下一次"
                        scheduled -> {
                            val fromMin = KexuCore.minutesOf(from)
                            val toMin = KexuCore.minutesOf(to)
                            if (fromMin < 0 || toMin < 0) error = "时间格式应为 HH:mm"
                            else if (toMin <= fromMin) error = "结束时间需要晚于开始时间"
                            else {
                                val baseWeek = task.week ?: KexuCore.weekIndexForDate(state.semester.startDate, LocalDate.now())
                                onSave(
                                    task.copy(
                                        title = title.trim(),
                                        priority = priority,
                                        estimatedMinutes = minutes,
                                        deadline = parsed,
                                        week = baseWeek,
                                        weekday = weekday,
                                        from = KexuCore.timeOf(fromMin),
                                        to = KexuCore.timeOf(toMin),
                                        repeatKind = repeatKind,
                                        repeatUntil = parsedRepeatUntil,
                                        repeatSeriesId = seriesId
                                    )
                                )
                            }
                        }
                        else -> {
                            onSave(
                                task.copy(
                                    title = title.trim(),
                                    priority = priority,
                                    estimatedMinutes = minutes,
                                    deadline = parsed,
                                    week = null,
                                    weekday = null,
                                    from = null,
                                    to = null,
                                    repeatKind = repeatKind,
                                    repeatUntil = parsedRepeatUntil,
                                    repeatSeriesId = seriesId
                                )
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { confirmDelete = true }
                    .padding(vertical = 11.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("删除任务", color = colors.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除任务", fontWeight = FontWeight.ExtraBold) },
            text = { Text("确定删除「${task.title}」吗？删除后无法恢复。", color = colors.ink2, fontSize = 13.sp) },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    confirmDelete = false
                    onDelete(task)
                }) { Text("删除", color = colors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmDelete = false }) { Text("取消", color = colors.muted) }
            },
            containerColor = colors.surface
        )
    }
}
