package com.kexu.app.ui.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.BuildConfig
import com.kexu.app.data.imports.UniversalTimetableImport
import com.kexu.app.domain.Course
import com.kexu.app.domain.IcsExporter
import com.kexu.app.domain.KexuCore
import com.kexu.app.domain.Period
import com.kexu.app.domain.TaskCsvExporter
import com.kexu.app.domain.imports.CourseImportResult
import com.kexu.app.domain.imports.ImportTemplates
import com.kexu.app.notify.ExactAlarmScheduler
import com.kexu.app.ui.AppUiState
import com.kexu.app.ui.KexuViewModel
import com.kexu.app.ui.components.ConfirmDialog
import com.kexu.app.ui.components.DataActionRow
import com.kexu.app.ui.components.EnglishCaption
import com.kexu.app.ui.components.KSegmented
import com.kexu.app.ui.components.glassSurface
import com.kexu.app.ui.components.KStepper
import com.kexu.app.ui.components.KSwitch
import com.kexu.app.ui.components.KTextField
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.captionSize
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.components.SectionTitle
import com.kexu.app.ui.components.SettingCategoryRow
import com.kexu.app.ui.components.SettingRow
import com.kexu.app.ui.components.SettingsHero
import com.kexu.app.ui.components.SettingsSubHeader
import com.kexu.app.ui.imports.ImportPreviewDialog
import com.kexu.app.ui.theme.LocalFontScales
import com.kexu.app.ui.theme.LocalDisplayOptions
import com.kexu.app.ui.theme.LocalKexuColors
import com.kexu.app.ui.theme.hexColor
import com.kexu.app.ui.theme.toneOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * V11 设置：hub 首页（特色卡 + 深色规划卡 + 分类行）与全部二级页。
 * 二级页统一 SettingsSubHeader + SettingsHero + SectionTitle + SettingRow 结构；
 * 保留原有全部功能（导入、模板导出、课程颜色、节次编辑、排程偏好、提醒授权、外观字号、备份与自检）。
 */
@Composable
fun SettingsScreen(
    state: AppUiState,
    vm: KexuViewModel,
    showMessage: (String) -> Unit,
    initialPage: String? = null
) {
    var page by rememberSaveable { mutableStateOf(initialPage ?: "root") }
    LaunchedEffect(initialPage) {
        if (!initialPage.isNullOrBlank()) page = initialPage
    }
    BackHandler(enabled = page != "root") { page = "root" }
    Crossfade(targetState = page, animationSpec = tween(220, easing = FastOutSlowInEasing), label = "settingsPage") { current ->
        when (current) {
            "periods" -> SettingsPage("节次时间轴", onBack = { page = "root" }) {
                PeriodEditor(state, vm, showMessage)
            }
            "course" -> CourseSettings(state, vm, onBack = { page = "root" }, showMessage = showMessage)
            "schedule" -> ScheduleSettings(state, vm, onBack = { page = "root" })
            "reminders" -> ReminderSettings(state, vm, onBack = { page = "root" })
            "appearance" -> AppearanceSettings(state, vm, onBack = { page = "root" })
            "data" -> DataSettings(state, vm, onBack = { page = "root" }, showMessage = showMessage)
            "help" -> UsageGuideScreen(onBack = { page = "root" })
            else -> SettingsRoot(state, onOpen = { page = it })
        }
    }
}

// ------------------------------------------------------------------ 设置首页（V11 hub）

