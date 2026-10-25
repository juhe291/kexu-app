package com.kexu.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.kexu.app.notify.ReminderScheduler
import com.kexu.app.ui.KexuApp
import com.kexu.app.ui.KexuViewModel
import com.kexu.app.ui.theme.FontScales
import com.kexu.app.ui.theme.KexuTheme
import com.kexu.app.ui.theme.LocalFontScales

class MainActivity : ComponentActivity() {

    private val viewModel: KexuViewModel by viewModels()
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* 结果不影响使用 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 适配 Android 15 强制边到边：内容延伸到状态栏/导航栏，由 Compose 侧 inset padding 让位
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        ReminderScheduler.ensureChannels(applicationContext)
        setContent {
            val state by viewModel.state.collectAsState()
            val systemDark = isSystemInDarkTheme()
            val dark = when (state.prefs.themeMode) {
                "dark" -> true
                "light" -> false
                else -> systemDark
            }
            LaunchedEffect(dark) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            LaunchedEffect(state.loading, state.courses, state.tasks, state.semester, state.prefs) {
                if (!state.loading) {
                    ReminderScheduler.sync(applicationContext, state)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        state.prefs.notifyEnabled &&
                        ContextCompat.checkSelfPermission(
                            applicationContext,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }
            KexuTheme(themeMode = state.prefs.themeMode, accentHex = state.prefs.accentHex) {
                val prefs = state.prefs
                val scales = FontScales(
                    title = prefs.fontTitleScale / 100f,
                    body = prefs.fontBodyScale / 100f,
                    caption = prefs.fontCaptionScale / 100f,
                    timetable = prefs.fontTimetableScale / 100f
                )
                val density = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(density = density.density, fontScale = density.fontScale * scales.body),
                    LocalFontScales provides scales
                ) {
                    KexuApp(state, viewModel)
                }
            }
        }
    }
}
