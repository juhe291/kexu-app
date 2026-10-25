package com.kexu.app.ui.today

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.Course
import com.kexu.app.domain.CourseOccurrence
import com.kexu.app.domain.Goal
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.NextCourse
import com.kexu.app.domain.RecurringTaskPlanner
import com.kexu.app.domain.Task
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.KexuViewModel
import com.kexu.app.ui.components.CollapsibleModule
import com.kexu.app.ui.components.EmptyHint
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.captionSize
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.courses.CourseEditDialog
import com.kexu.app.ui.goals.AddGoalDialog
import com.kexu.app.ui.tasks.AddTaskDialog
import com.kexu.app.ui.tasks.TaskEditDialog
import com.kexu.app.ui.theme.LocalFontScales
import com.kexu.app.ui.theme.LocalKexuColors
import com.kexu.app.ui.theme.toneOf
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/** 今天页：date-stand + 下一节课 hero + daypulse + 无图标折叠模块（V11）。 */
@Composable
fun TodayScreen(state: AppUiState, vm: KexuViewModel, onGoPlanning: () -> Unit) {
    val colors = LocalKexuColors.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = LocalDateTime.now()
        }
    }
    val today = now.toLocalDate()

    fun hasContent(date: LocalDate): Boolean {
        val w = state.currentWeek(date)
        val wd = KexuCore.weekdayIndex(date)
        return state.occurrencesOn(w, wd).any { !it.cancelled } ||
            state.tasks.any { it.week == w && it.weekday == wd && it.from != null && it.to != null }
    }

    val displayDate = (0..7).map { today.plusDays(it.toLong()) }.firstOrNull { hasContent(it) } ?: today
    val week = state.currentWeek(displayDate)
    val weekday = KexuCore.weekdayIndex(displayDate)
    val dayTitle = when {
        displayDate == today -> "今天"
        displayDate == today.plusDays(1) -> "明天"
        displayDate == today.plusDays(2) -> "后天"
        else -> KexuCore.WEEKDAY_CN[weekday]
    }
    val semesterLabel = semesterCaption(state.semester.name, displayDate, week)

    val next = remember(now, state.courses, state.periods, state.semester, state.exceptions) {
        KexuCore.nextCourse(now, state.semester, state.courses, state.periods, state.exceptions)
    }
    val todayCourses = remember(week, weekday, state.courses, state.exceptions) {
        state.occurrencesOn(week, weekday).filter { !it.cancelled }
    }
    val todayTasks = remember(state.tasks, week, weekday) {
        state.tasks.filter { it.week == week && it.weekday == weekday && it.from != null && it.to != null }
    }
    val doneTasks = todayTasks.filter { it.done }
    val inbox = remember(state.tasks) { state.tasks.filter { it.week == null && it.from == null && !it.done } }
    val deadlines = remember(state.tasks, today) {
        state.tasks.filter { !it.done }.mapNotNull { task ->
            task.deadline?.let { date -> Triple(task, date, ChronoUnit.DAYS.between(today, date)) }
        }.sortedBy { it.third }
    }
    val plannedHours = todayTasks.filter { !it.done }.sumOf {
        (KexuCore.minutesOf(it.to ?: "") - KexuCore.minutesOf(it.from ?: "")).coerceAtLeast(0)
    } / 60.0

    var detailCourse by remember { mutableStateOf<CourseOccurrence?>(null) }
    var showAddGoal by remember { mutableStateOf(false) }
    var editTask by remember { mutableStateOf<Task?>(null) }
    var editCourse by remember { mutableStateOf<Course?>(null) }
    var showAddTask by remember { mutableStateOf(false) }
    var moduleOpen by remember { mutableStateOf(state.prefs.moduleOpen) }
    LaunchedEffect(state.prefs.moduleOpen) { moduleOpen = state.prefs.moduleOpen }
    val toggleModule: (String) -> Unit = { key ->
        val open = !(moduleOpen[key] ?: false)
        moduleOpen = moduleOpen + (key to open)
        vm.setModuleOpen(key, open)
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface),
        contentPadding = PaddingValues(start = 23.dp, end = 23.dp, top = 22.dp, bottom = 32.dp)
    ) {
        item {
            DateStand(
                dateLine = "${displayDate.monthValue}月${displayDate.dayOfMonth}日　星期${WEEKDAY_FULL[weekday]}",
                title = dayTitle,
                semesterLabel = semesterLabel
            )
        }
        item {
            HeroCard(
                next = next,
                onClick = {
                    next?.let { course ->
                        detailCourse = CourseOccurrence(
                            course = course.course,
                            meeting = course.meeting,
                            week = course.week,
                            weekday = course.weekday,
                            startPeriod = course.startPeriod,
                            endPeriod = course.endPeriod,
                            room = course.room ?: course.meeting.room ?: course.course.room,
                            moved = course.moved
                        )
                    }
                }
            )
            Spacer(Modifier.height(21.dp))
        }
        item {
            DayPulse(
                courses = todayCourses.size,
                tasks = todayTasks.count { !it.done },
                hours = plannedHours
            )
            Spacer(Modifier.height(7.dp))
        }
        item {
            val rows = buildList {
                todayCourses.forEach { add(AgendaItem.CourseItem(it)) }
                todayTasks.forEach { add(AgendaItem.TaskItem(it)) }
            }.sortedBy {
                when (it) {
                    is AgendaItem.CourseItem -> state.periods.getOrNull(it.occurrence.startPeriod - 1)?.from ?: "00:00"
                    is AgendaItem.TaskItem -> it.task.from ?: "00:00"
                }
            }
            CollapsibleModule(
                title = "今日日程",
                icon = Icons.Filled.DateRange,
                count = rows.size,
                toneHex = "#2563EB",
                open = moduleOpen["agenda"] ?: true,
                onToggle = { toggleModule("agenda") },
                meta = "${rows.size} 个安排"
            ) {
                if (rows.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.surface2),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Filled.DateRange,
                                contentDescription = null,
                                tint = colors.accent,
                                modifier = Modifier.size(30.dp)
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Text("今天还没有安排", color = colors.ink, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "添加任务后，这里会按时间排出一天的节奏",
                            color = colors.muted,
                            fontSize = captionSize(),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        PrimaryButton("＋ 添加任务", modifier = Modifier.width(190.dp)) { showAddTask = true }
                    }
                } else {
                    AgendaTimeline(
                        rows = rows,
                        state = state,
                        onCourseClick = { detailCourse = it },
                        onTaskToggle = { vm.toggleTask(it.id, !it.done) },
                        onTaskEdit = { editTask = it }
                    )
                }
            }
        }
        item {
            val near = deadlines.filter { it.third <= 14 }
            val featured = near.take(1)
            val remaining = (near.size - featured.size).coerceAtLeast(0)
            val meta = when {
                near.isEmpty() -> "暂无截止"
                near.any { it.third in 0L..3L } -> "未来三天"
                else -> "未来两周"
            }
            CollapsibleModule(
                title = "即将截止",
                icon = Icons.Filled.Notifications,
                count = near.size,
                toneHex = "#A66A34",
                open = moduleOpen["deadline"] ?: true,
                onToggle = { toggleModule("deadline") },
                meta = meta
            ) {
                if (featured.isEmpty()) EmptyHint("未来两周没有截止任务。")
                featured.forEach { (task, date, days) -> DueFeatureRow(task, date, days.toInt()) }
                if (remaining > 0) {
                    Text(
                        "还有 $remaining 项截止",
                        color = colors.muted,
                        fontSize = captionSize(),
                        modifier = Modifier.padding(top = 10.dp)
                    )
                }
            }
        }
        item {
            val meta = when {
                state.goals.isEmpty() -> "新建一个"
                state.goals.size == 1 -> "一项在进行"
                else -> "${state.goals.size} 项在进行"
            }
            CollapsibleModule(
                title = "长期学习",
                icon = Icons.Filled.Star,
                count = state.goals.size,
                toneHex = "#237E63",
                open = moduleOpen["goal"] ?: false,
                onToggle = { toggleModule("goal") },
                meta = meta
            ) {
                state.goals.forEach { goal -> GoalRow(goal) }
                AccentTextAction("＋ 新建长期学习目标") { showAddGoal = true }
            }
        }
        item {
            CollapsibleModule(
                title = "待安排",
                icon = Icons.Filled.List,
                count = inbox.size,
                toneHex = "#A66A34",
                open = moduleOpen["inbox"] ?: false,
                onToggle = { toggleModule("inbox") },
                meta = "${inbox.size} 项"
            ) {
                if (inbox.isEmpty()) EmptyHint("所有任务都已安排时间。")
                inbox.forEach { task ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                task.title,
                                color = colors.ink,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                lineHeight = 22.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(5.dp))
                            Text(inboxMeta(task), color = colors.body, fontSize = captionSize(), lineHeight = 20.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "去规划",
                            color = colors.accent,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable(onClick = onGoPlanning)
                                .padding(horizontal = 4.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
        item {
            CollapsibleModule(
                title = "已完成",
                icon = Icons.Filled.CheckCircle,
                count = doneTasks.size,
                toneHex = "#237E63",
                open = moduleOpen["done"] ?: false,
                onToggle = { toggleModule("done") },
                meta = "${doneTasks.size} 项"
            ) {
                if (doneTasks.isEmpty()) EmptyHint("今天还没有完成的任务。")
                doneTasks.forEach { task ->
                    DoneTaskRow(task, state) { vm.toggleTask(task.id, false) }
                }
            }
        }
        item {
            val reviewWeek = state.currentWeek(today)
            val weekTasks = state.tasks.filter { it.week == reviewWeek }
            val finished = weekTasks.count { it.done }
            val total = weekTasks.size
            val plannedMinutes = weekTasks.sumOf { it.estimatedMinutes ?: 0 }
            val courseCount = (0..6).sumOf { day -> state.occurrencesOn(reviewWeek, day).count { !it.cancelled } }
            val rate = if (total == 0) 0f else finished.toFloat() / total
            CollapsibleModule(
                title = "本周复盘",
                icon = Icons.Filled.DateRange,
                count = total,
                toneHex = "#18796E",
                open = moduleOpen["review"] ?: false,
                onToggle = { toggleModule("review") },
                meta = "第 $reviewWeek 周"
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    PulseNumber("$courseCount", "节课", colors.accent)
                    PulseNumber("$finished/$total", "项任务", colors.warn)
                    PulseNumber(formatHours(plannedMinutes / 60.0), "小时计划", colors.ok)
                }
                Spacer(Modifier.height(14.dp))
                ProgressBar(rate.coerceIn(0f, 1f), colors.accent2)
                Spacer(Modifier.height(10.dp))
                Text(
                    "本周任务完成率 ${(rate * 100).toInt()}% · 长期目标 ${state.goals.size} 个",
                    color = colors.muted,
                    fontSize = captionSize()
                )
            }
        }
    }

    detailCourse?.let { occurrence ->
        val course = occurrence.course
        val tone = toneOf(course.colorHex, colors.dark)
        val from = state.periods.getOrNull(occurrence.startPeriod - 1)?.from ?: ""
        val to = state.periods.getOrNull(occurrence.endPeriod - 1)?.to ?: ""
        KexuDialog(title = if (occurrence.moved) "课程详情 · 已调课" else "课程详情", onDismiss = { detailCourse = null }) {
            Column {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(tone.bg)
                        .padding(16.dp)
                ) {
                    Text(course.name, color = tone.ink, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${KexuCore.WEEKDAY_CN.getOrElse(occurrence.weekday) { "?" }} $from — $to · ${occurrence.room}" +
                            if (occurrence.moved) "（本次调课）" else "",
                        color = tone.ink,
                        fontSize = captionSize()
                    )
                }
                Spacer(Modifier.height(12.dp))
                Text("教师：${course.teacher}", color = colors.ink2, fontSize = captionSize())
                Spacer(Modifier.height(4.dp))
                Text("地点：${occurrence.room}", color = colors.ink2, fontSize = captionSize())
                Spacer(Modifier.height(12.dp))
                DialogAction("编辑课程", accent = true) {
                    detailCourse = null
                    editCourse = course
                }
                Spacer(Modifier.height(8.dp))
                DialogAction("复制课程", accent = false) {
                    vm.duplicateCourse(course)
                    detailCourse = null
                }
            }
        }
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

    editCourse?.let { course ->
        CourseEditDialog(
            state = state,
            course = course,
            onDismiss = { editCourse = null },
            onSave = {
                vm.updateCourse(it)
                editCourse = null
            },
            onDelete = {
                vm.deleteCourse(it.id)
                editCourse = null
            }
        )
    }

    if (showAddTask) {
        AddTaskDialog(state = state, onDismiss = { showAddTask = false }) { title, courseId, priority, minutes, deadline, week, weekday, from, to, repeatKind, repeatUntil ->
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
            showAddTask = false
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
}

private val WEEKDAY_FULL = listOf("一", "二", "三", "四", "五", "六", "日")

private sealed interface AgendaItem {
    data class CourseItem(val occurrence: CourseOccurrence) : AgendaItem
    data class TaskItem(val task: Task) : AgendaItem
}

/** V11 date-stand：大标题 + 强调色句点 + 右侧学期周次。 */
@Composable
private fun DateStand(dateLine: String, title: String, semesterLabel: String) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(dateLine, color = colors.muted, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(
                buildAnnotatedString {
                    append(title)
                    withStyle(SpanStyle(color = colors.accent)) { append(".") }
                },
                color = colors.ink,
                fontSize = (44f * fonts.title / fonts.body).sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-2.2).sp,
                lineHeight = (48f * fonts.title / fonts.body).sp,
                maxLines = 1
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            semesterLabel,
            color = colors.accent,
            fontSize = captionSize(),
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
    }
    Spacer(Modifier.height(21.dp))
}

/** V11 hero：下一节课 / 自由安排。 */
@Composable
private fun HeroCard(next: NextCourse?, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    val tone = if (next != null) toneOf(next.course.colorHex, colors.dark) else toneOf("#475569", colors.dark)
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(tone.bg)
            .clickable(enabled = next != null, onClick = onClick)
    ) {
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 120.dp, y = (-135).dp)
                .size(215.dp)
                .border(30.dp, tone.ink.copy(alpha = 0.055f), CircleShape)
        )
        Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 23.dp, bottom = 21.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("●　下一节课", color = tone.ink.copy(alpha = 0.85f), fontSize = captionSize(), fontWeight = FontWeight.Bold)
                Text(
                    if (next != null) "${KexuCore.timeOf(next.startMin)} 开始" else "今天已无课",
                    color = tone.ink.copy(alpha = 0.85f),
                    fontSize = captionSize(),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(17.dp))
            Text(
                next?.course?.name ?: "自由安排",
                color = if (next == null) tone.ink.copy(alpha = 0.6f) else tone.ink,
                fontSize = 36.sp,
                lineHeight = 42.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.6).sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(0.94f)
            )
            if (next != null) {
                Spacer(Modifier.height(15.dp))
                HorizontalDivider(color = tone.ink.copy(alpha = 0.21f))
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                    Text(
                        "${KexuCore.timeOf(next.startMin)} — ${KexuCore.timeOf(next.endMin)}",
                        color = tone.ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                        Text(
                            next.room ?: next.meeting.room ?: next.course.room,
                            color = tone.ink,
                            fontSize = captionSize(),
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "第 ${next.startPeriod}–${next.endPeriod} 节" + if (next.moved) " · 调课" else "",
                            color = tone.ink.copy(alpha = 0.9f),
                            fontSize = captionSize()
                        )
                    }
                }
            } else {
                Spacer(Modifier.height(15.dp))
                Text("今天没有课程安排", color = tone.ink.copy(alpha = 0.72f), fontSize = captionSize())
            }
        }
    }
}

