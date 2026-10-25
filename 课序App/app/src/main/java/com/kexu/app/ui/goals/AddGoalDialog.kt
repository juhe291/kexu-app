package com.kexu.app.ui.goals

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.components.SettingRow
import com.kexu.app.ui.components.KStepper
import com.kexu.app.ui.theme.LocalKexuColors

/** 新建长期学习目标：名称、总周数、每周投入、下一步。 */
@Composable
fun AddGoalDialog(
    onDismiss: () -> Unit,
    onSave: (title: String, totalWeeks: Int, weeklyHours: Int, next: String) -> Unit
) {
    val colors = LocalKexuColors.current
    var title by remember { mutableStateOf("") }
    var totalWeeks by remember { mutableStateOf(12) }
    var weeklyHours by remember { mutableStateOf(4) }
    var next by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    KexuDialog(title = "新建长期学习目标", onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                "例如「12 周学习 PyTorch」「一学期读完 6 本书」。目标会显示在首页与待办页的长期学习模块。",
                color = colors.muted,
                fontSize = 11.5.sp,
                lineHeight = 17.sp
            )
            Spacer(Modifier.height(10.dp))
            KTextField(title, { title = it }, label = "目标名称", placeholder = "例如：12 周学习 PyTorch")
            Spacer(Modifier.height(6.dp))
            SettingRow("总周数", "从本周开始计算") {
                KStepper(totalWeeks, 2, 52, 1) { totalWeeks = it }
            }
            SettingRow("每周投入", "计划每周投入的小时数") {
                KStepper(weeklyHours, 1, 20, 1) { weeklyHours = it }
            }
            KTextField(next, { next = it }, label = "下一步（可选）", placeholder = "例如：完成第 1 章练习")
            if (error != null) {
                Text(error!!, color = colors.danger, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.height(14.dp))
            PrimaryButton("创建目标", modifier = Modifier.fillMaxWidth()) {
                if (title.isBlank()) {
                    error = "请填写目标名称"
                } else {
                    onSave(title.trim(), totalWeeks, weeklyHours, next.trim())
                }
            }
        }
    }
}
