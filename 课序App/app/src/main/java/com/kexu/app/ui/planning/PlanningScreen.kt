package com.kexu.app.ui.planning

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.Goal
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Task
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.KexuViewModel
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.captionSize
import com.kexu.app.ui.components.PageHeader
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.goals.AddGoalDialog
import com.kexu.app.ui.theme.LocalKexuColors
import java.time.LocalDate
import java.util.Locale

private data class PlanSuggestion(
    val date: LocalDate,
    val week: Int,
    val weekday: Int,
    val from: String,
    val to: String,
    val reason: String
)

/** 本周节奏的星期字母（一~日），与 KexuCore.WEEKDAY_CN 的下标一致（周一 = 0）。 */
private val RHYTHM_DAY_LETTERS = listOf("一", "二", "三", "四", "五", "六", "日")

/** 小号英文大写装饰：11sp、拉开字距、muted 55% 透明度，只做层级点缀，不抢主标题。 */
@Composable
private fun EnglishLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        color = LocalKexuColors.current.muted.copy(alpha = 0.55f),
        fontSize = captionSize(9f),
        lineHeight = captionSize(10f),
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.1.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

/**
 * 规划页（V11 之后的新样式）：
 * - 没有待安排任务：深色 hero 空状态 + 本周节奏（7 日节奏条 + 三列统计）+ 开始长期学习计划 + 排程策略；
 * - 有待安排任务：保留 V11 的当前任务、建议 hero、编号推荐安排、换个时间/换任务/确认安排与长期目标，
 *   并在推荐安排之后追加本周节奏与统计。
 *
 * 候选任务只包含「未完成且尚未安排时间」的任务：确认安排后任务会从候选消失，
 * 全部排完时自动进入空状态，不再循环回到已经排好的任务。
 *
 * [onGoTasks] 由 KexuApp 接线（跳转待办页）；为 null 时点击只提示，不影响编译。
 */
