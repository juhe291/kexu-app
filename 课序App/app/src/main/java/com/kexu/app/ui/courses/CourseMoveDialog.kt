package com.kexu.app.ui.courses

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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.CourseOccurrence
import com.kexu.app.domain.KexuCore
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.KStepper
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.theme.LocalKexuColors

/**
 * 调课一次：把某教学周的那一次课改到新的星期/节次/教室，不影响其他周。
 * 入口：课表里点某次课 → 课程详情 →「调课…」。
 */
@Composable
fun CourseMoveDialog(
    state: AppUiState,
    occurrence: CourseOccurrence,
    onDismiss: () -> Unit,
    onConfirm: (weekday: Int, startPeriod: Int, endPeriod: Int, room: String?, note: String) -> Unit
) {
    val colors = LocalKexuColors.current
    val maxPeriods = state.periods.size.coerceAtLeast(1)
    var weekday by remember { mutableIntStateOf(occurrence.meeting.weekday.coerceIn(0, 6)) }
    var start by remember { mutableIntStateOf(occurrence.meeting.startPeriod.coerceIn(1, maxPeriods)) }
    var end by remember { mutableIntStateOf(occurrence.meeting.endPeriod.coerceIn(start, maxPeriods)) }
    var room by remember { mutableStateOf(occurrence.meeting.room ?: occurrence.course.room) }
    var note by remember { mutableStateOf("") }
    val valid = end >= start
    val originalFrom = state.periods.getOrNull(occurrence.meeting.startPeriod - 1)?.from ?: ""
    val originalTo = state.periods.getOrNull(occurrence.meeting.endPeriod - 1)?.to ?: ""

    KexuDialog(title = "调课一次", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                "${occurrence.course.name} · 第 ${occurrence.week} 教学周",
                color = colors.ink,
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                "原时间：${KexuCore.WEEKDAY_CN.getOrElse(occurrence.meeting.weekday) { "?" }} 第 ${occurrence.meeting.startPeriod}-${occurrence.meeting.endPeriod} 节（$originalFrom–$originalTo）",
                color = colors.muted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 3.dp, bottom = 10.dp)
            )

            Text("调到星期", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                KexuCore.WEEKDAY_CN.forEachIndexed { index, label ->
                    val selected = weekday == index
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (selected) colors.accent else colors.surface2)
                            .clickable { weekday = index }
                            .padding(vertical = 7.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label.removePrefix("周"),
                            color = if (selected) colors.surface else colors.ink2,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("开始节次", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                KStepper(start, 1, maxPeriods, 1) { value ->
                    start = value
                    if (end < start) end = start
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("结束节次", color = colors.muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                KStepper(end, start, maxPeriods, 1) { end = it }
            }
            Spacer(Modifier.height(8.dp))
            KTextField(
                value = room,
                onValueChange = { room = it },
                label = "教室（可改）",
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            KTextField(
                value = note,
                onValueChange = { note = it },
                label = "备注（可选，如：临时调至实验楼）",
                modifier = Modifier.fillMaxWidth()
            )
            if (!valid) {
                Text(
                    "结束节次需不早于开始节次",
                    color = colors.danger,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("确认调课（仅本次）", modifier = Modifier.fillMaxWidth()) {
                if (valid) onConfirm(weekday, start, end, room.trim().ifBlank { null }, note.trim())
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("取消", color = colors.muted, fontSize = 12.sp)
            }
        }
    }
}
