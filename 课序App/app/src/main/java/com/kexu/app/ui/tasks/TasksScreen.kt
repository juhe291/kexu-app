@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.kexu.app.ui.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.Goal
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.RecurringTaskPlanner
import com.kexu.app.domain.Task
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.KexuViewModel
import com.kexu.app.ui.components.ConfirmDialog
import com.kexu.app.ui.components.KSegmented
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.MoreActionsDialog
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.components.RowAction
import com.kexu.app.ui.goals.AddGoalDialog
import com.kexu.app.ui.theme.LocalFontScales
import com.kexu.app.ui.theme.LocalKexuColors
import com.kexu.app.ui.theme.toneOf
import java.time.LocalDate

private enum class TaskFilter(val label: String) { ALL("全部"), SCHEDULED("已安排"), INBOX("待安排"), DONE("已完成") }

private enum class TaskSort(val label: String) { DEFAULT("默认"), DEADLINE("按截止"), PRIORITY("按优先级") }

/**
 * 待办页（用户最新截图版）：眉标 + 大号「待办」与未完成计数 + 全宽下划线筛选，
 * 空状态插画引导，分隔线下方固定「智能排程 / 长期学习」入口；优先处理卡与任务行保持 V11 样式。
 */
@Composable
fun TasksScreen(state: AppUiState, vm: KexuViewModel, onGoPlanning: () -> Unit) {
    val colors = LocalKexuColors.current
    var filter by remember { mutableStateOf(TaskFilter.ALL) }
    val sort = TaskSort.entries.firstOrNull { it.name.lowercase() == state.prefs.taskSort } ?: TaskSort.DEFAULT
    var showAdd by remember { mutableStateOf(false) }
    var showAddGoal by remember { mutableStateOf(false) }
    var goalToDelete by remember { mutableStateOf<Goal?>(null) }
    var actionTask by remember { mutableStateOf<Task?>(null) }
    var actionGoal by remember { mutableStateOf<Goal?>(null) }
    var taskToDelete by remember { mutableStateOf<Task?>(null) }
    var editTask by remember { mutableStateOf<Task?>(null) }

    val scheduled = state.tasks.filter { it.week != null && it.from != null }
    val inbox = state.tasks.filter { it.week == null && it.from == null }
    val unfinished = state.tasks.count { !it.done }
    val priorityTask = state.tasks
        .filter { !it.done }
        .sortedWith(
            compareBy<Task> { priorityRank(it.priority) }
                .thenBy { it.deadline ?: LocalDate.MAX }
                .thenBy { it.week == null }
        )
        .firstOrNull()

    val visible = when (filter) {
        TaskFilter.ALL -> state.tasks
        TaskFilter.SCHEDULED -> scheduled
        TaskFilter.INBOX -> inbox
        TaskFilter.DONE -> state.tasks.filter { it.done }
    }
    val shown = when (sort) {
        TaskSort.DEFAULT -> visible
        TaskSort.DEADLINE -> visible.sortedWith(compareBy { it.deadline ?: LocalDate.MAX })
        TaskSort.PRIORITY -> visible.sortedBy { priorityRank(it.priority) }
    }

    LazyColumn(
        Modifier
            .fillMaxWidth()
            .background(colors.surface),
        contentPadding = PaddingValues(start = 23.dp, end = 23.dp, top = 22.dp, bottom = 34.dp)
    ) {
        item {
            TaskPageHeader(
                sort = sort,
                unfinished = unfinished,
                filter = filter,
                onSelectFilter = { filter = it },
                onCycleSort = {
                    val nextSort = TaskSort.entries[(sort.ordinal + 1) % TaskSort.entries.size]
                    vm.updatePrefs { it.copy(taskSort = nextSort.name.lowercase()) }
                }
            )
        }
        if (priorityTask != null) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(18.dp))
                    PrioritySpot(task = priorityTask, state = state, onClick = { editTask = priorityTask })
                    Spacer(Modifier.height(8.dp))
                }
            }
        }
        if (shown.isEmpty()) {
            item {
                TaskEmptyState(filter = filter, onAdd = { showAdd = true })
            }
        } else {
            items(shown.size) { index ->
                TaskRow(
                    task = shown[index],
                    state = state,
                    onToggle = { vm.toggleTask(shown[index].id, !shown[index].done) },
                    onClick = { editTask = shown[index] },
                    onLongClick = { actionTask = shown[index] }
                )
            }
            item {
                Text(
                    "＋ 添加任务",
                    color = colors.accent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showAdd = true }
                        .padding(horizontal = 2.dp, vertical = 16.dp)
                )
            }
        }
        item {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(26.dp))
                HorizontalDivider(color = colors.line)
            }
        }
        item {
            PlanningEntry(onGoPlanning = onGoPlanning)
        }
        item {
            HorizontalDivider(color = colors.line)
        }
        item {
            LongTermSection(
                goals = state.goals,
                onAddGoal = { showAddGoal = true },
                onGoPlanning = onGoPlanning,
                onAdvanceGoal = { vm.advanceGoal(it) },
                onLongPressGoal = { actionGoal = it }
            )
        }
    }

    if (showAdd) {
        AddTaskDialog(state = state, onDismiss = { showAdd = false }) { title, courseId, priority, minutes, deadline, week, weekday, from, to, repeatKind, repeatUntil ->
            vm.addTask(
                title = title,
                courseId = courseId,
                priority = priority,
                estimatedMinutes = minutes,
                deadline = deadline,
                from = from,
                to = to,
                weekday = weekday,
                week = week,
                repeatKind = repeatKind,
                repeatUntil = repeatUntil
            )
            showAdd = false
        }
    }

    if (showAddGoal) {
        AddGoalDialog(
            onDismiss = { showAddGoal = false },
            onSave = { title, weeks, hours, next ->
                vm.addGoal(title, weeks, hours, next)
                showAddGoal = false
            }
        )
    }

    editTask?.let { task ->
        TaskEditDialog(
            state = state,
            task = task,
            onDismiss = { editTask = null },
            onSave = {
                vm.updateTask(it)
                editTask = null
            },
            onDelete = {
                vm.deleteTask(it.id)
                editTask = null
            }
        )
    }

    goalToDelete?.let { goal ->
        ConfirmDialog(
            title = "删除长期目标",
            message = "确定删除「${goal.title}」吗？不会删除已关联的任务。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteGoal(goal.id)
                goalToDelete = null
            },
            onDismiss = { goalToDelete = null }
        )
    }

    actionTask?.let { task ->
        MoreActionsDialog(
            title = task.title,
            actions = listOf(
                RowAction("编辑任务") { editTask = task },
                RowAction(if (task.done) "恢复为未完成" else "标记完成") {
                    vm.toggleTask(task.id, !task.done)
                },
                RowAction("复制任务") {
                    vm.duplicateTask(task)
                    actionTask = null
                },
                RowAction("删除任务", danger = true) {
                    taskToDelete = task
                }
            ),
            onDismiss = { actionTask = null }
        )
    }

    taskToDelete?.let { task ->
        ConfirmDialog(
            title = "删除任务",
            message = "确定删除「${task.title}」吗？删除后无法恢复。",
            confirmText = "删除",
            danger = true,
            onConfirm = {
                vm.deleteTask(task.id)
                taskToDelete = null
            },
            onDismiss = { taskToDelete = null }
        )
    }

    actionGoal?.let { goal ->
        MoreActionsDialog(
            title = goal.title,
            actions = listOf(
                RowAction("删除长期目标", danger = true) { goalToDelete = goal }
            ),
            onDismiss = { actionGoal = null }
        )
    }
}

