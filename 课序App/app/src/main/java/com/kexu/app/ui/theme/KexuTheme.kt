package com.kexu.app.ui.theme

import android.graphics.Color as AndroidColor
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.kexu.app.domain.KexuCore

/**
 * 课序 V11 设计令牌（参考 V11 视觉重构稿）：
 * 中性底 + 墨蓝文字 + 蓝色主色，强调「大字号、少小字、层次分明」。
 */
data class KexuColors(
    val dark: Boolean,
    val surface: Color,
    val surface2: Color,
    val ink: Color,
    val ink2: Color,
    val muted: Color,
    val line: Color,
    val gridLine: Color,
    val accent: Color,
    val accentSoft: Color,
    val danger: Color,
    val warn: Color,
    val ok: Color,
    val accent2: Color,
    val accent3: Color,
    val accent4: Color,
    val accent5: Color,
    /** V11 卡片色：正文次级文字（比 muted 更清楚）。 */
    val body: Color = ink2
)

val LocalKexuColors = staticCompositionLocalOf { lightColors("#305FD6") }

/** 分区块的字体缩放，1f = 标准。 */
data class FontScales(
    val title: Float = 1f,
    val body: Float = 1f,
    val caption: Float = 1f,
    val timetable: Float = 1f
)
val LocalFontScales = staticCompositionLocalOf { FontScales() }

fun hexColor(hex: String): Color = runCatching { Color(AndroidColor.parseColor(hex)) }
    .getOrDefault(Color(0xFF305FD6))

fun toneOf(colorHex: String, dark: Boolean): CourseTone {
    val tone = KexuCore.toneFor(colorHex, dark)
    return CourseTone(
        base = hexColor(colorHex),
        bg = hexColor(tone.bg),
        line = hexColor(tone.line),
        ink = hexColor(tone.ink)
    )
}

data class CourseTone(val base: Color, val bg: Color, val line: Color, val ink: Color)

private fun lightColors(accentHex: String): KexuColors {
    val accent = hexColor(accentHex)
    return KexuColors(
        dark = false,
        surface = Color(0xFFFBFCFD),
        surface2 = Color(0xFFEDF2F8),
        ink = Color(0xFF182538),
        ink2 = Color(0xFF35465A),
        muted = Color(0xFF596B7F),
        line = Color(0xFFE4EAF1),
        gridLine = Color(0xFFE9EEF4),
        accent = accent,
        accentSoft = Color(0xFFE4ECFF),
        danger = Color(0xFFB75B53),
        warn = Color(0xFFA66A34),
        ok = Color(0xFF237E63),
        accent2 = Color(0xFF18796E),
        accent3 = Color(0xFFA66A34),
        accent4 = Color(0xFF775CBB),
        accent5 = Color(0xFFB05A84),
        body = Color(0xFF35465A)
    )
}

private fun darkColors(accentHex: String): KexuColors {
    val accent = hexColor(KexuCore.mixHex(accentHex, "#F1F5FC", 0.34f))
    return KexuColors(
        dark = true,
        surface = Color(0xFF141E2B),
        surface2 = Color(0xFF253347),
        ink = Color(0xFFF1F5FC),
        ink2 = Color(0xFFD0D9E7),
        muted = Color(0xFFACB9C9),
        line = Color(0xFF344355),
        gridLine = Color(0xFF2E3C4E),
        accent = accent,
        accentSoft = Color(0xFF263D68),
        danger = Color(0xFFFFA8A1),
        warn = Color(0xFFF0BD87),
        ok = Color(0xFF8ACEAA),
        accent2 = Color(0xFF8BD7C5),
        accent3 = Color(0xFFF0BD87),
        accent4 = Color(0xFFB9A7E8),
        accent5 = Color(0xFFE8A0BD),
        body = Color(0xFFD0D9E7)
    )
}

/**
 * 与 V11 对齐的字号：正文 16 / 次要 14 / 辅助 13。
 * 页面内的显式字号也遵循同样的层级（标题 35、分区 21、条目 17）。
 */
private val KexuTypography = Typography(
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 19.sp),
    labelSmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.Bold),
    headlineSmall = TextStyle(fontSize = 26.sp, lineHeight = 32.sp, fontWeight = FontWeight.ExtraBold)
)

@Composable
fun KexuTheme(
    themeMode: String,
    accentHex: String,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (themeMode) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val colors = remember(dark, accentHex) {
        if (dark) darkColors(accentHex) else lightColors(accentHex)
    }
    val scheme = remember(dark, colors) {
        if (dark) {
            darkColorScheme(
                primary = colors.accent,
                onPrimary = Color(0xFF0B1526),
                background = colors.surface,
                onBackground = colors.ink,
                surface = colors.surface,
                onSurface = colors.ink,
                surfaceVariant = colors.surface2,
                onSurfaceVariant = colors.ink2,
                outline = colors.line,
                error = colors.danger
            )
        } else {
            lightColorScheme(
                primary = colors.accent,
                onPrimary = Color.White,
                background = colors.surface,
                onBackground = colors.ink,
                surface = colors.surface,
                onSurface = colors.ink,
                surfaceVariant = colors.surface2,
                onSurfaceVariant = colors.ink2,
                outline = colors.line,
                error = colors.danger
            )
        }
    }
    CompositionLocalProvider(LocalKexuColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = KexuTypography, content = content)
    }
}