@Composable
fun PlanningScreen(
    state: AppUiState,
    vm: KexuViewModel,
    showMessage: (String) -> Unit,
    onGoSettings: (() -> Unit)? = null,
    onGoTasks: (() -> Unit)? = null
) {
    val colors = LocalKexuColors.current
    var selectedTaskId by remember { mutableStateOf<String?>(null) }
    var showTaskPicker by remember { mutableStateOf(false) }
    var showAddGoal by remember { mutableStateOf(false) }
    var variant by remember { mutableIntStateOf(0) }

    // 待安排 = 未完成且 week == null（还没排进日程）；已安排/已完成的任务不再进入候选。
    val candidates = state.tasks.filter { !it.done && it.week == null }
    val focus = candidates.firstOrNull { it.id == selectedTaskId } ?: candidates.firstOrNull()
    val allSuggestions = remember(focus, state.periods, state.courses, state.tasks, state.prefs) {
        focus?.let { buildSuggestions(state, it) }.orEmpty()
    }
    val suggestions = remember(allSuggestions, variant) {
        if (allSuggestions.isEmpty()) emptyList() else {
            val start = variant % allSuggestions.size
            List(minOf(2, allSuggestions.size)) { allSuggestions[(start + it) % allSuggestions.size] }
        }
    }
    val hasConflict = remember(suggestions, state.courses, state.exceptions, state.periods, state.semester) {
        suggestions.any { suggestion ->
            val week = KexuCore.weekIndexForDate(state.semester.startDate, suggestion.date)
            val weekday = KexuCore.weekdayIndex(suggestion.date)
            val from = KexuCore.minutesOf(suggestion.from)
            val to = KexuCore.minutesOf(suggestion.to)
            week in 1..state.semester.totalWeeks &&
                state.occurrencesOn(week, weekday).any { occurrence ->
                    if (occurrence.cancelled) {
                        false
                    } else {
                        val start = state.periods.getOrNull(occurrence.startPeriod - 1)?.fromMin ?: -1
                        val end = state.periods.getOrNull(occurrence.endPeriod - 1)?.toMin ?: -1
                        start >= 0 && end > start && from < end && to > start
                    }
                }
        }
    }
    val estimated = focus?.estimatedMinutes ?: state.prefs.defaultTaskMinutes
    val heroTitle = when {
        focus == null -> "本周还没有待安排的任务"
        suggestions.isEmpty() -> focus.title
        else -> "${suggestions.size} 次专注时间"
    }
    val heroBody = when {
        focus == null -> "先在「待办」页添加任务，课序会结合课程与空闲时间给出安排建议。"
        suggestions.isEmpty() -> "《${focus.title}》预计 ${KexuCore.formatDuration(estimated)}，暂未找到合适空档，可换个时间或调整可安排时段。"
        suggestions.size > 1 -> "《${focus.title}》预计 ${KexuCore.formatDuration(estimated)}，建议拆成 ${suggestions.size} 段完成。"
        else -> "《${focus.title}》预计 ${KexuCore.formatDuration(estimated)}，安排在一个完整空闲段。"
    }
    val goSchedule: () -> Unit = {
        if (onGoSettings != null) onGoSettings.invoke()
        else showMessage("可在「设置 → 日程与自动规划」中调整")
    }
    val goTasks: () -> Unit = {
        if (onGoTasks != null) onGoTasks.invoke()
        else showMessage("可先在「待办」页添加任务")
    }
    val goGoals: () -> Unit = {
        if (onGoTasks != null) onGoTasks.invoke()
        else showMessage("可在「待办」页查看长期学习目标")
    }

    // 本周节奏与统计：口径 = 当前教学周内已安排的任务 / 目标。
    val todayIndex = KexuCore.weekdayIndex(LocalDate.now())
    val weekNo = state.currentWeek()
    val weekTasks = state.tasks.filter { it.week == weekNo }
    val dayHasCourse = (0..6).map { day -> state.occurrencesOn(weekNo, day).any { !it.cancelled } }
    val dayHasTask = (0..6).map { day -> weekTasks.any { it.weekday == day } }
    val activeGoals = state.goals.size
    val plannedHours = weekHoursText(weekTasks.sumOf { it.estimatedMinutes ?: 0 })
    val completionPercent = if (weekTasks.isEmpty()) 0 else weekTasks.count { it.done } * 100 / weekTasks.size

    LazyColumn(
        Modifier.fillMaxWidth().background(colors.surface),
        contentPadding = PaddingValues(start = 23.dp, end = 23.dp, top = 22.dp, bottom = 32.dp)
    ) {
        item {
            PageHeader(eyebrow = "智能时间规划", title = "规划")
        }

        // ---------------------------------------------------------------- 空状态
        if (focus == null) {
            item {
                EnglishLabel("PLAN")
                Spacer(Modifier.height(2.dp))
                EmptyPlanHero(onAddTask = goTasks)
            }
            item {
                WeekRhythmBlock(
                    dayHasCourse = dayHasCourse,
                    dayHasTask = dayHasTask,
                    todayIndex = todayIndex,
                    activeGoals = activeGoals,
                    plannedHours = plannedHours,
                    completionPercent = completionPercent,
                    onLongTermClick = goGoals,
                    onStartGoal = { showAddGoal = true }
                )
            }
            item {
                ScheduleStrategyBlock(state = state, onAdjust = goSchedule)
            }
            return@LazyColumn
        }

        // ---------------------------------------------------------------- 推荐安排
        val activeTask = focus
        item {
            CurrentTaskRow(title = activeTask.title) { showTaskPicker = true }
            Spacer(Modifier.height(12.dp))
        }
        item {
            EnglishLabel("PLAN")
            Spacer(Modifier.height(2.dp))
            PlanHero(over = "本周建议 · 让计划真正落地", title = heroTitle, body = heroBody)
            PlanSectionHead(title = "推荐安排") {
                Text(
                    text = when {
                        suggestions.isEmpty() -> "暂无空档"
                        hasConflict -> "有课程冲突"
                        else -> "无课程冲突"
                    },
                    color = when {
                        suggestions.isEmpty() -> colors.muted
                        hasConflict -> colors.warn
                        else -> colors.ok
                    },
                    fontSize = captionSize(12f),
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        if (suggestions.isEmpty()) {
            item {
                Text(
                    "未来一周没有找到足够长的空闲时间，可在设置中调整每日可安排时间。",
                    color = colors.body,
                    fontSize = captionSize(13f),
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 6.dp)
                )
            }
        } else {
            items(suggestions.size) { index ->
                PlanBlock(
                    index = index,
                    suggestion = suggestions[index],
                    last = index == suggestions.lastIndex,
                    taskTitle = activeTask.title
                )
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                SoftButton("换个时间", modifier = Modifier.weight(1f)) {
                    if (allSuggestions.isNotEmpty()) {
                        variant += 1
                    } else {
                        showMessage("当前没有可换的空闲时间")
                    }
                }
                PrimaryButton("确认安排", modifier = Modifier.weight(1f)) {
                    val first = suggestions.firstOrNull()
                    if (first != null) {
                        vm.scheduleTask(activeTask, first.week, first.weekday, first.from, first.to)
                        selectedTaskId = null
                        variant = 0
                        showMessage("已加入日程：${first.date.monthValue} 月 ${first.date.dayOfMonth} 日 ${first.from}–${first.to}")
                    } else {
                        showMessage("当前没有可用时间")
                    }
                }
            }
        }

        // ------------------------------------------------- 本周节奏（推荐安排之后追加）
        item {
            WeekRhythmBlock(
                dayHasCourse = dayHasCourse,
                dayHasTask = dayHasTask,
                todayIndex = todayIndex,
                activeGoals = activeGoals,
                plannedHours = plannedHours,
                completionPercent = completionPercent,
                onLongTermClick = goGoals,
                onStartGoal = { showAddGoal = true }
            )
        }

        item {
            PlanSectionHead(title = "长期目标") {
                Text("每周持续推进", color = colors.muted, fontSize = captionSize(12f), fontWeight = FontWeight.Medium)
            }
            GoalSpot(goal = state.goals.firstOrNull())
        }

        item {
            ScheduleStrategyBlock(state = state, onAdjust = goSchedule)
        }
    }

    if (showTaskPicker) {
        KexuDialog(title = "选择要安排的任务", onDismiss = { showTaskPicker = false }) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) {
                    Text("没有待安排的任务。", color = colors.muted, fontSize = captionSize(13f))
                }
                candidates.forEach { task ->
                    val selected = task.id == focus?.id
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable {
                                selectedTaskId = task.id
                                variant = 0
                                showTaskPicker = false
                            }
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title,
                                color = if (selected) colors.accent else colors.ink,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "待安排 · 预计 ${KexuCore.formatDuration(task.estimatedMinutes)}",
                                color = colors.muted,
                                fontSize = captionSize(12f),
                                modifier = Modifier.padding(top = 3.dp)
                            )
                        }
                        if (selected) {
                            Text("✓", color = colors.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showAddGoal) {
        AddGoalDialog(
            onDismiss = { showAddGoal = false },
            onSave = { title, weeks, hours, next ->
                vm.addGoal(title, weeks, hours, next)
                showAddGoal = false
                showMessage("已创建长期学习目标")
            }
        )
    }
}

// ------------------------------------------------------------------ 空状态 / 公共新区块

/**
 * 空状态深色 hero：眉标「本周建议」+ 30sp「让计划真正落地」+ 15sp 说明 + 右下「添加任务 ↗」。
 * 没有任何待安排任务时替代推荐安排，避免出现空转的建议。
 */
@Composable
private fun EmptyPlanHero(onAddTask: () -> Unit) {
    val colors = LocalKexuColors.current
    val foreground = if (colors.dark) colors.ink else Color.White
    PlanHero(
        over = "本周建议",
        title = "让计划真正落地",
        body = "还没有待安排任务，添加后会自动生成建议。",
        footer = {
            Row(
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(onClick = onAddTask)
                    .padding(horizontal = 2.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("添加任务", color = foreground, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.width(7.dp))
                Text("↗", color = foreground, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            }
        }
    )
}

/**
 * 本周节奏：24sp 标题 + 右侧「长期学习」16sp ok 链接；7 列星期 + 10dp 节奏条；
 * 下方三列统计（活跃目标 / 本周计划 / 已完成）与「＋ 开始长期学习计划」入口。
 */
@Composable
private fun WeekRhythmBlock(
    dayHasCourse: List<Boolean>,
    dayHasTask: List<Boolean>,
    todayIndex: Int,
    activeGoals: Int,
    plannedHours: String,
    completionPercent: Int,
    onLongTermClick: () -> Unit,
    onStartGoal: () -> Unit
) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        PlanSectionHead(title = "本周节奏", eyebrow = "WEEKLY RHYTHM") {
            Text(
                "长期学习",
                color = colors.ok,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onLongTermClick)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            RHYTHM_DAY_LETTERS.forEachIndexed { day, letter ->
                val hasCourse = dayHasCourse.getOrElse(day) { false }
                val hasTask = dayHasTask.getOrElse(day) { false }
                // 有课 / 有安排的当天用 ok 绿或主色标记；无安排用浅底；今天加深为 ok 绿方便定位。
                val barColor = when {
                    day == todayIndex -> colors.ok
                    hasCourse -> colors.ok
                    hasTask -> colors.accent
                    else -> colors.surface2
                }
                Column(
                    Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        letter,
                        color = colors.muted,
                        fontSize = captionSize(12f),
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(9.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(barColor)
                    )
                }
            }
        }
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            RhythmStat(label = "活跃目标", value = activeGoals.toString())
            RhythmStat(label = "本周计划", value = plannedHours, unit = "小时")
            RhythmStat(label = "已完成", value = "$completionPercent%", alignEnd = true)
        }
        Spacer(Modifier.height(22.dp))
        StartGoalAction(onClick = onStartGoal)
        Spacer(Modifier.height(6.dp))
    }
}

/** 三列统计中的一列：12sp muted 标签 + 26sp 数字（可带 12sp 单位）。 */
@Composable
private fun RhythmStat(
    label: String,
    value: String,
    unit: String? = null,
    alignEnd: Boolean = false
) {
    val colors = LocalKexuColors.current
    Column(horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        Text(label, color = colors.muted, fontSize = captionSize(12f), fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                color = colors.ink,
                fontSize = 26.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1).sp,
                lineHeight = 30.sp
            )
            if (unit != null) {
                Spacer(Modifier.width(5.dp))
                Text(
                    unit,
                    color = colors.muted,
                    fontSize = captionSize(12f),
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
        }
    }
}

/** 「⊕ 开始长期学习计划」17sp ok 绿色文字入口，点击打开新建长期目标对话框。 */
@Composable
private fun StartGoalAction(onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(20.dp)
                .border(1.5.dp, colors.ok, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("＋", color = colors.ok, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(9.dp))
        Text(
            "开始长期学习计划",
            color = colors.ok,
            fontSize = 17.sp,
            fontWeight = FontWeight.ExtraBold
        )
    }
}

/** 排程策略：细分隔线 + 英文眉标 + 24sp 标题 +「调整 ›」+ 一行 13sp muted 摘要（取自设置项）。 */
@Composable
private fun ScheduleStrategyBlock(state: AppUiState, onAdjust: () -> Unit) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.padding(top = 24.dp), color = colors.line)
        PlanSectionHead(title = "排程策略", eyebrow = "SCHEDULING") {
            Text(
                "调整 ›",
                color = colors.accent,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAdjust)
                    .padding(horizontal = 2.dp, vertical = 2.dp)
            )
        }
        Text(
            "可安排 ${state.prefs.availabilityStart}–${state.prefs.availabilityEnd} · " +
                "默认任务 ${state.prefs.defaultTaskMinutes} 分钟 · " +
                "专注上限 ${state.prefs.focusMinutes} 分钟",
            color = colors.muted,
            fontSize = captionSize(13f),
            lineHeight = 19.sp
        )
        Spacer(Modifier.height(6.dp))
    }
}