/** 页头：眉标与排序切换 + 大标题与未完成计数 + 全宽下划线筛选。 */
@Composable
private fun TaskPageHeader(
    sort: TaskSort,
    unfinished: Int,
    filter: TaskFilter,
    onSelectFilter: (TaskFilter) -> Unit,
    onCycleSort: () -> Unit
) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "所有任务",
                color = colors.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                modifier = Modifier.weight(1f)
            )
            Text(
                "排序：${sort.label}",
                color = colors.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onCycleSort)
                    .padding(horizontal = 6.dp, vertical = 4.dp)
            )
        }
        Spacer(Modifier.height(7.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Text(
                "待办",
                color = colors.ink,
                fontSize = (35f * LocalFontScales.current.title / LocalFontScales.current.body).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.5).sp,
                lineHeight = 40.sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                unfinished.toString().padStart(2, '0'),
                color = colors.accent,
                fontSize = 49.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-2.6).sp,
                lineHeight = 52.sp
            )
            Spacer(Modifier.width(5.dp))
            Text(
                "项任务",
                color = colors.muted,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        Spacer(Modifier.height(14.dp))
        FilterTabs(filter = filter, onSelect = onSelectFilter)
    }
}

/** 全宽下划线筛选：选中项 accent 加粗，底部一条 2dp 主色下划线。 */
@Composable
private fun FilterTabs(filter: TaskFilter, onSelect: (TaskFilter) -> Unit) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            TaskFilter.entries.forEach { entry ->
                val active = entry == filter
                Text(
                    entry.label,
                    color = if (active) colors.accent else colors.muted,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable { onSelect(entry) }
                        .padding(top = 10.dp, bottom = 15.dp)
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(colors.accent)
        )
    }
}

