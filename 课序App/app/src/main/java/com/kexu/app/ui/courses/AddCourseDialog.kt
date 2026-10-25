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
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.components.KSegmented
import com.kexu.app.ui.components.KStepper
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.components.SettingRow
import com.kexu.app.ui.theme.LocalKexuColors

/** 在课表里点击空格后添加课程；默认带入点击的星期与节次。 */
@Composable
fun AddCourseDialog(
    state: AppUiState,
    initialWeekday: Int,
    initialStartPeriod: Int,
    initialEndPeriod: Int,
    onDismiss: () -> Unit,
    onSave: (name: String, teacher: String, room: String, weekday: Int, start: Int, end: Int, parity: String?) -> Unit
) {
    val colors = LocalKexuColors.current
    var name by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    var weekday by remember { mutableStateOf(initialWeekday) }
    var startPeriod by remember { mutableStateOf(initialStartPeriod.coerceIn(1, state.periods.size)) }
    var endPeriod by remember { mutableStateOf(initialEndPeriod.coerceIn(startPeriod, state.periods.size)) }
    var parity by remember { mutableStateOf("all") }
    var error by remember { mutableStateOf<String?>(null) }

    KexuDialog(title = "添加课程", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            KTextField(name, { name = it }, label = "课程名称", placeholder = "例如：数据结构")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KTextField(teacher, { teacher = it }, label = "教师（可选）", modifier = Modifier.weight(1f))
                KTextField(room, { room = it }, label = "地点（可选）", modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Text("星期", color = colors.muted, fontSize = 11.5.sp)
            Spacer(Modifier.height(5.dp))
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
            SettingRow("开始节次", null) {
                KStepper(startPeriod, 1, state.periods.size, 1) {
                    startPeriod = it
                    if (endPeriod < it) endPeriod = it
                }
            }
            SettingRow("结束节次", null) {
                KStepper(endPeriod, startPeriod, state.periods.size, 1) { endPeriod = it }
            }
            Spacer(Modifier.height(6.dp))
            Text("周次", color = colors.muted, fontSize = 11.5.sp)
            Spacer(Modifier.height(5.dp))
            KSegmented(
                options = listOf("all" to "每周", "odd" to "单周", "even" to "双周"),
                selected = parity,
                onSelect = { parity = it }
            )
            if (error != null) {
                Text(error!!, color = colors.danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("保存课程", modifier = Modifier.fillMaxWidth()) {
                if (name.isBlank()) {
                    error = "请填写课程名称"
                } else if (endPeriod < startPeriod) {
                    error = "结束节次不能早于开始节次"
                } else {
                    onSave(
                        name.trim(),
                        teacher.trim(),
                        room.trim(),
                        weekday,
                        startPeriod,
                        endPeriod,
                        parity.takeIf { it != "all" }
                    )
                }
            }
        }
    }
}