// ------------------------------------------------------------------ V11 私有视觉组件

/**
 * V11 深色建议卡（plan-hero）：眉标 + 30sp 主标题 + 15sp 说明 + 可选右下操作行 + 极淡装饰圆。
 * 深色主题下背景用 colors.surface2。
 */
@Composable
private fun PlanHero(
    over: String,
    title: String,
    body: String,
    footer: (@Composable () -> Unit)? = null
) {
    val colors = LocalKexuColors.current
    val background = if (colors.dark) colors.surface2 else Color(0xFF1D304D)
    val foreground = if (colors.dark) colors.ink else Color.White
    val overColor = if (colors.dark) colors.muted else Color(0xFFC6D8F0)
    val bodyColor = if (colors.dark) colors.body else Color(0xFFD9E5F4)
    Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(23.dp)).background(background)) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 64.dp, y = (-88).dp)
                .size(180.dp)
                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 102.dp, y = (-126).dp)
                .size(256.dp)
                .border(1.dp, Color.White.copy(alpha = 0.04f), CircleShape)
        )
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 23.dp, bottom = 25.dp)) {
            Text(over, color = overColor, fontSize = captionSize(12f), fontWeight = FontWeight.SemiBold)
            Text(
                title,
                color = foreground,
                fontSize = 30.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.3).sp,
                lineHeight = 37.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 11.dp, bottom = 7.dp)
            )
            Text(body, color = bodyColor, fontSize = 15.sp, lineHeight = 22.sp)
            if (footer != null) {
                Spacer(Modifier.height(16.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    footer()
                }
            }
        }
    }
}

