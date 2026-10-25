package com.kexu.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.KexuCore
import com.kexu.app.ui.theme.CourseTone
import com.kexu.app.ui.theme.LocalFontScales
import com.kexu.app.ui.theme.LocalKexuColors
import com.kexu.app.ui.theme.hexColor
import com.kexu.app.ui.theme.toneOf

/** V11 页面标题：小眉标 + 超大标题（无标题色块/装饰线），层级靠字号与留白建立。 */
@Composable
fun PageHeader(
    eyebrow: String? = null,
    title: String,
    trailing: (@Composable () -> Unit)? = null
) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            Column(Modifier.weight(1f)) {
                if (eyebrow != null) {
                    Text(
                        eyebrow,
                        color = colors.muted,
                        fontSize = (14f * fonts.caption / fonts.body).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(
                    title,
                    color = colors.ink,
                    fontSize = (35f * fonts.title / fonts.body).sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.5).sp,
                    lineHeight = (41f * fonts.title / fonts.body).sp
                )
            }
            Spacer(Modifier.width(10.dp))
            trailing?.invoke()
        }
        Spacer(Modifier.height(14.dp))
    }
}

/** V11 分区小标题（对应 control-subtitle）：纯文字、无底色块。 */
@Composable
fun SectionTitle(text: String, trailing: String? = null) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 26.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        Text(
            text,
            color = colors.ink,
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp,
            modifier = Modifier.weight(1f)
        )
        if (trailing != null) Text(trailing, color = colors.muted, fontSize = (14f * fonts.caption / fonts.body).sp, fontWeight = FontWeight.Medium)
    }
}

/** 设置/表单行：更大的主标签 + 14sp 说明，减少零碎小字。 */
@Composable
fun SettingRow(
    label: String,
    hint: String? = null,
    control: @Composable () -> Unit
) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (hint != null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        hint,
                        color = colors.muted,
                        fontSize = (14f * fonts.caption / fonts.body).sp,
                        lineHeight = 20.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            control()
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
fun KSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = LocalKexuColors.current
    val track by animateColorAsState(
        targetValue = if (checked) colors.accent else colors.line,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "switchTrack"
    )
    val thumbX by animateDpAsState(
        targetValue = if (checked) 22.dp else 2.dp,
        animationSpec = tween(260, easing = FastOutSlowInEasing),
        label = "switchThumb"
    )
    Box(
        Modifier
            .size(46.dp, 26.dp)
            .clip(RoundedCornerShape(13.dp))
            .background(track)
            .clickable { onChange(!checked) }
    ) {
        Box(
            Modifier
                .offset { IntOffset(thumbX.roundToPx(), 3.dp.roundToPx()) }
                .size(20.dp)
                .background(Color.White, CircleShape)
        )
    }
}

@Composable
fun KStepper(
    value: Int,
    min: Int,
    max: Int,
    step: Int,
    suffix: String = "",
    onChange: (Int) -> Unit
) {
    val colors = LocalKexuColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton("−", enabled = value - step >= min) { onChange((value - step).coerceAtLeast(min)) }
        Text(
            "$value$suffix",
            color = colors.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.width(64.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        StepperButton("＋", enabled = value + step <= max) { onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        color = colors.surface2,
        modifier = Modifier.size(34.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = if (enabled) colors.ink else colors.muted, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun KSegmented(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .background(colors.surface2, RoundedCornerShape(12.dp))
            .padding(5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        options.forEach { (value, label) ->
            val active = value == selected
            val bg by animateColorAsState(
                targetValue = if (active) colors.surface else Color.Transparent,
                animationSpec = tween(280, easing = FastOutSlowInEasing),
                label = "segBg"
            )
            val fg by animateColorAsState(
                targetValue = if (active) colors.accent else colors.body,
                animationSpec = tween(160),
                label = "segFg"
            )
            Surface(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(9.dp),
                color = bg
            ) {
                Text(
                    label,
                    color = fg,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 9.dp)
                )
            }
        }
    }
}

@Suppress("ModifierParameter")
@Composable
fun KTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String? = null,
    keyboardType: KeyboardType = KeyboardType.Text,
    modifier: Modifier = Modifier,
    placeholder: String? = null
) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = label?.let { { Text(it, fontSize = (13f * fonts.caption / fonts.body).sp) } },
        placeholder = placeholder?.let { { Text(it, fontSize = (14f * fonts.caption / fonts.body).sp, color = colors.muted) } },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = colors.ink),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    )
}

