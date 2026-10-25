package com.kexu.app.ui.timetable

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.Course
import com.kexu.app.domain.CourseOccurrence
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Meeting
import com.kexu.app.domain.Task
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.KexuViewModel
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.courses.AddCourseDialog
import com.kexu.app.ui.courses.CourseEditDialog
import com.kexu.app.ui.courses.CourseMoveDialog
import com.kexu.app.ui.tasks.AddTaskDialog
import com.kexu.app.ui.tasks.TaskEditDialog
import com.kexu.app.ui.theme.LocalFontScales
import com.kexu.app.ui.theme.LocalKexuColors
import com.kexu.app.ui.theme.toneOf
import kotlinx.coroutines.flow.collect
import java.time.LocalDate

private val AXIS_BASE = 44.dp
private val COL_BASE = 86.dp
private val HEAD_H = 56.dp

private data class CellTap(
    val week: Int,
    val weekday: Int,
    val startPeriod: Int,
    val endPeriod: Int,
    val from: String,
    val to: String
)

@Composable
fun TimetableScreen(state: AppUiState, vm: KexuViewModel, onOpenPeriodSettings: () -> Unit) {
    val colors = LocalKexuColors.current
    val currentWeek = state.currentWeek()
    var weekOffset by remember { mutableIntStateOf(0) }
    val week = (currentWeek + weekOffset).coerceIn(1, state.semester.totalWeeks)
    val monday = remember(week, state.semester.startDate) { KexuCore.dateForWeekDay(state.semester.startDate, week, 0) }
    val layout = remember(state.periods) { KexuCore.periodLayout(state.periods) }
    val dayCourses = remember(week, state.courses, state.exceptions) {
        state.occurrencesOnWeek(week)
    }
    val dayTasks = remember(week, state.tasks) {
        (0..6).map { d ->
            state.tasks.filter { it.week == week && it.weekday == d && !it.done && it.from != null && it.to != null }
        }
    }
    var layoutZoom by remember { mutableFloatStateOf(1f) }
    var pinchScale by remember { mutableFloatStateOf(1f) }
    // 双指缩放时文字只跟一半大小，避免放大后字体夸张
    val textZoom = 1f + (layoutZoom - 1f) * 0.5f
    val axisW = AXIS_BASE * layoutZoom
    val colW = COL_BASE * layoutZoom
    var selectedDay by remember { mutableIntStateOf(KexuCore.weekdayIndex(LocalDate.now())) }
    var detailCourse by remember { mutableStateOf<CourseOccurrence?>(null) }
    var moveOccurrence by remember { mutableStateOf<CourseOccurrence?>(null) }
    var editCourse by remember { mutableStateOf<Course?>(null) }
    var editTask by remember { mutableStateOf<Task?>(null) }
    var taskDetail by remember { mutableStateOf<Task?>(null) }
    var cellTap by remember { mutableStateOf<CellTap?>(null) }
    var addTaskCell by remember { mutableStateOf<CellTap?>(null) }
    var addCourseCell by remember { mutableStateOf<CellTap?>(null) }
    val hScroll = rememberScrollState()
    val vScroll = rememberScrollState()

    val fonts = LocalFontScales.current
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = density.fontScale * fonts.timetable / fonts.body)
    ) {
    Column(Modifier.fillMaxSize().background(colors.surface)) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(KexuCore.weekMonthLabel(monday), color = colors.accent, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                Text("第 $week 教学周 · 共 ${state.semester.totalWeeks} 周", color = colors.accent2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(2.dp))
            Text("课程表", color = colors.ink, fontSize = (27f * fonts.title / fonts.body).sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                NavButton("‹") { if (weekOffset > 1 - currentWeek) weekOffset -= 1 }
                Text(
                    KexuCore.weekRangeLabel(monday),
                    color = colors.ink2,
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 10.dp)
                )
                NavButton("›") { if (week + 1 <= state.semester.totalWeeks) weekOffset += 1 }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.surface2)
                        .clickable { weekOffset = 0 }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("本周", color = colors.ink2, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "点空格添加任务/课程 · 双指缩放 " + ((layoutZoom * pinchScale) * 100).toInt() + "% · 点击重置",
                    color = colors.muted,
                    fontSize = 10.5.sp,
                    modifier = Modifier.clickable {
                        layoutZoom = 1f
                        pinchScale = 1f
                    }
                )
                Text(
                    "节次设置",
                    color = colors.accent,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable(onClick = onOpenPeriodSettings)
                )
            }
        }
        HorizontalDivider(color = colors.accent.copy(alpha = 0.45f), thickness = 2.dp)
        Spacer(Modifier.height(8.dp))

        Row(Modifier.weight(1f)) {
            Column(Modifier.width(axisW).fillMaxHeight()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(HEAD_H),
                    contentAlignment = Alignment.Center
                ) {
                    Text("节次", color = colors.muted, fontSize = 9.5.sp)
                }
                Box(Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                  // 时间轴改为「按滚动位置绝对定位」：每个节次/午休标签直接用与右侧网格相同的
                  // vScroll 计算纵坐标，不再依赖超高 Column 的 translationY（那样会被父级裁剪，
                  // 出现午休以下空白、甚至上午标签整段不显示的问题）。
                  // 字号：基准随行高自适应，再乘「课表文字」设置倍率；过大时按行高封顶，避免重叠。
                  val ttRatio = fonts.timetable.coerceIn(0.6f, 2f)
                  val axisFontScale = (density.fontScale * ttRatio / fonts.body).coerceAtLeast(0.5f)
                  fun axisSp(baseDp: Float, maxDp: Float): androidx.compose.ui.unit.TextUnit =
                      (minOf(baseDp * ttRatio, maxDp) / axisFontScale).sp
                  layout.rows.forEachIndexed { index, row ->
                    if (row.gapPx > 0f) {
                      val gapHDp = row.gapPx * layoutZoom
                      val gapSp = axisSp(gapHDp * 0.224f, gapHDp * 0.38f)
                      val gapTop = ((row.topPx - row.gapPx) * layoutZoom).dp
                      Box(
                        Modifier
                          .fillMaxWidth()
                          .height(gapHDp.dp)
                          .offset { IntOffset(0, (gapTop - vScroll.value.toDp()).roundToPx()) }
                          .background(colors.surface2.copy(alpha = 0.55f)),
                        contentAlignment = Alignment.Center
                      ) {
                        if (row.gapLabel.isNotEmpty() && gapHDp >= 18f) {
                          Text(
                            row.gapLabel,
                            color = colors.muted,
                            fontSize = gapSp,
                            maxLines = 1,
                            modifier = Modifier
                              .border(1.dp, colors.gridLine, RoundedCornerShape(6.dp))
                              .background(colors.surface)
                              .padding(horizontal = 3.dp, vertical = 1.dp)
                          )
                        }
                      }
                    }
                    val rowH = row.heightPx * layoutZoom
                    val textRowH = row.heightPx * textZoom
                    val rowTop = (row.topPx * layoutZoom).dp
                    val showTimes = rowH >= 44f
                    val numSp = axisSp(textRowH * 0.116f, textRowH * 0.22f)
                    val timeSp = axisSp(textRowH * 0.084f, textRowH * 0.16f)
                    // 时间轴「开始/结束时间」之间的空隙随缩放自动调整：
                    // 行高越大 spacing/行距越大，行高越小越紧凑；字号也按行高封顶，避免重叠。
                    val labelGapDp = (rowH * 0.035f).coerceIn(1f, 6f)
                    val numLineSp = (textRowH * 0.20f / axisFontScale).sp
                    val timeLineSp = (textRowH * 0.16f / axisFontScale).sp
                    Box(
                      Modifier
                        .fillMaxWidth()
                        .height(rowH.dp)
                        .offset { IntOffset(0, (rowTop - vScroll.value.toDp()).roundToPx()) }
                        .drawBehind {
                          drawLine(
                            colors.line.copy(alpha = 0.65f),
                            Offset(0f, size.height - 1f),
                            Offset(size.width, size.height - 1f),
                            1.dp.toPx()
                          )
                        },
                      contentAlignment = Alignment.Center
                    ) {
                      Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(labelGapDp.dp)
                      ) {
                        Text(
                          "${index + 1}",
                          color = colors.ink2,
                          fontSize = numSp,
                          lineHeight = numLineSp,
                          fontWeight = FontWeight.Bold,
                          maxLines = 1
                        )
                        if (showTimes) {
                          Text(state.periods[index].from, color = colors.muted, fontSize = timeSp, lineHeight = timeLineSp, maxLines = 1)
                          Text(state.periods[index].to, color = colors.muted, fontSize = timeSp, lineHeight = timeLineSp, maxLines = 1)
                        }
                      }
                    }
                  }
                }
            }

            Column(Modifier.weight(1f).fillMaxHeight().pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var zoomed = false
                    do {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val pressed = event.changes.count { it.pressed }
                        if (pressed >= 2) {
                            val z = event.calculateZoom()
                            if (z.isFinite() && z != 1f) {
                                val next = (layoutZoom * pinchScale * z).coerceIn(0.7f, 2f)
                                pinchScale = (next / layoutZoom).coerceIn(0.7f / layoutZoom, 2f / layoutZoom)
                                zoomed = true
                            }
                        }
                        if (zoomed) event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    if (zoomed) {
                        layoutZoom = (layoutZoom * pinchScale).coerceIn(0.7f, 2f)
                        pinchScale = 1f
                    }
                }
            }) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .horizontalScroll(hScroll)
                        .graphicsLayer {
                            scaleX = pinchScale
                            scaleY = pinchScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                ) {
                    Row(Modifier.width(colW * 7).height(HEAD_H)) {
                        for (day in 0..6) {
                            val date = monday.plusDays(day.toLong())
                            val isToday = week == currentWeek && day == KexuCore.weekdayIndex(LocalDate.now())
                            val active = day == selectedDay
                            Column(
                                Modifier
                                    .width(colW)
                                    .fillMaxHeight()
                                    .background(if (active) colors.accentSoft else colors.surface)
                                    .clickable { selectedDay = day }
                                    .border(0.5.dp, colors.gridLine),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(KexuCore.WEEKDAY_CN[day], color = colors.muted, fontSize = 10.5.sp)
                                Text(
                                    "${date.dayOfMonth}",
                                    color = if (isToday || active) colors.accent else colors.ink,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Box(
                        Modifier
                            .weight(1f)
                            .width(colW * 7)
                            .verticalScroll(vScroll)
                    ) {
                        Box(
                            Modifier
                                .width(colW * 7)
                                .height((layout.totalPx * layoutZoom).dp)
                        ) {
                            Box(
                                Modifier
                                    .offset { IntOffset((colW * selectedDay).roundToPx(), 0) }
                                    .width(colW)
                                    .fillMaxHeight()
                                    .background(colors.accent.copy(alpha = 0.045f))
                            )
                            Canvas(Modifier.fillMaxSize()) {
                                val vStroke = 0.8.dp.toPx()
                                val hStroke = 1.dp.toPx()
                                var y = 0f
                                layout.rows.forEach { row ->
                                    if (row.gapPx > 0f) y += row.gapPx.dp.toPx() * layoutZoom
                                    y += row.heightPx.dp.toPx() * layoutZoom
                                    drawLine(colors.line.copy(alpha = 0.65f), Offset(0f, y), Offset(size.width, y), hStroke)
                                }
                                for (day in 0..7) {
                                    val x = colW.toPx() * day
                                    drawLine(colors.gridLine, Offset(x, 0f), Offset(x, size.height), vStroke)
                                }
                            }

                            // 空白格点击区域：点一下即可添加任务或课程
                            // 高度必须跟视觉网格一样按 layoutZoom 缩放，否则点击命中的节次/日期会错位
                            Column(Modifier.fillMaxSize()) {
                                layout.rows.forEach { row ->
                                    if (row.gapPx > 0f) Spacer(Modifier.height((row.gapPx * layoutZoom).dp))
                                    Row(Modifier.fillMaxWidth().height((row.heightPx * layoutZoom).dp)) {
                                        for (day in 0..6) {
                                            Box(
                                                Modifier
                                                    .width(colW)
                                                    .fillMaxHeight()
                                                    .clickable {
                                                        cellTap = CellTap(week, day, row.index + 1, row.index + 1, row.from, row.to)
                                                    }
                                            )
                                        }
                                    }
                                }
                            }

                            // 课程块（已应用单次停课/调课）
                            for (day in 0..6) {
                                dayCourses[day].forEach { occurrence ->
                                    val course = occurrence.course
                                    val startPeriod = occurrence.startPeriod
                                    val endPeriod = occurrence.endPeriod
                                    val cancelled = occurrence.cancelled
                                    val (topPx, heightPx) = KexuCore.meetingRect(state.periods, startPeriod, endPeriod)
                                    val tone = toneOf(course.colorHex, colors.dark)
                                    val hasConflict = dayCourses[day].any { other ->
                                        other !== occurrence && !other.cancelled &&
                                            other.startPeriod <= endPeriod && startPeriod <= other.endPeriod
                                    }
                                    val blockHPx = (((heightPx * layoutZoom) - 8f).coerceAtLeast(24f))
                                    // 课程块文字按块高自适应：先取块高比例做基准，再乘「课表文字」设置倍率，
                                    // 但物理字号不超过块高上限，保证任何缩放下都不溢出。
                                    val ratio = fonts.timetable.coerceIn(0.6f, 2f)
                                    val effScale = (density.fontScale * ratio / fonts.body).coerceAtLeast(0.5f)
                                    val textBlockH = (heightPx * textZoom).coerceAtLeast(24f)
                                    val titleDp = minOf(textBlockH * 0.108f * ratio, blockHPx * 0.20f)
                                    val titleLineDp = titleDp * 1.24f
                                    val timeDp = minOf(textBlockH * 0.08f * ratio, blockHPx * 0.15f)
                                    val showTime = blockHPx >= 48f
                                    val showRoom = blockHPx >= 72f
                                    val timeRows = if (showRoom) 2 else if (showTime) 1 else 0
                                    val padV = (blockHPx * 0.06f).coerceIn(3f, 5f)
                                    val padH = (colW.value * 0.05f).coerceIn(3f, 6f)
                                    val availTitleDp = blockHPx - padV * 2f - timeDp * 1.32f * timeRows
                                    val maxLines = (availTitleDp / titleLineDp).toInt().coerceIn(1, 3)
                                    Column(
                                        Modifier
                                            .offset { IntOffset((colW * day + 3.dp).roundToPx(), ((topPx * layoutZoom).dp + 4.dp).roundToPx()) }
                                            .width(colW - 6.dp)
                                            .height(blockHPx.dp)
                                            .zIndex(3f)
                                            .alpha(if (cancelled) 0.55f else 1f)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(if (cancelled) colors.surface2 else tone.bg)
                                            .border(1.dp, if (cancelled) colors.line else tone.line, RoundedCornerShape(9.dp))
                                            .clickable { detailCourse = occurrence }
                                            .padding(horizontal = padH.dp, vertical = padV.dp)
                                    ) {
                                        Text(
                                            (when {
                                                cancelled -> "已停课 · ${course.name}"
                                                occurrence.moved -> "调课 · ${course.name}"
                                                else -> course.name
                                            }) + if (hasConflict) " ⚠" else "",
                                            color = if (cancelled) colors.muted else tone.ink,
                                            fontSize = (titleDp / effScale).sp,
                                            lineHeight = (titleLineDp / effScale).sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            maxLines = maxLines,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (showTime && !cancelled) {
                                            val from = state.periods.getOrNull(startPeriod - 1)?.from ?: ""
                                            val to = state.periods.getOrNull(endPeriod - 1)?.to ?: ""
                                            Text(
                                                "$from–$to",
                                                color = tone.ink,
                                                fontSize = (timeDp / effScale).sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            if (showRoom) {
                                                Text(
                                                    occurrence.room,
                                                    color = tone.ink,
                                                    fontSize = (timeDp / effScale).sp,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 空档单独画一条横带：午休/晚餐不再只是空白（高度必须跟缩放同步）
                            val gapRatio = fonts.timetable.coerceIn(0.6f, 2f)
                            val gapEff = (density.fontScale * gapRatio / fonts.body).coerceAtLeast(0.5f)
                            layout.rows.forEach { row ->
                                if (row.gapPx > 0f) {
                                    val gapHDp = row.gapPx * layoutZoom
                                    Box(
                                        Modifier
                                            .offset { IntOffset(0, (((row.topPx - row.gapPx) * layoutZoom).dp).roundToPx()) }
                                            .width(colW * 7)
                                            .height(gapHDp.dp)
                                            .background(colors.surface2)
                                            .border(1.dp, colors.gridLine.copy(alpha = 0.9f))
                                    ) {
                                        if (row.gapLabel.isNotEmpty() && gapHDp >= 18f) {
                                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                                val gapTextDp = minOf(gapHDp * 0.224f * gapRatio, gapHDp * 0.36f)
                                                Text(
                                                    "${row.gapLabel} · ${row.gapMin} 分钟",
                                                    color = colors.muted,
                                                    fontSize = (gapTextDp / gapEff).sp,
                                                    fontWeight = FontWeight.Medium,
                                                    maxLines = 1
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 任务块：白底 + 虚线边框，与课程色块明显区分
                            for (day in 0..6) {
                                dayTasks[day].forEach { task ->
                                    val fromMin = KexuCore.minutesOf(task.from!!)
                                    val toMin = KexuCore.minutesOf(task.to!!)
                                    if (fromMin < 0 || toMin <= fromMin) return@forEach
                                    val top = KexuCore.minutesToY(state.periods, fromMin)
                                    val bottom = KexuCore.minutesToY(state.periods, toMin)
                                    val heightPx = (bottom - top).coerceAtLeast(24f)
                                    val tone = toneOf(state.courseColor(task.courseId), colors.dark)
                                    val blockHPx = (((heightPx * layoutZoom) - 6f).coerceAtLeast(24f))
                                    // 任务块文字同样按块高自适应，并遵守「课表文字」倍率
                                    val ratio = fonts.timetable.coerceIn(0.6f, 2f)
                                    val effScale = (density.fontScale * ratio / fonts.body).coerceAtLeast(0.5f)
                                    val taskTextBlockH = (heightPx * textZoom).coerceAtLeast(24f)
                                    val taskLabelDp = minOf(taskTextBlockH * 0.08f * ratio, blockHPx * 0.15f)
                                    val taskTitleDp = minOf(taskTextBlockH * 0.108f * ratio, blockHPx * 0.20f)
                                    val taskTitleLineDp = taskTitleDp * 1.24f
                                    val taskTimeDp = minOf(taskTextBlockH * 0.08f * ratio, blockHPx * 0.15f)
                                    Column(
                                        Modifier
                                            .offset { IntOffset((colW * day + 3.dp).roundToPx(), ((top * layoutZoom).dp + 3.dp).roundToPx()) }
                                            .width(colW - 6.dp)
                                            .height(blockHPx.dp)
                                            .zIndex(2f)
                                            .clip(RoundedCornerShape(11.dp))
                                            .background(colors.surface)
                                            .drawBehind {
                                                drawRoundRect(
                                                    color = tone.base.copy(alpha = 0.55f),
                                                    style = Stroke(
                                                        width = 1.2.dp.toPx(),
                                                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
                                                    ),
                                                    cornerRadius = CornerRadius(9.dp.toPx())
                                                )
                                            }
                                            .clickable { taskDetail = task }
                                            .padding(horizontal = 6.dp, vertical = 4.dp)
                                    ) {
                                        Text("任务", color = tone.base, fontSize = (taskLabelDp / effScale).sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
                                        Text(
                                            task.title,
                                            color = colors.ink,
                                            fontSize = (taskTitleDp / effScale).sp,
                                            lineHeight = (taskTitleLineDp / effScale).sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = if (blockHPx < 52f) 1 else 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        if (blockHPx >= 54f) {
                                            Text(
                                                "${task.from}–${task.to}",
                                                color = colors.muted,
                                                fontSize = (taskTimeDp / effScale).sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    cellTap?.let { cell ->
        val date = KexuCore.dateForWeekDay(state.semester.startDate, cell.week, cell.weekday)
        KexuDialog(title = "添加", onDismiss = { cellTap = null }) {
            Column {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(colors.surface2)
                        .padding(14.dp)
                ) {
                    Column {
                        Text(
                            "${KexuCore.fmtCN(date)} · ${KexuCore.WEEKDAY_CN[cell.weekday]}",
                            color = colors.ink,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text("第 ${cell.startPeriod} 节 · ${cell.from}–${cell.to}", color = colors.muted, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                PrimaryButton("添加任务", modifier = Modifier.fillMaxWidth()) {
                    addTaskCell = cell
                    cellTap = null
                }
                Spacer(Modifier.height(8.dp))
                PrimaryButton("添加课程", modifier = Modifier.fillMaxWidth()) {
                    addCourseCell = cell
                    cellTap = null
                }
            }
        }
    }

    addTaskCell?.let { cell ->
        AddTaskDialog(
            state = state,
            initialWeekday = cell.weekday,
            initialFrom = cell.from,
            initialTo = cell.to,
            initialWeek = cell.week,
            onDismiss = { addTaskCell = null },
            onSave = { title, courseId, priority, minutes, deadline, taskWeek, weekday, from, to, repeatKind, repeatUntil ->
                vm.addTask(
                    title = title,
                    courseId = courseId,
                    priority = priority,
                    estimatedMinutes = minutes,
                    deadline = deadline,
                    from = from,
                    to = to,
                    weekday = weekday,
                    week = taskWeek,
                    repeatKind = repeatKind,
                    repeatUntil = repeatUntil
                )
                addTaskCell = null
            }
        )
    }

    addCourseCell?.let { cell ->
        AddCourseDialog(
            state = state,
            initialWeekday = cell.weekday,
            initialStartPeriod = cell.startPeriod,
            initialEndPeriod = cell.endPeriod,
            onDismiss = { addCourseCell = null },
            onSave = { name, teacher, room, weekday, start, end, parity ->
                vm.addCourse(name, teacher, room, weekday, start, end, parity)
                addCourseCell = null
            }
        )
    }

    detailCourse?.let { occurrence ->
        val course = occurrence.course
        val meeting = occurrence.meeting
        val tone = toneOf(course.colorHex, colors.dark)
        val from = state.periods.getOrNull(occurrence.startPeriod - 1)?.from ?: ""
        val to = state.periods.getOrNull(occurrence.endPeriod - 1)?.to ?: ""
        KexuDialog(
            title = when {
                occurrence.cancelled -> "课程详情 · 已停课"
                occurrence.moved -> "课程详情 · 已调课"
                else -> "课程详情"
            },
            onDismiss = { detailCourse = null }
        ) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(tone.bg).padding(14.dp)) {
                Text(course.name, color = tone.ink, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(5.dp))
                Text(
                    "${KexuCore.WEEKDAY_CN.getOrElse(occurrence.weekday) { "?" }} $from — $to",
                    color = tone.ink,
                    fontSize = 12.5.sp
                )
                Text(
                    occurrence.room + " · " + course.teacher +
                        (meeting.parity?.let { if (it == "odd") " · 单周" else " · 双周" } ?: "") +
                        (meeting.weeks?.let { " · 第 " + it.sorted().joinToString("、") + " 周" } ?: "") +
                        when {
                            occurrence.cancelled -> " · 第 ${occurrence.week} 周已停课"
                            occurrence.moved -> " · 第 ${occurrence.week} 周已调课"
                            else -> ""
                        },
                    color = tone.ink,
                    fontSize = 11.5.sp,
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (occurrence.moved) {
                    Text(
                        "原时间：${KexuCore.WEEKDAY_CN.getOrElse(meeting.weekday) { "?" }} 第 ${meeting.startPeriod}-${meeting.endPeriod} 节",
                        color = tone.ink.copy(alpha = 0.75f),
                        fontSize = 10.5.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Spacer(Modifier.height(10.dp))
                val exceptionId = occurrence.exceptionId
                if (exceptionId != null) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(9.dp))
                            .background(colors.surface.copy(alpha = 0.75f))
                            .clickable {
                                vm.deleteException(exceptionId)
                                detailCourse = null
                            }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            if (occurrence.cancelled) "恢复本次课（撤销停课）" else "恢复本次课（撤销调课）",
                            color = tone.ink,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(colors.surface.copy(alpha = 0.75f))
                                .clickable {
                                    vm.cancelOccurrence(occurrence)
                                    detailCourse = null
                                }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("停课一次", color = tone.ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(9.dp))
                                .background(colors.surface.copy(alpha = 0.75f))
                                .clickable {
                                    moveOccurrence = occurrence
                                    detailCourse = null
                                }
                                .padding(vertical = 9.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("调课…", color = tone.ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.surface.copy(alpha = 0.75f))
                        .clickable {
                            detailCourse = null
                            editCourse = course
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("编辑课程", color = tone.ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(9.dp))
                        .background(colors.surface.copy(alpha = 0.75f))
                        .clickable {
                            vm.duplicateCourse(course)
                            detailCourse = null
                        }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("复制课程", color = tone.ink, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }

    moveOccurrence?.let { occurrence ->
        CourseMoveDialog(
            state = state,
            occurrence = occurrence,
            onDismiss = { moveOccurrence = null },
            onConfirm = { newWeekday, newStart, newEnd, newRoom, note ->
                vm.moveOccurrence(occurrence, newWeekday, newStart, newEnd, newRoom, note)
                moveOccurrence = null
            }
        )
    }

    taskDetail?.let { task ->
        KexuDialog(title = "任务详情", onDismiss = { taskDetail = null }) {
            Column {
                Text(task.title, color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "${KexuCore.WEEKDAY_CN[task.weekday ?: 0]} ${task.from ?: ""}–${task.to ?: ""} · " +
                        (state.courseById(task.courseId)?.name ?: "个人任务"),
                    color = colors.muted,
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(14.dp))
                PrimaryButton(
                    if (task.done) "恢复为未完成" else "标记完成",
                    modifier = Modifier.fillMaxWidth()
                ) {
                    vm.toggleTask(task.id, !task.done)
                    taskDetail = null
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface2)
                        .clickable {
                            taskDetail = null
                            editTask = task
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("编辑任务", color = colors.ink, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
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
    }
}

@Composable
private fun NavButton(text: String, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(colors.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.ink2, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