/** V11 daypulse：23sp 数字 + 14sp 标签 + 细分隔线；三个数字用不同颜色区分。 */
@Composable
private fun DayPulse(courses: Int, tasks: Int, hours: Double) {
    val colors = LocalKexuColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        PulseNumber(courses.toString(), "门课", colors.accent)
        PulseSeparator()
        PulseNumber(tasks.toString(), "项任务", colors.warn)
        PulseSeparator()
        PulseNumber(formatHours(hours), "小时计划", colors.ok)
    }
}

@Composable
private fun PulseNumber(number: String, label: String, numberColor: Color) {
    val colors = LocalKexuColors.current
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            number,
            color = numberColor,
            fontSize = 23.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.8).sp,
            lineHeight = 26.sp
        )
        Spacer(Modifier.width(5.dp))
        Text(label, color = colors.muted, fontSize = captionSize(), modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun PulseSeparator() {
    val colors = LocalKexuColors.current
    Box(
        Modifier
            .padding(horizontal = 13.dp)
            .width(1.dp)
            .height(20.dp)
            .background(colors.line)
    )
}

@Composable
private fun AccentTextAction(text: String, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Text(
        text,
        color = colors.accent,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
    )
}

/** V11 今日日程时间轴：时间列 + 圆点 + 主区（+ 任务完成圆钮）。 */
@Composable
private fun AgendaTimeline(
    rows: List<AgendaItem>,
    state: AppUiState,
    onCourseClick: (CourseOccurrence) -> Unit,
    onTaskToggle: (Task) -> Unit,
    onTaskEdit: (Task) -> Unit
) {
    val colors = LocalKexuColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val x = 68.5.dp.toPx()
                drawLine(
                    colors.line,
                    Offset(x, 4.dp.toPx()),
                    Offset(x, size.height - 4.dp.toPx()),
                    1.dp.toPx()
                )
            }
    ) {
        rows.forEach { row ->
            when (row) {
                is AgendaItem.CourseItem -> AgendaCourseRow(row.occurrence, state, onCourseClick)
                is AgendaItem.TaskItem -> AgendaTaskRow(row.task, state, onTaskToggle, onTaskEdit)
            }
        }
    }
}

