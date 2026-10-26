package com.kexu.app.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.BuildConfig
import com.kexu.app.ui.components.EnglishCaption
import com.kexu.app.ui.components.SettingsHero
import com.kexu.app.ui.components.SettingsSubHeader
import com.kexu.app.ui.theme.LocalKexuColors

/** 使用说明书：按「导入 → 安排 → 提醒 → 备份」的顺序讲清楚每个模块怎么用。 */
@Composable
fun UsageGuideScreen(onBack: () -> Unit) {
    val colors = LocalKexuColors.current
    Column(
        Modifier
            .fillMaxSize()
            .background(colors.surface)
            .verticalScroll(rememberScrollState())
            .padding(start = 23.dp, end = 23.dp, top = 22.dp, bottom = 36.dp)
    ) {
        EnglishCaption("USER GUIDE · OFFLINE")
        SettingsSubHeader(title = "使用说明", onBack = onBack)
        SettingsHero(
            title = "课序怎么用",
            body = "导入课表 → 添加任务 → 规划时间 → 提醒与复盘。核心功能全部离线可用，只有「检查更新」会联网读取 GitHub。"
        )
        Spacer(Modifier.height(10.dp))

        GuideSection(
            title = "1. 导入课表",
            lines = listOf(
                "设置 → 课程与学期 → 从文件导入，支持 CSV / ICS / PDF / XLSX / HTML / 图片（PNG、JPG）/ 课序 JSON 模板。",
                "文字版 PDF 直接解析；扫描版 PDF 和图片使用本地离线 OCR，识别后进入「可编辑预览」。",
                "预览里可以逐条勾选，修改课程名、教师、地点、星期、节次、周次；确认后才写入课表，写库失败不会破坏原有数据。"
            )
        )
        GuideSection(
            title = "2. 停课与调课",
            lines = listOf(
                "课表里点某次课程 → 课程详情 →「停课一次」或「调课…」。",
                "只影响该教学周的那一次课，其他周不变；已停课/已调课的块会带标记，点开可「恢复本次课」。",
                "课表、今天页、智能规划、本周复盘和课程提醒都会按调整后的安排计算。"
            )
        )
        GuideSection(
            title = "3. 重复任务",
            lines = listOf(
                "新建或编辑任务时把「重复」设为每天 / 每周，可再填「重复截止」日期。",
                "完成一条后会自动生成下一条（安排时间按天/周顺延）；同一系列不会重复生成。"
            )
        )
        GuideSection(
            title = "4. 提醒与精确闹钟",
            lines = listOf(
                "设置 → 提醒与闹钟：设置上课前、任务开始、截止日期提醒。",
                "开启「响铃式提醒」，并在 Android 12+ 按提示授予「精确闹钟」权限后，提醒会准点触发；未授权时自动用 WorkManager 兜底（可能延迟几分钟）。",
                "重启手机、修改时间或时区后，精确闹钟会自动重建。"
            )
        )
        GuideSection(
            title = "5. 规划与安排",
            lines = listOf(
                "待办里没安排时间的任务会进入「待安排」；到「规划」页选任务，课序会结合课程与空闲时间给出建议。",
                "可以「换个时间」或「换任务」，确认后任务写入日程，课表里会显示为任务块。",
                "没有待安排任务时，规划页会显示空状态；先去待办添加任务即可。"
            )
        )
        GuideSection(
            title = "6. 备份与导出",
            lines = listOf(
                "设置 → 数据与备份：导出 JSON 备份（换机恢复用）、导出课表 ICS（导入系统日历）、导出任务 CSV（Excel/WPS 打开）。",
                "「恢复示例数据」会覆盖当前数据；「清除全部数据」不可恢复，操作前请先导出备份。"
            )
        )
        GuideSection(
            title = "7. 常见问题",
            lines = listOf(
                "图片/扫描件识别不准：换更清晰、方向正确的课表截图，或改用 CSV / JSON 模板导入。",
                "导入时提示冲突：课序允许导入，只在预览和课表里标记冲突，方便和教务系统保持一致。",
                "提醒不准点：检查通知权限与「精确闹钟」授权（设置 → 提醒与闹钟）。"
            )
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "课序 V${BuildConfig.VERSION_NAME} · 数据保存在本机；只有检查更新会访问 GitHub，不上传任何内容。",
            color = colors.muted,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
    }
}

@Composable
private fun GuideSection(title: String, lines: List<String>) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth().padding(top = 22.dp)) {
        Text(
            title,
            color = colors.ink,
            fontSize = 19.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = (-0.4).sp
        )
        Spacer(Modifier.height(8.dp))
        lines.forEach { line ->
            Text(
                "· $line",
                color = colors.body,
                fontSize = 15.sp,
                lineHeight = 24.sp,
                modifier = Modifier.padding(bottom = 5.dp)
            )
        }
    }
}