@Composable
fun CourseToneBlock(
    tone: CourseTone,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = tone.bg,
        border = BorderStroke(1.dp, tone.line)
    ) {
        content()
    }
}

/**
 * V11 折叠分区：顶部细分隔线 + 大标题 + 14sp 元信息 + 箭头。
 * [icon]/[toneHex] 保留参数兼容旧调用，但不再绘制图标/色块（视觉层级交给字号）。
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CollapsibleModule(
    title: String,
    icon: ImageVector,
    count: Int,
    toneHex: String,
    open: Boolean,
    onToggle: () -> Unit,
    meta: String? = null,
    content: @Composable () -> Unit
) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    HorizontalDivider(color = colors.line)
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                color = colors.ink,
                fontSize = 19.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.4).sp
            )
            Spacer(Modifier.weight(1f))
            Text(
                meta ?: "$count",
                color = colors.muted,
                fontSize = (14f * fonts.caption / fonts.body).sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(end = 10.dp)
            )
            val rotation by animateFloatAsState(
                targetValue = if (open) 180f else 0f,
                animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
                label = "chevron"
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = if (open) "收起" else "展开",
                tint = colors.muted,
                modifier = Modifier
                    .size(22.dp)
                    .rotate(rotation)
            )
        }
        AnimatedVisibility(
            visible = open,
            enter = expandVertically(animationSpec = tween(280, easing = FastOutSlowInEasing)) +
                fadeIn(animationSpec = tween(180)),
            exit = shrinkVertically(animationSpec = tween(240, easing = FastOutSlowInEasing)) +
                fadeOut(animationSpec = tween(120))
        ) {
            Column(Modifier.padding(bottom = 18.dp)) { content() }
        }
    }
}

@Composable
fun KexuDialog(
    title: String,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.4).sp) },
        text = { content() },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("关闭", fontSize = 15.sp) }
        },
        containerColor = LocalKexuColors.current.surface,
        shape = RoundedCornerShape(22.dp)
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "确定",
    danger: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKexuColors.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold) },
        text = { Text(message, fontSize = 15.sp, color = colors.ink2, lineHeight = 22.sp) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = if (danger) colors.danger else colors.accent, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消", color = colors.muted, fontSize = 15.sp) }
        },
        containerColor = colors.surface,
        shape = RoundedCornerShape(22.dp)
    )
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Button(
        onClick = onClick,
        modifier = modifier.height(48.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.accent, contentColor = Color.White),
        shape = RoundedCornerShape(13.dp)
    ) {
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun EmptyHint(text: String) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    Text(text, color = colors.muted, fontSize = (14f * fonts.caption / fonts.body).sp, lineHeight = (21f * fonts.caption / fonts.body).sp, modifier = Modifier.padding(vertical = 10.dp))
}

@Composable
fun PriorityDot(priority: String) {
    val colors = LocalKexuColors.current
    val color = when (priority) {
        "high" -> colors.danger
        "low" -> colors.muted
        else -> colors.warn
    }
    Box(
        Modifier
            .padding(top = 6.dp)
            .size(6.dp)
            .background(color, RoundedCornerShape(3.dp))
    )
}

fun priorityLabel(priority: String): String = when (priority) {
    "high" -> "高优先级"
    "low" -> "低优先级"
    else -> "中优先级"
}

fun courseToneOrSlate(colorHex: String, dark: Boolean): CourseTone =
    if (colorHex.isBlank()) toneOf("#475569", dark) else toneOf(colorHex.ifBlank { "#475569" }, dark)

fun parseHexOrFallback(hex: String): Color = runCatching { hexColor(hex) }.getOrDefault(Color(0xFF2563EB))

fun periodLayoutTotal(periods: List<com.kexu.app.domain.Period>): Float = KexuCore.periodLayout(periods).totalPx

/** 长按条目后弹出的更多操作，任务与长期学习目标共用同一套样式。 */
data class RowAction(
    val label: String,
    val danger: Boolean = false,
    val onClick: () -> Unit
)