@Composable
private fun AgendaCourseRow(occurrence: CourseOccurrence, state: AppUiState, onClick: (CourseOccurrence) -> Unit) {
    val colors = LocalKexuColors.current
    val course = occurrence.course
    val tone = toneOf(course.colorHex, colors.dark)
    val from = state.periods.getOrNull(occurrence.startPeriod - 1)?.from ?: "--:--"
    val to = state.periods.getOrNull(occurrence.endPeriod - 1)?.to ?: "--:--"
    val subtitle = buildList {
        if (occurrence.room.isNotBlank()) add(occurrence.room)
        add("第 ${occurrence.startPeriod}–${occurrence.endPeriod} 节")
    }.joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 87.dp)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        TimeColumn(from, to)
        Spacer(Modifier.width(9.dp))
        TimelineDot(tone.base)
        Spacer(Modifier.width(9.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onClick(occurrence) }
        ) {
            Text(
                if (occurrence.moved) "${course.name} · 调课" else course.name,
                color = tone.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                subtitle,
                color = colors.body,
                fontSize = captionSize(),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AgendaTaskRow(
    task: Task,
    state: AppUiState,
    onToggle: (Task) -> Unit,
    onEdit: (Task) -> Unit
) {
    val colors = LocalKexuColors.current
    val tone = toneOf(state.courseColor(task.courseId), colors.dark)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 87.dp)
            .padding(vertical = 9.dp),
        verticalAlignment = Alignment.Top
    ) {
        TimeColumn(task.from ?: "--:--", task.to ?: "")
        Spacer(Modifier.width(9.dp))
        TimelineDot(tone.base)
        Spacer(Modifier.width(9.dp))
        Column(
            Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onEdit(task) }
        ) {
            Text(
                task.title,
                color = if (task.done) colors.muted else colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 22.sp,
                textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            Text(
                agendaTaskSubtitle(task, state),
                color = colors.body,
                fontSize = captionSize(),
                lineHeight = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(if (task.done) colors.ok else Color.Transparent)
                .border(
                    1.5.dp,
                    if (task.done) colors.ok else if (colors.dark) colors.muted else Color(0xFF98A6B6),
                    CircleShape
                )
                .clickable { onToggle(task) },
            contentAlignment = Alignment.Center
        ) {
            if (task.done) {
                Icon(Icons.Filled.Check, contentDescription = "标记未完成", tint = Color.White, modifier = Modifier.size(14.dp))
            }
        }
    }
}

@Composable
private fun TimeColumn(from: String, to: String) {
    val colors = LocalKexuColors.current
    Column(Modifier.width(50.dp)) {
        Text(from, color = colors.accent, fontSize = captionSize(), fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(9.dp))
        Text(to, color = colors.muted, fontSize = captionSize())
    }
}

@Composable
private fun TimelineDot(color: Color) {
    val colors = LocalKexuColors.current
    Box(Modifier.width(19.dp).padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.size(19.dp).background(colors.surface, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(11.dp).background(color, CircleShape))
        }
    }
}

