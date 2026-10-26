package com.kexu.app.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.BuildConfig
import com.kexu.app.data.UpdateChecker
import com.kexu.app.data.UpdateInfo
import com.kexu.app.data.UpdateResult
import com.kexu.app.ui.components.KexuDialog
import com.kexu.app.ui.components.PrimaryButton
import com.kexu.app.ui.theme.LocalKexuColors
import java.util.Locale

/**
 * 设置页「检查更新」入口弹出的对话框：打开即自动请求 GitHub Releases 的 latest 版本。
 *
 * 状态：检查中 → 已是最新 / 发现有新版本（可打开 APK 下载链接或 Release 页）/ 失败（可重试）。
 * 只有这个对话框会联网，App 其余功能仍然完全离线。
 *
 * @param currentVersion 当前安装版本，默认取 BuildConfig.VERSION_NAME（如 "1.2.6"）。
 * @param onDismiss 关闭对话框（「稍后」与标题栏关闭走同一回调）。
 */
@Composable
fun CheckUpdateDialog(
    currentVersion: String = BuildConfig.VERSION_NAME,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var attempt by remember { mutableStateOf(0) }
    var result by remember { mutableStateOf<UpdateResult?>(null) }
    var openError by remember { mutableStateOf<String?>(null) }

    // attempt 变化（重试）时重新发起检查；null 表示正在检查更新。
    LaunchedEffect(currentVersion, attempt) {
        result = null
        openError = null
        result = UpdateChecker.checkForUpdate(currentVersion)
    }

    fun openUrl(url: String) {
        if (url.isBlank()) {
            openError = "没有可打开的更新链接，请稍后再试"
            return
        }
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (runCatching { context.startActivity(intent) }.isFailure) {
            openError = "没有找到能打开链接的应用，请手动访问：$url"
        }
    }

    KexuDialog(title = "检查更新", onDismiss = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            val state = result
            when {
                state == null -> CheckingContent()
                state is UpdateResult.UpToDate -> UpToDateContent(currentVersion)
                state is UpdateResult.Available -> AvailableContent(
                    info = state.info,
                    currentVersion = currentVersion,
                    openError = openError,
                    onUpdate = { openUrl(state.info.apkUrl ?: state.info.releaseUrl) },
                    onDismiss = onDismiss
                )
                state is UpdateResult.Failed -> FailedContent(
                    message = state.message,
                    onRetry = { attempt += 1 }
                )
            }
        }
    }
}

@Composable
private fun CheckingContent() {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = colors.accent
            )
            Spacer(Modifier.width(12.dp))
            Text("正在检查更新…", color = colors.ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            "正在连接 GitHub Releases 获取最新版本，通常几秒内完成。",
            color = colors.muted,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
    }
}

@Composable
private fun UpToDateContent(currentVersion: String) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        Text("已是最新版本", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(
            "当前版本 V$currentVersion，暂时没有更新的版本。",
            color = colors.body,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
    }
}

@Composable
private fun AvailableContent(
    info: UpdateInfo,
    currentVersion: String,
    openError: String?,
    onUpdate: () -> Unit,
    onDismiss: () -> Unit
) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        Text(
            "发现新版本 V${info.versionName}",
            color = colors.ink,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold
        )
        Spacer(Modifier.height(6.dp))
        Text("当前版本 V$currentVersion", color = colors.body, fontSize = 14.sp)
        val meta = buildList {
            if (info.publishedAt.length >= 10) add("发布于 ${info.publishedAt.take(10)}")
            val size = formatApkSize(info.apkSize)
            if (size.isNotEmpty()) add("安装包 $size")
        }
        if (meta.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(meta.joinToString(" · "), color = colors.muted, fontSize = 13.sp)
        }
        Spacer(Modifier.height(12.dp))
        HorizontalDivider(color = colors.line)
        Spacer(Modifier.height(12.dp))
        Text("更新说明", color = colors.ink, fontSize = 15.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text(
            previewNotes(info.notes).ifBlank { "本次发布暂无更新说明。" },
            color = colors.body,
            fontSize = 14.sp,
            lineHeight = 21.sp
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (info.apkUrl != null) {
                "下载后覆盖安装即可完成升级，本地课程、任务与设置都会保留。"
            } else {
                "将打开 GitHub Release 页面，可手动下载 APK；下载后覆盖安装即可完成升级，本地数据都会保留。"
            },
            color = colors.muted,
            fontSize = 13.sp,
            lineHeight = 19.sp
        )
        if (openError != null) {
            Spacer(Modifier.height(8.dp))
            Text(openError, color = colors.danger, fontSize = 13.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                Text("稍后", color = colors.muted, fontSize = 15.sp)
            }
            Spacer(Modifier.width(10.dp))
            PrimaryButton("立即更新", modifier = Modifier.weight(1f)) { onUpdate() }
        }
    }
}

@Composable
private fun FailedContent(message: String, onRetry: () -> Unit) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth()) {
        Text("检查更新失败", color = colors.ink, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
        Spacer(Modifier.height(8.dp))
        Text(message, color = colors.body, fontSize = 14.sp, lineHeight = 21.sp)
        Spacer(Modifier.height(16.dp))
        PrimaryButton("重试", modifier = Modifier.fillMaxWidth()) { onRetry() }
    }
}

// ------------------------------------------------------------------ 文案处理

private val HEADING_PREFIX = Regex("^#{1,6}\\s*")
private val BULLET_PREFIX = Regex("^[-*+]\\s+")
private val CHECKBOX_PREFIX = Regex("^\\[[ xX]]\\s*")
private val MARKDOWN_LINK = Regex("\\[([^\\]]+)]\\(([^)]+)\\)")

/** 更新说明只展示前 [maxLines] 行，并做最小化的 Markdown 清理（标题井号、列表符号、粗体等）。 */
private fun previewNotes(raw: String, maxLines: Int = 8, maxChars: Int = 420): String {
    if (raw.isBlank()) return ""
    val lines = raw.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            line
                .replace(HEADING_PREFIX, "")
                .replace(CHECKBOX_PREFIX, "")
                .replace(BULLET_PREFIX, "· ")
                .replace(MARKDOWN_LINK, "\$1")
                .replace("**", "")
                .replace("`", "")
                .trim()
        }
        .filter { it.isNotEmpty() && it != "---" && it != "***" }
    if (lines.isEmpty()) return ""
    val shown = lines.take(maxLines)
    var text = shown.joinToString("\n")
    var truncated = lines.size > shown.size
    if (text.length > maxChars) {
        text = text.take(maxChars).trimEnd()
        truncated = true
    }
    return if (truncated) "$text…" else text
}

private fun formatApkSize(bytes: Long): String = when {
    bytes <= 0L -> ""
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes.toDouble() / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.0f KB", bytes.toDouble() / 1024.0)
}