/** V11 section-head：小号英文眉标（可选）+ 24sp 标题 + 右侧链接/说明。 */
@Composable
private fun PlanSectionHead(
    title: String,
    eyebrow: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = LocalKexuColors.current
    Row(
        Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Column(Modifier.weight(1f)) {
            if (eyebrow != null) {
                EnglishLabel(eyebrow)
                Spacer(Modifier.height(5.dp))
            }
            Text(
                title,
                color = colors.ink,
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.65).sp
            )
        }
        trailing?.invoke()
    }
}

/** 「当前任务：xxx ›」入口：点击复用任务选择弹窗（换任务）。 */
@Composable
private fun CurrentTaskRow(title: String, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("当前任务：", color = colors.muted, fontSize = captionSize(12f))
        Spacer(Modifier.width(2.dp))
        Text(
            title,
            color = colors.ink,
            fontSize = captionSize(),
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            "›",
            color = colors.muted,
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** V11 plan-block：58dp 编号列 + 日期 / 24sp 时间 / 16sp 任务名，块间左侧细竖线引导。 */
@Composable
private fun PlanBlock(index: Int, suggestion: PlanSuggestion, last: Boolean, taskTitle: String) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                if (!last) {
                    val x = 38.dp.toPx()
                    drawLine(
                        color = colors.line,
                        start = Offset(x, 38.dp.toPx()),
                        end = Offset(x, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                }
            }
    ) {
        Text(
            (index + 1).toString().padStart(2, '0'),
            color = colors.accent,
            fontSize = 28.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-1).sp,
            modifier = Modifier.width(58.dp)
        )
        Spacer(Modifier.width(15.dp))
        Column(Modifier.weight(1f).padding(bottom = 25.dp)) {
            Text(
                "${KexuCore.WEEKDAY_CN[suggestion.weekday]} · ${KexuCore.fmtCN(suggestion.date)}",
                color = colors.muted,
                fontSize = captionSize(13f),
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "${suggestion.from} — ${suggestion.to}",
                color = colors.ink,
                fontSize = 24.sp,
                fontWeight = FontWeight(820),
                letterSpacing = (-0.8).sp,
                modifier = Modifier.padding(top = 6.dp, bottom = 5.dp)
            )
            Text(
                if (index == 0) taskTitle else "继续 · $taskTitle",
                color = colors.accent2,
                fontSize = 16.sp,
                fontWeight = FontWeight(760),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** V11 btnsoft：浅底圆角 12dp 的次级按钮。 */
@Composable
private fun SoftButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Box(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** V11 goal-spot：51sp 进度数字 + 目标名 + 13sp 进度信息 + 进度条。 */
@Composable
private fun GoalSpot(goal: Goal?) {
    val colors = LocalKexuColors.current
    if (goal == null) {
        Text(
            "还没有长期目标，可在待办页添加并每周推进。",
            color = colors.body,
            fontSize = captionSize(13f),
            lineHeight = 20.sp
        )
        return
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            "${goal.progress}%",
            color = colors.ok,
            fontSize = 51.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-2).sp,
            lineHeight = 51.sp
        )
        Spacer(Modifier.width(17.dp))
        Column(Modifier.weight(1f).padding(bottom = 3.dp)) {
            Text(
                goal.title,
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.3).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "第 ${goal.currentWeek} / ${goal.totalWeeks} 周 · 本周已学 ${goal.doneHours} / ${goal.weeklyHours} 小时",
                color = colors.body,
                fontSize = captionSize(13f)
            )
            Spacer(Modifier.height(9.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(7.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(colors.line)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(goal.progress.coerceIn(0, 100) / 100f)
                        .fillMaxHeight()
                        .background(colors.ok)
                )
            }
        }
    }
}

/** 本周计划时长：满整小时显示整数，否则保留一位小数（如 45 分钟 → 0.8 小时）。 */
private fun weekHoursText(minutes: Int): String {
    if (minutes <= 0) return "0"
    val rest = minutes % 60
    return if (rest == 0) {
        (minutes / 60).toString()
    } else {
        String.format(Locale.US, "%.1f", minutes / 60.0)
    }
}

private fun buildSuggestions(state: AppUiState, task: Task): List<PlanSuggestion> {
    val today = LocalDate.now()
    val out = ArrayList<PlanSuggestion>()
    val block = ((task.estimatedMinutes ?: state.prefs.defaultTaskMinutes).coerceAtLeast(30)).let {
        if (it > 60) 60 else it
    }
    val dayStart = KexuCore.minutesOf(state.prefs.availabilityStart).coerceAtLeast(0)
    val dayEnd = KexuCore.minutesOf(state.prefs.availabilityEnd).let { if (it <= 0) 22 * 60 else it }
    for (offset in 0..9) {
        if (out.size >= 12) break
        val date = today.plusDays(offset.toLong())
        val week = KexuCore.weekIndexForDate(state.semester.startDate, date)
        if (week < 1 || week > state.semester.totalWeeks) continue
        val weekday = KexuCore.weekdayIndex(date)
        val busy = ArrayList<Pair<Int, Int>>()
        KexuCore.effectiveCoursesOnDay(state.courses, state.exceptions, week, weekday).forEach { occurrence ->
            if (occurrence.cancelled) return@forEach
            val start = state.periods.getOrNull(occurrence.startPeriod - 1)?.fromMin ?: return@forEach
            val end = state.periods.getOrNull(occurrence.endPeriod - 1)?.toMin ?: return@forEach
            busy.add(start to end)
        }
        state.tasks.filter { it.week == week && it.weekday == weekday && it.from != null && it.to != null }
            .forEach { t ->
                val s = KexuCore.minutesOf(t.from!!)
                val e = KexuCore.minutesOf(t.to!!)
                if (s >= 0 && e > s) busy.add(s to e)
            }
        busy.sortBy { it.first }
        var cursor = dayStart
        for (interval in busy) {
            if (interval.first - cursor >= block) {
                out.add(PlanSuggestion(date, week, weekday, KexuCore.timeOf(cursor), KexuCore.timeOf(cursor + block), "课程之间的完整空闲段，距离截止仍有余量。"))
            }
            cursor = maxOf(cursor, interval.second)
        }
        if (out.size < 12 && dayEnd - cursor >= block) {
            out.add(PlanSuggestion(date, week, weekday, KexuCore.timeOf(cursor), KexuCore.timeOf(cursor + block), "当日末尾的空闲时间，适合安排收尾工作。"))
        }
    }
    return out.distinctBy { it.date to it.from }
}