@Composable
private fun DueFeatureRow(task: Task, date: LocalDate, days: Int) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.width(70.dp)) {
            Text(
                "${date.dayOfMonth}",
                color = colors.warn,
                fontSize = 38.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.4).sp,
                lineHeight = 40.sp
            )
            Spacer(Modifier.height(4.dp))
            Text("${date.monthValue} 月", color = colors.warn, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 22.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(7.dp))
            Text(
                "${KexuCore.WEEKDAY_CN[KexuCore.weekdayIndex(date)]}截止 · ${deadlineRemainText(days)}",
                color = colors.muted,
                fontSize = captionSize(),
                lineHeight = 20.sp
            )
        }
    }
}

@Composable
private fun GoalRow(goal: Goal) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${goal.progress}%",
            color = colors.ok,
            fontSize = 43.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-2).sp,
            lineHeight = 45.sp
        )
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(
                goal.title,
                color = colors.ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(5.dp))
            Text(
                "第 ${goal.currentWeek} 周 · 本周 ${goal.doneHours}/${goal.weeklyHours} 小时",
                color = colors.body,
                fontSize = captionSize(),
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(9.dp))
            ProgressBar(goal.progress / 100f, colors.ok)
        }
    }
}

@Composable
private fun DoneTaskRow(task: Task, state: AppUiState, onToggle: () -> Unit) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(colors.ok)
                .clickable(onClick = onToggle),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Check, contentDescription = "标记未完成", tint = Color.White, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                task.title,
                color = colors.muted,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textDecoration = TextDecoration.LineThrough,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Text(doneTaskMeta(task, state), color = colors.muted, fontSize = captionSize())
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

