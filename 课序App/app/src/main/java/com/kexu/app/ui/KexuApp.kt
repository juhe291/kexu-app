package com.kexu.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kexu.app.ui.components.ConfirmDialog
import com.kexu.app.ui.planning.PlanningScreen
import com.kexu.app.ui.settings.SettingsScreen
import com.kexu.app.ui.tasks.TasksScreen
import com.kexu.app.ui.theme.LocalKexuColors
import com.kexu.app.ui.timetable.TimetableScreen
import com.kexu.app.ui.today.TodayScreen
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("今天", Icons.Filled.Home),
    Tab("课表", Icons.Filled.DateRange),
    Tab("待办", Icons.Filled.List),
    Tab("规划", Icons.Filled.Star),
    Tab("设置", Icons.Filled.Settings)
)

@Composable
fun KexuApp(state: AppUiState, vm: KexuViewModel) {
    val colors = LocalKexuColors.current
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var settingsStartPage by remember { mutableStateOf<String?>(null) }
    var showExitConfirm by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? android.app.Activity

    BackHandler {
        if (selectedTab != 0) {
            selectedTab = 0
        } else {
            showExitConfirm = true
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val showMessage: (String) -> Unit = { message ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(message)
        }
    }

    if (showExitConfirm) {
        ConfirmDialog(
            title = "退出课序",
            message = "要退出应用吗？",
            confirmText = "退出",
            onConfirm = {
                showExitConfirm = false
                activity?.finish()
            },
            onDismiss = { showExitConfirm = false }
        )
    }

    Scaffold(
        containerColor = colors.surface,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (!state.loading) {
                V11TopBar(
                    weekLabel = "第 ${state.currentWeek()} 教学周",
                    onOpenReminders = {
                        settingsStartPage = "reminders"
                        selectedTab = 4
                    }
                )
            }
        },
        bottomBar = {
            V11BottomNav(
                selected = selectedTab,
                onSelect = { index ->
                    if (index == 4) settingsStartPage = null
                    selectedTab = index
                }
            )
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(colors.surface)
        ) {
            if (state.loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("课序", color = colors.accent, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                }
            } else {
                Crossfade(
                    targetState = selectedTab,
                    animationSpec = tween(210, easing = FastOutSlowInEasing),
                    label = "tab"
                ) { tab ->
                    when (tab) {
                        0 -> TodayScreen(state, vm, onGoPlanning = { selectedTab = 3 })
                        1 -> TimetableScreen(
                            state = state,
                            vm = vm,
                            onOpenPeriodSettings = {
                                settingsStartPage = "periods"
                                selectedTab = 4
                            }
                        )
                        2 -> TasksScreen(state, vm, onGoPlanning = { selectedTab = 3 })
                        3 -> PlanningScreen(
                            state = state,
                            vm = vm,
                            showMessage = showMessage,
                            onGoSettings = {
                                settingsStartPage = "schedule"
                                selectedTab = 4
                            },
                            onGoTasks = { selectedTab = 2 }
                        )
                        else -> SettingsScreen(state, vm, showMessage, initialPage = settingsStartPage)
                    }
                }
            }
        }
    }
}

/** V11 顶栏：品牌 + 教学周 + 提醒入口（适配状态栏，避免被系统状态栏遮挡）。 */
@Composable
private fun V11TopBar(weekLabel: String, onOpenReminders: () -> Unit) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth().background(colors.surface).statusBarsPadding()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(57.dp)
                .padding(horizontal = 23.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(19.dp)
                        .rotate(-8f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            LogoSq(1f)
                            LogoSq(0.47f)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            LogoSq(0.75f)
                            LogoSq(0.25f)
                        }
                    }
                }
                Spacer(Modifier.width(9.dp))
                Text(
                    "课序",
                    color = colors.ink,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp
                )
            }
            Spacer(Modifier.weight(1f))
            Text(weekLabel, color = colors.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.width(15.dp))
            Icon(
                Icons.Filled.Notifications,
                contentDescription = "提醒设置",
                tint = colors.ink2,
                modifier = Modifier
                    .size(21.dp)
                    .clickable(onClick = onOpenReminders)
            )
        }
        HorizontalDivider(color = colors.line)
    }
}

@Composable
private fun LogoSq(alpha: Float) {
    val colors = LocalKexuColors.current
    Box(
        Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(colors.accent.copy(alpha = alpha))
    )
}

/** V11 底部导航：每个 Tab 为「图标胶囊 + 12sp 标签」，选中态为浅色胶囊（适配系统导航条）。 */
@Composable
private fun V11BottomNav(selected: Int, onSelect: (Int) -> Unit) {
    val colors = LocalKexuColors.current
    Column(Modifier.fillMaxWidth().background(colors.surface).navigationBarsPadding()) {
        HorizontalDivider(color = colors.line)
        Row(
            Modifier
                .fillMaxWidth()
                .height(71.dp)
                .padding(horizontal = 3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TABS.forEachIndexed { index, tab ->
                val active = selected == index
                Column(
                    Modifier
                        .weight(1f)
                        .clickable { onSelect(index) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        Modifier
                            .size(42.dp, 29.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) colors.accentSoft else Color.Transparent),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            tint = if (active) colors.accent else colors.muted,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        tab.label,
                        color = if (active) colors.accent else colors.muted,
                        fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.ExtraBold else FontWeight.Medium
                    )
                }
            }
        }
    }
}