/** 空状态：插图 + 主标题 + 说明 + 居中主按钮（不同筛选给对应文案）。 */
@Composable
private fun TaskEmptyState(filter: TaskFilter, onAdd: () -> Unit) {
    val colors = LocalKexuColors.current
    val title = when (filter) {
        TaskFilter.ALL -> "从第一项任务开始"
        TaskFilter.SCHEDULED -> "没有已安排的任务"
        TaskFilter.INBOX -> "没有待安排任务"
        TaskFilter.DONE -> "还没有已完成任务"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 30.dp, bottom = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        TaskEmptyIllustration()
        Spacer(Modifier.height(18.dp))
        Text(
            title,
            color = colors.ink,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
            lineHeight = 31.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "记录要做的事，时间可以稍后安排",
            color = colors.muted,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(24.dp))
        PrimaryButton(text = "＋ 添加任务", modifier = Modifier.width(240.dp)) { onAdd() }
    }
}

/** 空状态插画：圆角文档卡（三组圆点 + 细线）+ 右下角蓝色加号徽标，纯 Compose 绘制。 */
@Composable
private fun TaskEmptyIllustration() {
    val colors = LocalKexuColors.current
    val docShape = RoundedCornerShape(15.dp)
    val docLine = colors.accent.copy(alpha = 0.28f)
    val docRing = colors.accent.copy(alpha = 0.4f)
    Box(Modifier.size(78.dp)) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .size(width = 64.dp, height = 72.dp)
                .clip(docShape)
                .background(colors.surface2)
                .border(1.5.dp, colors.accent.copy(alpha = 0.18f), docShape)
                .padding(start = 9.dp, end = 10.dp, top = 15.dp, bottom = 15.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            repeat(3) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .border(1.2.dp, docRing, CircleShape)
                    )
                    Spacer(Modifier.width(7.dp))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(2.dp)
                            .background(docLine, RoundedCornerShape(1.dp))
                    )
                }
            }
        }
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .size(34.dp)
                .clip(CircleShape)
                .background(colors.accent),
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(width = 13.dp, height = 2.5.dp)
                    .background(Color.White, RoundedCornerShape(2.dp))
            )
            Box(
                Modifier
                    .size(width = 2.5.dp, height = 13.dp)
                    .background(Color.White, RoundedCornerShape(2.dp))
            )
        }
    }
}