@Composable
private fun DialogAction(text: String, accent: Boolean, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surface2)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = if (accent) colors.accent else colors.ink2, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

private fun agendaTaskSubtitle(task: Task, state: AppUiState): String = buildList {
    add(state.courseById(task.courseId)?.name ?: "个人任务")
    task.estimatedMinutes?.let { add(KexuCore.formatDuration(it)) }
    if (task.isRepeating) add(RecurringTaskPlanner.label(task.repeatKind))
}.joinToString(" · ")

private fun doneTaskMeta(task: Task, state: AppUiState): String = buildList {
    add(state.courseById(task.courseId)?.name ?: "个人任务")
    add("${task.from ?: "--:--"}–${task.to ?: "--:--"}")
}.joinToString(" · ")

private fun inboxMeta(task: Task): String = buildList {
    add(KexuCore.formatDuration(task.estimatedMinutes))
    add("尚未规划")
    if (task.isRepeating) add(RecurringTaskPlanner.label(task.repeatKind))
}.joinToString(" · ")

private fun deadlineRemainText(days: Int): String = when {
    days < 0 -> "已逾期 ${-days} 天"
    days == 0 -> "就是今天"
    days == 1 -> "还剩 1 天"
    else -> "还剩 $days 天"
}

private fun semesterCaption(semesterName: String, date: LocalDate, week: Int): String {
    val season = when {
        semesterName.contains("第一") || semesterName.contains("秋") -> "秋"
        semesterName.contains("第二") || semesterName.contains("春") -> "春"
        else -> if (date.monthValue in 2..7) "春" else "秋"
    }
    val year = Regex("\\d{4}").find(semesterName)?.value ?: date.year.toString()
    return "$year $season · 第${week}周"
}

private fun formatHours(hours: Double): String {
    if (hours <= 0.0) return "0"
    return String.format(Locale.US, "%.2f", hours).trimEnd('0').trimEnd('.')
}