@Composable
private fun SettingsRoot(state: AppUiState, onOpen: (String) -> Unit) {
    val colors = LocalKexuColors.current
    val fonts = LocalFontScales.current
    var showAbout by remember { mutableStateOf(false) }
    var showUpdate by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(start = 23.dp, end = 23.dp, top = 22.dp, bottom = 32.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                EnglishCaption("SETTINGS · PREFERENCES")
                Text("个人配置 / 日程偏好", color = colors.muted, fontSize = captionSize(), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "设置",
                    color = colors.ink,
                    fontSize = (35f * fonts.title / fonts.body).sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-1.5).sp,
                    lineHeight = (41f * fonts.title / fonts.body).sp
                )
                Spacer(Modifier.height(8.dp))
                Text("让课序适应你的学习节奏", color = colors.muted, fontSize = captionSize(), lineHeight = 21.sp)
            }
            Text(
                "✳",
                color = colors.accent.copy(alpha = 0.17f),
                fontSize = 64.sp,
                fontWeight = FontWeight.ExtraBold,
                lineHeight = 64.sp
            )
        }
        Spacer(Modifier.height(24.dp))

        CourseSemesterFeatureCard(onClick = { onOpen("course") })
        Spacer(Modifier.height(13.dp))
        SchedulePlanCard(
            focusMinutes = state.prefs.focusMinutes,
            onClick = { onOpen("schedule") }
        )
        Spacer(Modifier.height(16.dp))

        SettingCategoryRow(
            glyph = "◉",
            glyphBg = Color(0xFFF9EBDE),
            glyphFg = Color(0xFFAA6C35),
            title = "提醒与闹钟",
            subtitle = "上课提醒 · 截止通知 · 精确闹钟",
            onClick = { onOpen("reminders") }
        )
        SettingCategoryRow(
            glyph = "◐",
            glyphBg = Color(0xFFE9E8FB),
            glyphFg = Color(0xFF6556B2),
            title = "外观与显示",
            subtitle = "主题颜色 · 深色模式 · 阅读字号",
            onClick = { onOpen("appearance") }
        )
        SettingCategoryRow(
            glyph = "↓",
            glyphBg = Color(0xFFE5F3ED),
            glyphFg = Color(0xFF2B7A61),
            title = "数据与备份",
            subtitle = "导入导出 · 本地数据管理",
            onClick = { onOpen("data") }
        )

        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                "检查更新",
                color = colors.accent,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showUpdate = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Text(
                "使用说明",
                color = colors.muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onOpen("help") }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
            Text(
                "关于课序",
                color = colors.muted,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { showAbout = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (showUpdate) CheckUpdateDialog(onDismiss = { showUpdate = false })
}

/** 设置首页特色卡：accentSoft 底 + 22sp 标题 + 三根彩色竖条 motif。 */
@Composable
private fun CourseSemesterFeatureCard(onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    val display = LocalDisplayOptions.current
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .then(
                if (display.glassEnabled) Modifier.glassSurface(colors.accentSoft, shape, display.glassDistortion)
                else Modifier.clip(shape).background(colors.accentSoft)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 19.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "课程与学期",
                color = colors.ink,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.65).sp
            )
            Spacer(Modifier.height(9.dp))
            Text(
                "教学周、课表导入、\n自定义节次与课程颜色",
                color = colors.body,
                fontSize = captionSize(),
                lineHeight = 21.sp
            )
        }
        Spacer(Modifier.width(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
            MotifBar(height = 34.dp, color = Color(0xFF658CD6))
            MotifBar(height = 53.dp, color = Color(0xFF52AC9D))
            MotifBar(height = 40.dp, color = Color(0xFFE5B36A))
        }
    }
}

@Composable
private fun MotifBar(height: Dp, color: Color) {
    Box(Modifier.width(13.dp).height(height).clip(RoundedCornerShape(5.dp)).background(color))
}

/** 设置首页深色自动规划卡：左侧说明 + 右侧 55dp 圆环显示专注时长。 */
@Composable
private fun SchedulePlanCard(focusMinutes: Int, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    val background = if (colors.dark) colors.surface2 else Color(0xFF203451)
    val foreground = if (colors.dark) colors.ink else Color.White
    val subColor = if (colors.dark) colors.body else Color(0xFFC3D4E8)
    val ringColor = if (colors.dark) colors.accent else Color(0xFFA4C6EF)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(19.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "日程与自动规划",
                color = foreground,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-0.5).sp
            )
            Spacer(Modifier.height(8.dp))
            Text("空闲时间、任务拆分、排程偏好", color = subColor, fontSize = captionSize())
        }
        Spacer(Modifier.width(10.dp))
        Box(
            Modifier
                .size(55.dp)
                .clip(CircleShape)
                .border(3.dp, ringColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("$focusMinutes′", color = foreground, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

// ------------------------------------------------------------------ 二级页脚手架

@Composable
private fun SettingsPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    val colors = LocalKexuColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .animateContentSize(animationSpec = tween(260, easing = FastOutSlowInEasing))
            .padding(start = 23.dp, end = 23.dp, top = 22.dp, bottom = 32.dp)
    ) {
        SettingsSubHeader(title, onBack)
        content()
        Spacer(Modifier.height(24.dp))
    }
}

/** 设置项整行区块：主标签 + 可选说明 + 控件（用于 4 段式 KSegmented 等放得下的场景）。 */
@Composable
private fun SettingBlock(label: String, hint: String? = null, control: @Composable () -> Unit) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth().padding(top = 15.dp)) {
        Text(label, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        if (hint != null) {
            Spacer(Modifier.height(4.dp))
            Text(hint, color = colors.muted, fontSize = captionSize(), lineHeight = 20.sp)
        }
        Spacer(Modifier.height(10.dp))
        control()
        Spacer(Modifier.height(13.dp))
        HorizontalDivider(color = colors.line)
    }
}

/** V11 btnsoft：浅底圆角次级按钮。 */
@Composable
private fun SoftButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    Box(
        modifier
            .height(46.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface2)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/** 危险操作分区标题（danger 色）。 */
@Composable
private fun DangerSectionTitle(text: String) {
    val colors = LocalKexuColors.current
    Text(
        text,
        color = colors.danger,
        fontSize = 19.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.4).sp,
        modifier = Modifier.padding(top = 26.dp, bottom = 8.dp)
    )
}