/** 智能排程入口：accent 眉标 + 20sp 主句 + 右侧 44dp 圆形箭头，点击去规划页。 */
@Composable
private fun PlanningEntry(onGoPlanning: () -> Unit) {
    val colors = LocalKexuColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onGoPlanning)
            .padding(vertical = 24.dp)
    ) {
        Text("智能排程", color = colors.accent, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "把任务放进空闲时间",
                color = colors.ink,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(colors.surface2),
                contentAlignment = Alignment.Center
            ) {
                Text("↗", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** 长期学习：进行中目标数 + 目标概览 + 已有目标卡（保留完成本周/长按删除/进度条）+ 创建入口。 */
@Composable
private fun LongTermSection(
    goals: List<Goal>,
    onAddGoal: () -> Unit,
    onGoPlanning: () -> Unit,
    onAdvanceGoal: (Goal) -> Unit,
    onLongPressGoal: (Goal) -> Unit
) {
    val colors = LocalKexuColors.current
    val inProgress = goals.count { it.progress < 100 }
    val firstGoal = goals.firstOrNull()
    val summaryTitle = firstGoal?.title ?: "为长期目标留出时间"
    val summaryMeta = firstGoal?.let {
        "第 ${it.currentWeek}/${it.totalWeeks} 周 · 本周 ${it.doneHours}/${it.weeklyHours} 小时"
    } ?: "Python、算法、英语……按周推进"
    Column(Modifier.fillMaxWidth()) {
        Spacer(Modifier.height(26.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "长期学习",
                color = colors.ink,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.6).sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                "↗",
                color = colors.ink2,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onGoPlanning)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(80.dp), contentAlignment = Alignment.Center) {
                Text(
                    inProgress.toString(),
                    color = colors.ok,
                    fontSize = 44.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.8).sp,
                    lineHeight = 46.sp
                )
            }
            Spacer(Modifier.width(18.dp))
            Text(
                summaryTitle,
                color = colors.ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.3).sp,
                lineHeight = 26.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(3.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(80.dp), contentAlignment = Alignment.Center) {
                Text("进行中", color = colors.muted, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.width(18.dp))
            Text(
                summaryMeta,
                color = colors.muted,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
        goals.forEachIndexed { index, goal ->
            if (index > 0) Spacer(Modifier.height(6.dp))
            GoalSpot(
                goal = goal,
                onAdvance = { onAdvanceGoal(goal) },
                onLongClick = { onLongPressGoal(goal) }
            )
        }
        Spacer(Modifier.height(22.dp))
        Row(
            Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onAddGoal)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(22.dp)
                    .border(1.5.dp, colors.ok, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text("+", color = colors.ok, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(9.dp))
            Text(
                "创建长期目标",
                color = colors.ok,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
        }
        Spacer(Modifier.height(6.dp))
    }
}

/** V11 priority-spot：醒目的浅橙卡 + 右侧优先级圆环。 */
@Composable
private fun PrioritySpot(task: Task, state: AppUiState, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    val spotBg = if (colors.dark) Color(0xFF3A2D20) else Color(0xFFF7E9D9)
    val spotInk = if (colors.dark) Color(0xFFF2D2A9) else Color(0xFF744620)
    val spotRing = if (colors.dark) Color(0xFF8C6C4A) else Color(0xFFB58354)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(spotBg)
            .clickable(onClick = onClick)
            .padding(20.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                priorityTag(task),
                color = spotInk.copy(alpha = 0.8f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(11.dp))
            Text(
                task.title,
                color = spotInk,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 29.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(11.dp))
            Text(
                priorityBottomText(task, state),
                color = spotInk,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box(
            Modifier
                .size(62.dp)
                .border(2.dp, spotRing, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(priorityShort(task.priority), color = spotInk, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/** V11 task-item：24dp 圆形勾选 + 17sp 标题 + 合并成一行 14sp meta + 右侧状态。 */
@Composable
private fun TaskRow(
    task: Task,
    state: AppUiState,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val colors = LocalKexuColors.current
    val tone = toneOf(state.courseColor(task.courseId), colors.dark)
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(if (task.done) colors.ok else Color.Transparent)
                .border(
                    2.dp,
                    if (task.done) colors.ok else if (colors.dark) colors.muted else Color(0xFFA9B5C3),
                    CircleShape
                )
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            if (task.done) {
                Icon(Icons.Filled.Check, contentDescription = "标记未完成", tint = Color.White, modifier = Modifier.size(15.dp))
            }
        }
        Spacer(Modifier.width(13.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                color = if (task.done) colors.muted else colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 24.sp,
                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Text(
                taskMeta(task, state, tone.ink),
                color = colors.body,
                fontSize = 14.sp,
                lineHeight = 21.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            when {
                task.done -> "已完成"
                task.week != null -> "已安排"
                else -> "待规划"
            },
            color = when {
                task.done -> colors.muted
                task.week != null -> colors.accent
                else -> colors.warn
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
    HorizontalDivider(color = colors.line)
}

/** V11 goal-spot：大数字 + 17sp 标题 + 14sp 进度信息 + 细进度条。 */
@Composable
private fun GoalSpot(goal: Goal, onAdvance: () -> Unit, onLongClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = {}, onLongClick = onLongClick)
            .padding(top = 20.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            "${goal.progress}%",
            color = colors.ok,
            fontSize = 51.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-2.5).sp,
            lineHeight = 52.sp
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                goal.title,
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "第 ${goal.currentWeek} 周 · 本周 ${goal.doneHours}/${goal.weeklyHours} 小时",
                color = colors.body,
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(9.dp))
            ProgressBar(goal.progress / 100f, colors.ok)
            Spacer(Modifier.height(10.dp))
            Text(
                "完成本周 →",
                color = colors.accent2,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onAdvance)
                    .padding(vertical = 4.dp)
            )
        }
    }
}

@Composable
private fun ProgressBar(fraction: Float, color: Color) {
    val colors = LocalKexuColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(7.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(colors.surface2)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(color, RoundedCornerShape(4.dp))
        )
    }
}

private fun priorityRank(priority: String): Int = when (priority) {
    "high" -> 0
    "medium" -> 1
    else -> 2
}

private fun priorityShort(priority: String): String = when (priority) {
    "high" -> "高"
    "low" -> "低"
    else -> "中"
}

private fun priorityTag(task: Task): String = buildList {
    add("优先处理")
    val due = task.deadline
    val weekNo = task.week
    val weekdayNo = task.weekday
    if (due != null) {
        add("${due.monthValue}月${due.dayOfMonth}日截止")
    } else if (weekNo != null && weekdayNo != null) {
        add("第 $weekNo 周 ${KexuCore.WEEKDAY_CN[weekdayNo]}")
    }
}.joinToString(" · ")

private fun priorityBottomText(task: Task, state: AppUiState): String {
    val weekNo = task.week
    val weekdayNo = task.weekday
    val from = task.from
    val to = task.to
    if (weekNo != null && weekdayNo != null && from != null && to != null) {
        return "第 $weekNo 周 · ${KexuCore.WEEKDAY_CN[weekdayNo]} $from–$to"
    }
    return "还需 ${task.estimatedMinutes ?: state.prefs.defaultTaskMinutes} 分钟"
}

private fun taskMeta(task: Task, state: AppUiState, courseColor: Color): AnnotatedString = buildAnnotatedString {
    val courseName = state.courseById(task.courseId)?.name
    if (courseName != null) {
        withStyle(SpanStyle(color = courseColor, fontWeight = FontWeight.SemiBold)) { append(courseName) }
        append(" · ")
    }
    val weekNo = task.week
    val weekdayNo = task.weekday
    val from = task.from
    val to = task.to
    val parts = buildList {
        if (weekNo != null && weekdayNo != null && from != null && to != null) {
            add("第 $weekNo 周 ${KexuCore.WEEKDAY_CN[weekdayNo]} $from–$to")
        } else {
            add("预计 ${KexuCore.formatDuration(task.estimatedMinutes)}")
        }
        task.deadline?.let { add("${it.monthValue}月${it.dayOfMonth}日截止") }
        if (task.isRepeating) add(RecurringTaskPlanner.label(task.repeatKind))
    }
    append(parts.joinToString(" · "))
}

@Composable
fun AddTaskDialog(
    state: AppUiState,
    initialWeekday: Int? = null,
    initialFrom: String? = null,
    initialTo: String? = null,
    initialWeek: Int? = null,
    onDismiss: () -> Unit,
    onSave: (
        title: String,
        courseId: String?,
        priority: String,
        minutes: Int,
        deadline: LocalDate?,
        week: Int?,
        weekday: Int?,
        from: String?,
        to: String?,
        repeatKind: String,
        repeatUntil: LocalDate?
    ) -> Unit
) {
    val colors = LocalKexuColors.current
    var title by remember { mutableStateOf("") }
    var courseId by remember { mutableStateOf<String?>(null) }
    var priority by remember { mutableStateOf(state.prefs.defaultPriority) }
    var minutes by remember { mutableStateOf(state.prefs.defaultTaskMinutes) }
    var deadline by remember { mutableStateOf("") }
    var repeatKind by remember { mutableStateOf(RecurringTaskPlanner.NONE) }
    var repeatUntil by remember { mutableStateOf("") }
    var showCoursePicker by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scheduleEnabled by remember { mutableStateOf(initialFrom != null && initialTo != null) }
    var weekday by remember { mutableStateOf(initialWeekday ?: KexuCore.weekdayIndex(LocalDate.now())) }
    var from by remember { mutableStateOf(initialFrom ?: "") }
    var to by remember { mutableStateOf(initialTo ?: "") }

    KexuDialog(title = "新建任务", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            KTextField(value = title, onValueChange = { title = it }, label = "任务标题", placeholder = "需要完成什么？")
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .border(1.dp, colors.line, RoundedCornerShape(9.dp))
                    .clickable { showCoursePicker = true }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("关联课程", color = colors.muted, fontSize = 13.sp)
                Text(
                    state.courseById(courseId)?.name ?: "不关联",
                    color = colors.ink,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))
            Text("优先级", color = colors.muted, fontSize = 13.sp)
            Spacer(Modifier.height(5.dp))
            KSegmented(
                options = listOf("low" to "低", "medium" to "中", "high" to "高"),
                selected = priority,
                onSelect = { priority = it }
            )
            Spacer(Modifier.height(12.dp))
            Text("预计时长：${KexuCore.formatDuration(minutes)}", color = colors.muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(25, 45, 60, 90, 120).forEach { value ->
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (minutes == value) colors.accentSoft else colors.surface2)
                            .clickable { minutes = value }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("$value", color = if (minutes == value) colors.accent else colors.muted, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            KTextField(
                value = deadline,
                onValueChange = { deadline = it },
                label = "截止日期（可选，YYYY-MM-DD）",
                placeholder = "2026-11-02"
            )

            Spacer(Modifier.height(12.dp))
            Text("重复", color = colors.muted, fontSize = 13.sp)
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
                KTextField(
                    value = repeatUntil,
                    onValueChange = { repeatUntil = it },
                    label = "重复截止（可选，YYYY-MM-DD）",
                    placeholder = "2026-12-31"
                )
                Text(
                    "完成后自动生成下一条",
                    color = colors.muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .clickable { scheduleEnabled = !scheduleEnabled }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(20.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .border(1.5.dp, if (scheduleEnabled) colors.accent else colors.line, RoundedCornerShape(6.dp))
                        .background(if (scheduleEnabled) colors.accent else Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    if (scheduleEnabled) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
                }
                Spacer(Modifier.width(8.dp))
                Column {
                    Text("自己安排时间", color = colors.ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Text("不勾选则保存到「待安排」", color = colors.muted, fontSize = 13.sp)
                }
            }
            if (scheduleEnabled) {
                Spacer(Modifier.height(8.dp))
                Text("星期", color = colors.muted, fontSize = 13.sp)
                Spacer(Modifier.height(5.dp))
                KexuCore.WEEKDAY_CN.chunked(4).forEach { chunk ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chunk.forEachIndexed { idx, label ->
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
                                Text(label, color = if (weekday == index) colors.accent else colors.muted, fontSize = 13.sp)
                            }
                        }
                        if (chunk.size < 4) Spacer(Modifier.weight((4 - chunk.size).toFloat()))
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    KTextField(from, { from = it }, label = "开始", placeholder = "14:00", modifier = Modifier.weight(1f))
                    Text("—", color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 8.dp))
                    KTextField(to, { to = it }, label = "结束", placeholder = "15:00", modifier = Modifier.weight(1f))
                }
                Text("时间格式 HH:mm", color = colors.muted, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }

            if (error != null) {
                Text(error!!, color = colors.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("保存任务", modifier = Modifier.fillMaxWidth()) {
                if (title.isBlank()) {
                    error = "请填写任务标题"
                } else {
                    val parsed = if (deadline.isBlank()) null else runCatching { LocalDate.parse(deadline.trim()) }.getOrNull()
                    val parsedRepeatUntil = if (repeatUntil.isBlank()) null else runCatching { LocalDate.parse(repeatUntil.trim()) }.getOrNull()
                    when {
                        deadline.isNotBlank() && parsed == null -> error = "截止日期格式应为 YYYY-MM-DD"
                        repeatUntil.isNotBlank() && parsedRepeatUntil == null -> error = "重复截止日期格式应为 YYYY-MM-DD"
                        repeatKind != RecurringTaskPlanner.NONE && !scheduleEnabled && parsed == null ->
                            error = "重复任务需要设置截止日期或自己安排时间，否则无法计算下一次"
                        scheduleEnabled -> {
                            val fromMin = KexuCore.minutesOf(from)
                            val toMin = KexuCore.minutesOf(to)
                            if (fromMin < 0 || toMin < 0) {
                                error = "时间格式应为 HH:mm"
                            } else if (toMin <= fromMin) {
                                error = "结束时间需要晚于开始时间"
                            } else {
                                val todayWeek = KexuCore.weekdayIndex(LocalDate.now())
                                val baseWeek = initialWeek ?: state.currentWeek()
                                val targetWeek = if (initialWeek != null) baseWeek else if (weekday < todayWeek) baseWeek + 1 else baseWeek
                                onSave(
                                    title.trim(), courseId, priority, minutes, parsed, targetWeek, weekday,
                                    KexuCore.timeOf(fromMin), KexuCore.timeOf(toMin), repeatKind, parsedRepeatUntil
                                )
                            }
                        }
                        else -> onSave(
                            title.trim(), courseId, priority, minutes, parsed, null, null, null, null,
                            repeatKind, parsedRepeatUntil
                        )
                    }
                }
            }
        }
    }

    if (showCoursePicker) {
        KexuDialog(title = "选择课程", onDismiss = { showCoursePicker = false }) {
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            courseId = null
                            showCoursePicker = false
                        }
                        .padding(vertical = 10.dp)
                ) { Text("不关联课程", color = colors.ink, fontSize = 14.sp) }
                state.courses.forEach { course ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                courseId = course.id
                                showCoursePicker = false
                            }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(toneOf(course.colorHex, colors.dark).base))
                        Spacer(Modifier.width(8.dp))
                        Text(course.name, color = colors.ink, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}
