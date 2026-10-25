package com.kexu.app.ui.settings

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
import com.kexu.app.domain.Period
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.KexuViewModel
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.theme.LocalKexuColors

/** 节次时间轴编辑：逐节修改上下课时间、增删节次、保存后课表自动重排。 */
@Composable
fun PeriodEditor(state: AppUiState, vm: KexuViewModel, showMessage: (String) -> Unit) {
    val colors = LocalKexuColors.current
    var draft by remember(state.periods) { mutableStateOf(state.periods) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text("逐节修改上下课时间，保存后课表格子、课程块与任务块位置会同步更新。", color = colors.muted, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(10.dp))
        draft.forEachIndexed { index, period ->
            Row(
                Modifier.fillMaxWidth().padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("第 ${index + 1} 节", color = colors.muted, fontSize = 14.sp, modifier = Modifier.width(56.dp))
                KTextField(
                    period.from,
                    { value ->
                        draft = draft.toMutableList().also { it[index] = period.copy(from = value) }
                        error = null
                    },
                    modifier = Modifier.width(84.dp)
                )
                Text("—", color = colors.muted, fontSize = 14.sp)
                KTextField(
                    period.to,
                    { value ->
                        draft = draft.toMutableList().also { it[index] = period.copy(to = value) }
                        error = null
                    },
                    modifier = Modifier.width(84.dp)
                )
                Spacer(Modifier.weight(1f))
                if (draft.size > KexuCore.MIN_PERIODS) {
                    Text(
                        "删除",
                        color = colors.muted,
                        fontSize = 14.sp,
                        modifier = Modifier.clickable {
                            draft = draft.toMutableList().also { it.removeAt(index) }
                            error = null
                        }.padding(6.dp)
                    )
                }
            }
        }
        if (error != null) {
            Text(error!!, color = colors.danger, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface2)
                    .clickable {
                        if (draft.size < KexuCore.MAX_PERIODS) {
                            val last = draft.last()
                            val start = KexuCore.minutesOf(last.to) + 10
                            if (start > 0 && start + 40 < 24 * 60) {
                                draft = draft + Period(KexuCore.timeOf(start), KexuCore.timeOf(start + 40))
                                error = null
                            }
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("＋ 增加节次", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(colors.surface2)
                    .clickable {
                        draft = KexuCore.defaultPeriods()
                        error = null
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) { Text("恢复默认", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton("保存时间轴", modifier = Modifier.fillMaxWidth()) {
            val errors = KexuCore.validatePeriods(draft)
            if (errors.isNotEmpty()) {
                error = errors.first()
            } else {
                error = null
                vm.savePeriods(draft)
                showMessage("时间轴已保存，课表已重新布局")
            }
        }
    }
}
