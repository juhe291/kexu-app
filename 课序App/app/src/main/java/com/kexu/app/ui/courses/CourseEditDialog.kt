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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.Course
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Meeting
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.components.KSegmented
import com.kexu.app.ui.components.KStepper
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.theme.LocalKexuColors

/** 编辑课程：名称/教师/地点/每次上课的星期与节次，可删除整门课程。 */
@Composable
fun CourseEditDialog(
    state: AppUiState,
    course: Course,
    onDismiss: () -> Unit,
    onSave: (Course) -> Unit,
    onDelete: (Course) -> Unit
) {
    val colors = LocalKexuColors.current
    var name by remember(course.id) { mutableStateOf(course.name) }
    var teacher by remember(course.id) { mutableStateOf(course.teacher) }
    var room by remember(course.id) { mutableStateOf(course.room) }
    var meetings by remember(course.id) { mutableStateOf(course.meetings) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun updateMeeting(index: Int, transform: (Meeting) -> Meeting) {
        meetings = meetings.toMutableList().also { it[index] = transform(it[index]) }
    }

    KexuDialog(title = "编辑课程", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            KTextField(name, { name = it }, label = "课程名称")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KTextField(teacher, { teacher = it }, label = "教师", modifier = Modifier.weight(1f))
                KTextField(room, { room = it }, label = "地点", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            Text("上课时间", color = colors.muted, fontSize = 11.5.sp, fontWeight = FontWeight.Bold)
            meetings.forEachIndexed { index, meeting ->
                Column(Modifier.padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("第 ${index + 1} 次", color = colors.ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        if (meetings.size > 1) {
                            Text(
                                "删除",
                                color = colors.muted,
                                fontSize = 10.5.sp,
                                modifier = Modifier.clickable { meetings = meetings.toMutableList().also { it.removeAt(index) } }
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        KexuCore.WEEKDAY_CN.take(7).forEachIndexed { day, label ->
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(7.dp))
                                    .background(if (meeting.weekday == day) colors.accentSoft else colors.surface2)
                                    .clickable { updateMeeting(index) { it.copy(weekday = day) } }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(label, color = if (meeting.weekday == day) colors.accent else colors.muted, fontSize = 9.5.sp)
                            }
                        }
                    }
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("节次", color = colors.muted, fontSize = 10.5.sp, modifier = Modifier.width(30.dp))
                        KStepper(meeting.startPeriod, 1, state.periods.size, 1) { value ->
                            updateMeeting(index) { it.copy(startPeriod = value, endPeriod = maxOf(it.endPeriod, value)) }
                        }
                        Text("—", color = colors.muted, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp))
                        KStepper(meeting.endPeriod, meeting.startPeriod, state.periods.size, 1) { value ->
                            updateMeeting(index) { it.copy(endPeriod = value) }
                        }
                    }
                    Row(Modifier.padding(top = 4.dp)) {
                        KSegmented(
                            options = listOf("all" to "每周", "odd" to "单周", "even" to "双周"),
                            selected = meeting.parity ?: "all",
                            onSelect = { value -> updateMeeting(index) { it.copy(parity = value.takeIf { p -> p != "all" }) } }
                        )
                    }
                }
                HorizontalDivider(color = colors.line, modifier = Modifier.padding(top = 8.dp))
            }
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(9.dp))
                    .background(colors.surface2)
                    .clickable {
                        meetings = meetings + Meeting(
                            weekday = KexuCore.weekdayIndex(java.time.LocalDate.now()),
                            startPeriod = 1,
                            endPeriod = 2
                        )
                    }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) { Text("＋ 添加上课时间", color = colors.ink, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold) }

            if (error != null) {
                Text(error!!, color = colors.danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("保存课程", modifier = Modifier.fillMaxWidth()) {
                if (name.isBlank()) error = "请填写课程名称"
                else onSave(course.copy(name = name.trim(), teacher = teacher.trim(), room = room.trim(), meetings = meetings))
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { confirmDelete = true }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) { Text("删除课程", color = colors.danger, fontSize = 12.5.sp, fontWeight = FontWeight.Bold) }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删除课程", fontWeight = FontWeight.ExtraBold) },
            text = { Text("确定删除「${course.name}」吗？关联任务不会删除。", color = colors.ink2, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete(course)
                }) { Text("删除", color = colors.danger, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消", color = colors.muted) } },
            containerColor = colors.surface
        )
    }
}