@Composable
fun MoreActionsDialog(
    title: String,
    actions: List<RowAction>,
    onDismiss: () -> Unit
) {
    val colors = LocalKexuColors.current
    KexuDialog(title = title, onDismiss = onDismiss) {
        Column {
            actions.forEach { action ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable {
                            onDismiss()
                            action.onClick()
                        }
                        .padding(horizontal = 6.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        action.label,
                        color = if (action.danger) colors.danger else colors.ink,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- V11 共享视觉组件

/** V11 子页头：方块返回键 + 27sp 标题（设置二级页、节次页等使用）。 */
@Composable
fun SettingsSubHeader(title: String, onBack: () -> Unit) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(39.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.surface2)
                .clickable(onClick = onBack),
            contentAlignment = Alignment.Center
        ) {
            Text("‹", color = colors.ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(13.dp))
        Text(
            title,
            color = colors.ink,
            fontSize = (27f * fonts.title / fonts.body).sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.7).sp
        )
    }
}

/** V11 彩色说明卡（detail-hero）：一句主标题 + 一句 14sp 说明。 */
@Composable
fun SettingsHero(
    title: String,
    body: String,
    container: Color = LocalKexuColors.current.accentSoft,
    titleColor: Color = LocalKexuColors.current.ink,
    bodyColor: Color = LocalKexuColors.current.body
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(19.dp))
            .background(container)
            .padding(20.dp)
    ) {
        Text(title, color = titleColor, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
        Spacer(Modifier.height(8.dp))
        val fonts = LocalFontScales.current
        Text(body, color = bodyColor, fontSize = (14f * fonts.caption / fonts.body).sp, lineHeight = (22f * fonts.caption / fonts.body).sp)
    }
}

/** V11 设置分类行：彩色字形块 + 主标题 + 副标题 + ›。 */
@Composable
fun SettingCategoryRow(
    glyph: String,
    glyphBg: Color,
    glyphFg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(glyphBg),
            contentAlignment = Alignment.Center
        ) {
            Text(glyph, color = glyphFg, fontSize = 23.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = colors.muted, fontSize = (14f * fonts.caption / fonts.body).sp, lineHeight = (20f * fonts.caption / fonts.body).sp)
        }
        Text("›", color = colors.muted, fontSize = 25.sp)
    }
    HorizontalDivider(color = colors.line)
}

/** V11 下划线式 Tab（待办筛选等）。 */
@Composable
fun UnderlineTabs(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val colors = LocalKexuColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
        options.forEach { (value, label) ->
            val active = value == selected
            Column(
                Modifier
                    .clickable { onSelect(value) }
                    .padding(top = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    label,
                    color = if (active) colors.accent else colors.muted,
                    fontSize = 15.sp,
                    fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium,
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(if (active) colors.accent else Color.Transparent, RoundedCornerShape(2.dp))
                )
            }
        }
    }
    HorizontalDivider(color = colors.line)
}

/** V11 数据操作行（数据管理页）。 */
@Composable
fun DataActionRow(text: String, trailing: String = "↗", danger: Boolean = false, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .border(1.dp, colors.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text,
            color = if (danger) colors.danger else colors.ink,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(trailing, color = if (danger) colors.danger else colors.muted, fontSize = 18.sp)
    }
}

/** 辅助说明字号：统一受「外观与显示 → 字体大小 → 辅助说明」倍率控制。 */
@Composable
fun captionSize(base: Float = 14f): androidx.compose.ui.unit.TextUnit {
    val fonts = LocalFontScales.current
    return (base * fonts.caption / fonts.body).sp
}