// ------------------------------------------------------------------ 课程与学期

@Composable
private fun CourseSettings(state: AppUiState, vm: KexuViewModel, onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = LocalKexuColors.current
    var startDate by remember(state.semester.startDate) { mutableStateOf(state.semester.startDate.toString()) }
    var colorTarget by remember { mutableStateOf<String?>(null) }
    var draftPeriods by remember(state.periods) { mutableStateOf(state.periods) }
    var periodError by remember { mutableStateOf<String?>(null) }
    var coursesExpanded by remember { mutableStateOf(false) }
    var periodsExpanded by remember { mutableStateOf(false) }

    var importPreview by remember { mutableStateOf<CourseImportResult?>(null) }
    var importing by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            if (importing) {
                showMessage("正在解析，请稍候…")
            } else {
                importing = true
                scope.launch {
                    try {
                        showMessage("正在解析，请稍候…")
                        val name = resolveFileName(context, uri) ?: uri.lastPathSegment ?: "课表文件"
                        val bytes = withContext(Dispatchers.IO) {
                            runCatching {
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            }.getOrNull()
                        }
                        if (bytes == null || bytes.isEmpty()) {
                            showMessage("读取文件失败")
                        } else {
                            runCatching {
                                UniversalTimetableImport.import(
                                    context,
                                    bytes,
                                    name,
                                    state.semester,
                                    state.periods
                                )
                            }.onSuccess { parsed ->
                                importPreview = parsed
                            }.onFailure { error ->
                                showMessage(error.message ?: "解析失败，请确认文件格式")
                            }
                        }
                    } finally {
                        importing = false
                    }
                }
            }
        }
    }

    val csvTemplateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            val text = ImportTemplates.csvTemplate()
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            showMessage(if (ok) "已导出 CSV 模板" else "导出失败")
        }
    }
    val jsonTemplateLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val text = ImportTemplates.jsonTemplate()
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            showMessage(if (ok) "已导出 JSON 模板" else "导出失败")
        }
    }
    val aiPromptLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
        if (uri != null) scope.launch {
            val text = ImportTemplates.aiPromptTemplate()
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
            showMessage(if (ok) "已导出 AI 提示词" else "导出失败")
        }
    }

    SettingsPage("课程与学期", onBack) {
        SettingsHero(
            title = "你的学期",
            body = "设置一次学期起点，教学周、课程日期与时间轴便保持一致。"
        )

        SectionTitle("学期")
        SettingRow("学期开始日期") {
            KTextField(startDate, { startDate = it }, modifier = Modifier.width(132.dp))
        }
        SettingRow("教学周数量") {
            KStepper(state.semester.totalWeeks, 4, 30, 1) { vm.updateSemester(state.semester.startDate, it) }
        }
        Spacer(Modifier.height(14.dp))
        PrimaryButton("保存学期开始日期", modifier = Modifier.fillMaxWidth()) {
            val parsed = runCatching { LocalDate.parse(startDate.trim()) }.getOrNull()
            if (parsed == null) {
                showMessage("日期格式应为 YYYY-MM-DD")
            } else {
                vm.updateSemester(parsed, state.semester.totalWeeks)
                showMessage("学期已更新")
            }
        }

        SectionTitle("导入课程")
        Text(
            "支持 CSV / ICS / PDF / XLSX / HTML / 图片（含扫描版 OCR），导入前可逐条预览修正。",
            color = colors.muted,
            fontSize = captionSize(),
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(6.dp))
        DataActionRow(
            text = if (importing) "正在解析…" else "从文件导入课表",
            trailing = "选择",
            onClick = {
                if (importing) showMessage("正在解析，请稍候…") else importLauncher.launch(arrayOf("*/*"))
            }
        )

        SectionTitle("外部 AI 模板")
        Text(
            "导出模板交给 AI 整理成 CSV / JSON，再导回课序。",
            color = colors.muted,
            fontSize = captionSize(),
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(6.dp))
        DataActionRow("导出 CSV 模板", "↗") { csvTemplateLauncher.launch("kexu-import-template.csv") }
        DataActionRow("导出 JSON 模板", "↗") { jsonTemplateLauncher.launch("kexu-import-template.json") }
        DataActionRow("导出 AI 提示词", "↗") { aiPromptLauncher.launch("kexu-ai-prompt.md") }

        SectionTitle("课程颜色", "${state.courses.size} 门")
        val shownCourses = if (coursesExpanded) state.courses else state.courses.take(3)
        shownCourses.forEach { course ->
            CourseColorRow(course = course) { colorTarget = course.id }
        }
        if (state.courses.size > 3) {
            Text(
                if (coursesExpanded) "收起课程列表" else "展开全部 ${state.courses.size} 门课程",
                color = colors.accent,
                fontSize = captionSize(),
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clickable { coursesExpanded = !coursesExpanded }
                    .padding(vertical = 10.dp)
            )
        }

        SectionTitle("节次时间轴", "${draftPeriods.size} 节")
        Text(
            "课表按节次显示，可逐节调整上下课时间。",
            color = colors.muted,
            fontSize = captionSize(),
            lineHeight = 21.sp
        )
        PeriodAxisPreview(periods = draftPeriods)
        Spacer(Modifier.height(12.dp))
        SoftButton(
            text = if (periodsExpanded) "收起节次编辑器" else "编辑完整节次时间轴 →",
            modifier = Modifier.fillMaxWidth()
        ) { periodsExpanded = !periodsExpanded }

        if (periodsExpanded) {
            Spacer(Modifier.height(16.dp))
            draftPeriods.forEachIndexed { index, period ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("第 ${index + 1} 节", color = colors.muted, fontSize = captionSize(), modifier = Modifier.width(56.dp))
                    KTextField(
                        period.from,
                        { value -> draftPeriods = draftPeriods.toMutableList().also { it[index] = period.copy(from = value) } },
                        modifier = Modifier.width(84.dp)
                    )
                    Text("—", color = colors.muted, fontSize = captionSize())
                    KTextField(
                        period.to,
                        { value -> draftPeriods = draftPeriods.toMutableList().also { it[index] = period.copy(to = value) } },
                        modifier = Modifier.width(84.dp)
                    )
                    Spacer(Modifier.weight(1f))
                    if (draftPeriods.size > KexuCore.MIN_PERIODS) {
                        Text(
                            "删除",
                            color = colors.muted,
                            fontSize = captionSize(),
                            modifier = Modifier
                                .clickable { draftPeriods = draftPeriods.toMutableList().also { it.removeAt(index) } }
                                .padding(6.dp)
                        )
                    }
                }
            }
            if (periodError != null) {
                Text(periodError!!, color = colors.danger, fontSize = captionSize(), modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton("＋ 增加节次", modifier = Modifier.weight(1f)) {
                    if (draftPeriods.size < KexuCore.MAX_PERIODS) {
                        val last = draftPeriods.last()
                        val start = KexuCore.minutesOf(last.to) + 10
                        if (start > 0 && start + 40 < 24 * 60) {
                            draftPeriods = draftPeriods + Period(KexuCore.timeOf(start), KexuCore.timeOf(start + 40))
                        }
                    }
                }
                SoftButton("恢复默认", modifier = Modifier.weight(1f)) {
                    draftPeriods = KexuCore.defaultPeriods()
                }
            }
            Spacer(Modifier.height(12.dp))
            PrimaryButton("保存时间轴", modifier = Modifier.fillMaxWidth()) {
                val errors = KexuCore.validatePeriods(draftPeriods)
                if (errors.isNotEmpty()) {
                    periodError = errors.first()
                } else {
                    periodError = null
                    vm.savePeriods(draftPeriods)
                    showMessage("时间轴已保存")
                }
            }
        }
    }

    colorTarget?.let { courseId ->
        val course = state.courses.firstOrNull { it.id == courseId }
        if (course != null) {
            KexuDialog(title = "课程颜色 · ${course.name}", onDismiss = { colorTarget = null }) {
                Column {
                    val chunks = KexuCore.PALETTE.chunked(4)
                    chunks.forEach { rowColors ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                            rowColors.forEach { hex ->
                                val selected = hex.equals(course.colorHex, ignoreCase = true)
                                val tone = toneOf(hex, colors.dark)
                                Box(
                                    Modifier
                                        .size(44.dp)
                                        .clip(RoundedCornerShape(11.dp))
                                        .background(tone.base)
                                        .border(
                                            width = if (selected) 3.dp else 0.dp,
                                            color = if (selected) colors.ink else Color.Transparent,
                                            shape = RoundedCornerShape(11.dp)
                                        )
                                        .clickable {
                                            vm.updateCourseColor(course.id, hex)
                                            colorTarget = null
                                        }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    importPreview?.let { preview ->
        ImportPreviewDialog(
            result = preview,
            semester = state.semester,
            maxPeriods = state.periods.size,
            onDismiss = { importPreview = null },
            onConfirm = { courses ->
                if (courses.isEmpty()) {
                    showMessage("没有可写入的课程，请检查勾选记录的时间是否完整")
                } else {
                    vm.importCourses(courses)
                    showMessage("已导入 " + courses.size + " 门课程")
                }
                importPreview = null
            }
        )
    }
}

/** V11 coursecolor 行：49dp 色块 + 16sp 课程名，点击改色。 */
@Composable
private fun CourseColorRow(course: Course, onClick: () -> Unit) {
    val colors = LocalKexuColors.current
    val tone = toneOf(course.colorHex, colors.dark)
    val meta = listOfNotNull(
        course.teacher.takeIf { it.isNotBlank() },
        "${course.meetings.size} 个课次"
    ).joinToString(" · ")
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(49.dp)
                .clip(RoundedCornerShape(13.dp))
                .background(tone.bg)
                .border(1.dp, tone.line, RoundedCornerShape(13.dp))
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(course.name, color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(3.dp))
            Text(meta, color = colors.muted, fontSize = captionSize())
        }
        Text("改色", color = colors.accent, fontSize = captionSize(), fontWeight = FontWeight.Bold)
        Text("›", color = colors.muted, fontSize = 20.sp, modifier = Modifier.padding(start = 4.dp))
    }
    HorizontalDivider(color = colors.line)
}

/** V11 axis-example：预览前 3 节的起止时间。 */
@Composable
private fun PeriodAxisPreview(periods: List<Period>) {
    val colors = LocalKexuColors.current
    if (periods.isEmpty()) return
    val preview = periods.take(3)
    Row(
        Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        preview.forEachIndexed { index, period ->
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(colors.surface2)
                    .padding(vertical = 13.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("第 ${index + 1} 节", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(6.dp))
                Text("${period.from}–${period.to}", color = colors.muted, fontSize = 13.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ 日程与自动规划

@Composable
private fun ScheduleSettings(state: AppUiState, vm: KexuViewModel, onBack: () -> Unit) {
    val colors = LocalKexuColors.current
    SettingsPage("日程与规划", onBack) {
        SettingsHero(
            title = "把时间留给重要的事",
            body = "课程是固定安排，任务按你的日常节奏自动放进空闲时间。",
            container = if (colors.dark) colors.surface2 else Color(0xFFE6F3EF),
            titleColor = if (colors.dark) colors.ok else Color(0xFF246D5D),
            bodyColor = if (colors.dark) colors.body else Color(0xFF416E62)
        )

        SectionTitle("可安排时间")
        TimeRangeBar(
            start = state.prefs.availabilityStart,
            end = state.prefs.availabilityEnd,
            onStart = { value -> vm.updatePrefs { it.copy(availabilityStart = value) } },
            onEnd = { value -> vm.updatePrefs { it.copy(availabilityEnd = value) } }
        )
        SettingRow("默认任务时长") {
            KStepper(state.prefs.defaultTaskMinutes, 15, 120, 15, suffix = " 分") { value ->
                vm.updatePrefs { it.copy(defaultTaskMinutes = value) }
            }
        }
        SettingRow("单次专注上限") {
            KStepper(state.prefs.focusMinutes, 25, 90, 5, suffix = " 分") { value ->
                vm.updatePrefs { it.copy(focusMinutes = value) }
            }
        }
        SettingRow("默认优先级") {
            KSegmented(listOf("low" to "低", "medium" to "中", "high" to "高"), state.prefs.defaultPriority) { value ->
                vm.updatePrefs { it.copy(defaultPriority = value) }
            }
        }

        SectionTitle("拆分与负载")
        SettingRow("允许拆分任务") {
            KSwitch(state.prefs.splitEnabled) { value -> vm.updatePrefs { it.copy(splitEnabled = value) } }
        }
        SettingRow("最小时间块") {
            KStepper(state.prefs.splitMin, 10, 120, 5, suffix = " 分") { value ->
                vm.updatePrefs { it.copy(splitMin = value) }
            }
        }
        SettingRow("最大时间块") {
            KStepper(state.prefs.splitMax, 30, 240, 15, suffix = " 分") { value ->
                vm.updatePrefs { it.copy(splitMax = value) }
            }
        }
        SettingRow("截止前预留") {
            KStepper(state.prefs.bufferDays, 0, 3, 1, suffix = " 天") { value ->
                vm.updatePrefs { it.copy(bufferDays = value) }
            }
        }
        SettingRow("每日学习上限") {
            KStepper(state.prefs.dailyCapHours, 2, 6, 1, suffix = " 小时") { value ->
                vm.updatePrefs { it.copy(dailyCapHours = value) }
            }
        }
        SettingRow("自动预留休息") {
            KSwitch(state.prefs.restEnabled) { value -> vm.updatePrefs { it.copy(restEnabled = value) } }
        }
        if (state.prefs.restEnabled) {
            SettingRow("休息间隔") {
                KStepper(state.prefs.restMinutes, 10, 30, 5, suffix = " 分") { value ->
                    vm.updatePrefs { it.copy(restMinutes = value) }
                }
            }
        }
    }
}

/** V11 timebar：浅底时段条，两端各一个时间输入。 */
@Composable
private fun TimeRangeBar(start: String, end: String, onStart: (String) -> Unit, onEnd: (String) -> Unit) {
    val colors = LocalKexuColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(colors.surface2)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        KTextField(start, onStart, modifier = Modifier.weight(1f))
        Text("至", color = colors.muted, fontSize = captionSize(), modifier = Modifier.padding(horizontal = 12.dp))
        KTextField(end, onEnd, modifier = Modifier.weight(1f))
    }
}

// ------------------------------------------------------------------ 提醒与闹钟

@Composable
private fun ReminderSettings(state: AppUiState, vm: KexuViewModel, onBack: () -> Unit) {
    val colors = LocalKexuColors.current
    val context = LocalContext.current
    val exactGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ExactAlarmScheduler.canScheduleExact(context)
    } else {
        true
    }
    SettingsPage("提醒与闹钟", onBack) {
        AlarmSpot(
            minutes = state.prefs.courseReminderMinutes,
            exactGranted = exactGranted,
            notifyEnabled = state.prefs.notifyEnabled
        )

        SectionTitle("提醒时间")
        SettingRow("上课前提醒") {
            KStepper(state.prefs.courseReminderMinutes, 5, 30, 5, suffix = " 分") { value ->
                vm.updatePrefs { it.copy(courseReminderMinutes = value) }
            }
        }
        SettingRow("任务开始提醒") {
            KStepper(state.prefs.taskReminderMinutes, 0, 30, 5, suffix = " 分") { value ->
                vm.updatePrefs { it.copy(taskReminderMinutes = value) }
            }
        }
        SettingBlock("截止日期提醒") {
            KSegmented(
                listOf("0" to "当天", "1440" to "提前 1 天", "2880" to "提前 2 天"),
                state.prefs.deadlineReminderMinutes.toString()
            ) { value -> vm.updatePrefs { it.copy(deadlineReminderMinutes = value.toInt()) } }
        }

        SectionTitle("提醒方式")
        SettingRow("普通通知") {
            KSwitch(state.prefs.notifyEnabled) { value -> vm.updatePrefs { it.copy(notifyEnabled = value) } }
        }
        SettingRow("响铃式提醒", "重要事项准点响铃") {
            KSwitch(state.prefs.alarmEnabled) { value -> vm.updatePrefs { it.copy(alarmEnabled = value) } }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SettingRow(
                "精确闹钟",
                if (exactGranted) "已授权：提醒可准点触发" else "未授权：改用 WorkManager，可能延迟数分钟"
            ) {
                if (exactGranted) {
                    Text("已开启", color = colors.muted, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                } else {
                    Text(
                        "去授权",
                        color = colors.accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            val intent = ExactAlarmScheduler.permissionSettingsIntent(context)
                                ?: Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    )
                }
            }
        }
        SettingRow("振动") {
            KSwitch(state.prefs.vibrateEnabled) { value -> vm.updatePrefs { it.copy(vibrateEnabled = value) } }
        }
        SettingRow("提醒声音") {
            Text(state.prefs.sound, color = colors.muted, fontSize = captionSize())
        }
    }
}

/** V11 alarm-spot：顶部状态卡，显示上课提醒提前量与精确闹钟授权状态。 */
@Composable
private fun AlarmSpot(minutes: Int, exactGranted: Boolean, notifyEnabled: Boolean) {
    val colors = LocalKexuColors.current
    val container = if (colors.dark) colors.surface2 else Color(0xFFE9F3ED)
    val foreground = if (colors.dark) colors.ok else Color(0xFF24624B)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(container)
            .padding(horizontal = 21.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "$minutes 分钟前",
                color = foreground,
                fontSize = 34.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = (-1.3).sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "上课提醒 · 精确闹钟${if (exactGranted) "已授权" else "未授权"}",
                color = foreground.copy(alpha = 0.86f),
                fontSize = captionSize()
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            if (notifyEnabled) "通知已开" else "通知已关",
            color = foreground,
            fontSize = captionSize(),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(colors.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

// ------------------------------------------------------------------ 外观与显示

@Composable
private fun AppearanceSettings(state: AppUiState, vm: KexuViewModel, onBack: () -> Unit) {
    val colors = LocalKexuColors.current
    val fontOptions = listOf("80" to "小", "100" to "标准", "115" to "大", "130" to "特大")
    SettingsPage("外观与显示", onBack) {
        SettingsHero(
            title = "阅读舒服，才愿意每天打开",
            body = "对比清楚、正文明显，辅助信息各有位置，而不是堆满小字。",
            container = if (colors.dark) colors.surface2 else Color(0xFFEDEAF8),
            titleColor = if (colors.dark) colors.accent4 else Color(0xFF5C4EA7),
            bodyColor = if (colors.dark) colors.body else Color(0xFF675F88)
        )

        SectionTitle("显示模式")
        Row(Modifier.fillMaxWidth().padding(top = 2.dp, bottom = 14.dp)) {
            KSegmented(
                listOf("light" to "浅色", "dark" to "深色", "system" to "跟随系统"),
                state.prefs.themeMode
            ) { value -> vm.updatePrefs { it.copy(themeMode = value) } }
        }
        HorizontalDivider(color = colors.line)

        SectionTitle("主色调")
        Row(
            Modifier.fillMaxWidth().padding(vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            listOf("#305FD6", "#167C75", "#775CBB", "#BA7336").forEach { hex ->
                val selected = hex.equals(state.prefs.accentHex, ignoreCase = true)
                Box(
                    Modifier
                        .size(51.dp)
                        .clip(CircleShape)
                        .clickable { vm.updatePrefs { it.copy(accentHex = hex) } },
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Box(Modifier.size(51.dp).border(2.dp, colors.ink, CircleShape))
                    }
                    Box(
                        Modifier
                            .size(43.dp)
                            .background(hexColor(hex), CircleShape)
                            .border(4.dp, colors.surface, CircleShape)
                    )
                }
            }
        }

        SectionTitle("字体大小")
        SettingBlock("页面标题", "各页面大标题") {
            KSegmented(fontOptions, state.prefs.fontTitleScale.toString()) { value ->
                vm.updatePrefs { it.copy(fontTitleScale = value.toInt()) }
            }
        }
        SettingBlock("正文文字", "任务、课程与设置项") {
            KSegmented(fontOptions, state.prefs.fontBodyScale.toString()) { value ->
                vm.updatePrefs { it.copy(fontBodyScale = value.toInt()) }
            }
        }
        SettingBlock("辅助说明", "日期、提示与副标题") {
            KSegmented(fontOptions, state.prefs.fontCaptionScale.toString()) { value ->
                vm.updatePrefs { it.copy(fontCaptionScale = value.toInt()) }
            }
        }
        SettingBlock("课表文字", "课表格子内的文字") {
            KSegmented(fontOptions, state.prefs.fontTimetableScale.toString()) { value ->
                vm.updatePrefs { it.copy(fontTimetableScale = value.toInt()) }
            }
        }

        SectionTitle("透明度与质感")
        SettingBlock("说明文字透明度", "眉标、辅助说明与提示文字") {
            KSegmented(
                listOf("100" to "不透明", "85" to "浅一点", "70" to "更浅"),
                state.prefs.textOpacity.toString()
            ) { value -> vm.updatePrefs { it.copy(textOpacity = value.toInt()) } }
        }
        SettingBlock("色块透明度", "卡片底色、浅色块与分隔线") {
            KSegmented(
                listOf("100" to "不透明", "85" to "浅一点", "70" to "更透"),
                state.prefs.blockOpacity.toString()
            ) { value -> vm.updatePrefs { it.copy(blockOpacity = value.toInt()) } }
        }
        SettingBlock("玻璃质感", "大卡片使用半透明磨砂效果") {
            KSwitch(state.prefs.glassEnabled) { on ->
                vm.updatePrefs { p -> p.copy(glassEnabled = on) }
            }
        }
        SettingBlock("玻璃边缘畸变", "卡片边缘的折射高光与暗边") {
            KSwitch(state.prefs.glassDistortion) { on ->
                vm.updatePrefs { p -> p.copy(glassDistortion = on) }
            }
        }

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
                .clip(RoundedCornerShape(17.dp))
                .background(colors.surface2)
                .padding(20.dp)
        ) {
            Text("读起来更轻松", color = colors.ink, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(8.dp))
            Text(
                "任务名称与时间一眼看清，不重要的信息不会抢占注意力。",
                color = colors.body,
                fontSize = 15.sp,
                lineHeight = 22.sp
            )
        }
    }
}

// ------------------------------------------------------------------ 数据与备份

@Composable
private fun DataSettings(state: AppUiState, vm: KexuViewModel, onBack: () -> Unit, showMessage: (String) -> Unit) {
    val colors = LocalKexuColors.current
    var showAbout by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var confirmClearDone by remember { mutableStateOf(false) }
    var selfTestResult by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val json = vm.createBackupJson()
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
                showMessage(if (ok) "已导出备份文件" else "导出失败")
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val text = runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                if (text.isNullOrBlank()) {
                    showMessage("读取文件失败")
                } else {
                    val ok = vm.restoreBackupJson(text)
                    showMessage(if (ok) "已导入备份" else "备份格式不正确")
                }
            }
        }
    }
    val icsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) {
            scope.launch {
                val ics = IcsExporter.export(state.semester, state.periods, state.courses)
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(ics.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
                showMessage(if (ok) "已导出课程表" else "导出失败")
            }
        }
    }
    val taskCsvLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) {
            scope.launch {
                val csv = TaskCsvExporter.export(state.tasks)
                val ok = runCatching {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(csv.toByteArray(Charsets.UTF_8)) }
                }.isSuccess
                showMessage(if (ok) "已导出任务清单" else "导出失败")
            }
        }
    }

    SettingsPage("数据与备份", onBack) {
        SettingsHero(
            title = "你的安排，保存在本地",
            body = "课程、任务与目标只在本机 Room 数据库，可随时导出 JSON 备份。",
            container = if (colors.dark) colors.surface2 else Color(0xFFEAF3EE),
            titleColor = if (colors.dark) colors.ok else Color(0xFF22684F),
            bodyColor = if (colors.dark) colors.body else Color(0xFF45695C)
        )

        SectionTitle("备份与恢复")
        DataActionRow("导出备份（JSON）", "↗") {
            exportLauncher.launch("kexu-backup-" + LocalDate.now() + ".json")
        }
        DataActionRow("导入备份（JSON）", "↘") {
            importLauncher.launch(arrayOf("application/json"))
        }

        SectionTitle("导出与分享")
        DataActionRow("导出课程表（.ics）", "↗") { icsLauncher.launch("kexu-timetable.ics") }
        DataActionRow("导出任务清单（.csv）", "↗") { taskCsvLauncher.launch("kexu-tasks.csv") }

        SectionTitle("示例与清理")
        DataActionRow("恢复示例数据", "↻") { confirmReset = true }
        DataActionRow("清除已完成任务", "×") { confirmClearDone = true }

        DangerSectionTitle("危险操作")
        DataActionRow("清除全部数据", "×", danger = true) { confirmClear = true }

        SectionTitle("检查")
        DataActionRow("运行自检", "运行") {
            val checks = listOf(
                "教学周换算" to (KexuCore.weekIndexForDate(state.semester.startDate, LocalDate.of(2026, 10, 26)) == 8),
                "单双周" to (KexuCore.parityMatch("odd", 9) && !KexuCore.parityMatch("odd", 8)),
                "节次布局" to (KexuCore.periodLayout(state.periods).rows.isNotEmpty()),
                "空档压缩" to (KexuCore.periodLayout(state.periods).rows.firstOrNull { it.gapLabel == "午休" }?.gapPx?.let { it <= 30f } ?: true),
                "课程色对比度" to state.courses.take(3).all {
                    KexuCore.contrastRatio(KexuCore.toneFor(it.colorHex, false).bg, KexuCore.toneFor(it.colorHex, false).ink) >= 4.5
                }
            )
            val passed = checks.count { it.second }
            selfTestResult = "共 ${checks.size} 项检查，通过 $passed 项。\n" + checks.joinToString("\n") { (name, ok) -> (if (ok) "✓ " else "✗ ") + name }
        }
        DataActionRow("使用说明与关于", "查看") { showAbout = true }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })
    if (confirmReset) ConfirmDialog("恢复示例数据", "会覆盖当前课程、任务与长期目标，确定继续吗？", "恢复", onConfirm = {
        confirmReset = false
        vm.resetDemoData()
        showMessage("已恢复示例数据")
    }, onDismiss = { confirmReset = false })
    if (confirmClearDone) ConfirmDialog("清除已完成任务", "将删除所有已完成任务，且无法撤销。", "清除", danger = true, onConfirm = {
        confirmClearDone = false
        vm.clearDoneTasks()
        showMessage("已清除完成任务")
    }, onDismiss = { confirmClearDone = false })
    if (confirmClear) ConfirmDialog("清除全部数据", "所有本地课程、任务与目标都会被删除，且无法撤销。", "清除", danger = true, onConfirm = {
        confirmClear = false
        vm.clearAllData()
        showMessage("已清除全部数据")
    }, onDismiss = { confirmClear = false })
    selfTestResult?.let { result ->
        KexuDialog(title = "逻辑自检", onDismiss = { selfTestResult = null }) {
            Text(result, color = colors.ink2, fontSize = captionSize(), lineHeight = 22.sp)
        }
    }
}

// ------------------------------------------------------------------ 关于

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    val colors = LocalKexuColors.current
    KexuDialog(title = "使用说明与关于", onDismiss = onDismiss) {
        Column {
            Text(
                "课序 V${BuildConfig.VERSION_NAME}",
                color = colors.ink,
                fontSize = 17.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(14.dp))
            AboutLine(
                "数据与隐私",
                "所有课程、任务与设置只保存在本机 Room 数据库；只有「检查更新」会访问 GitHub，不上传任何内容。可在「数据与备份」导出 JSON，换机时导入恢复。"
            )
            Spacer(Modifier.height(14.dp))
            AboutLine(
                "导入与提醒",
                "支持 CSV / ICS / PDF / XLSX / HTML / 图片（本地 OCR）多格式课表导入；提醒默认由 WorkManager 兜底，授权精确闹钟后可准点触发。"
            )
            Spacer(Modifier.height(14.dp))
            AboutLine(
                "已知限制",
                "桌面小组件与周报增强暂不在 1.0 范围内；导入结果会先进入可编辑预览，再写入课表。"
            )
        }
    }
}

@Composable
private fun AboutLine(title: String, body: String) {
    val colors = LocalKexuColors.current
    Column {
        Text(title, color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(3.dp))
        Text(body, color = colors.body, fontSize = captionSize(), lineHeight = 21.sp)
    }
}

/** 优先使用 ContentResolver 的显示名（含扩展名），供格式检测使用；取不到时回退路径段。 */
private fun resolveFileName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
        val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (index >= 0 && cursor.moveToFirst()) cursor.getString(index)?.takeIf { it.isNotBlank() } else null
    }
}.getOrNull()
