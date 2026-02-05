package com.example.mytransl.ui.nav

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavHostController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.mytransl.service.TranslationService
import com.example.mytransl.system.permissions.CapturePermissionStore
import com.example.mytransl.system.service.TranslationServiceState
import com.example.mytransl.ui.home.MainScreen
import com.example.mytransl.ui.permissions.PermissionsScreen
import com.example.mytransl.ui.settings.SettingsScreen
import com.example.mytransl.ui.translate.TranslateScreen
import com.example.mytransl.ui.components.UpdateDialog
import com.example.mytransl.utils.UpdateManager
import com.example.mytransl.data.settings.SettingsRepository
import com.example.mytransl.data.settings.SettingsState
import kotlinx.coroutines.launch

object Routes {
    const val Home = "home"
    const val Translate = "translate"
    const val Settings = "settings"
    const val Permissions = "permissions"
}

@Composable
fun AppNav(
    navController: NavHostController = rememberNavController()
) {
    val scope = rememberCoroutineScope()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevelRoutes = remember {
        listOf(Routes.Home, Routes.Translate, Routes.Settings)
    }

    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = SettingsState())
    
    // updateDialogItem: Triple(versionName, versionCode, url, changelog)
    var updateDialogItem by remember { mutableStateOf<UpdateInfo?>(null) }
    var hasNewVersion by remember { mutableStateOf(false) }

    fun doCheckUpdate(showToastIfLatest: Boolean = false) {
        UpdateManager.checkUpdate(context) { versionName, changelog, url, versionCode ->
            hasNewVersion = true
            // 只有当服务器版本号大于用户忽略的版本号时，才主动弹出强制更新弹窗
            if (versionCode > settings.ignoredVersionCode) {
                updateDialogItem = UpdateInfo(versionName, versionCode, url, changelog)
            }
        }
    }

    LaunchedEffect(Unit) {
        doCheckUpdate()
    }

    // 弹窗逻辑
    if (updateDialogItem != null) {
        UpdateDialog(
            versionName = updateDialogItem!!.versionName,
            changelog = updateDialogItem!!.changelog.ifBlank { "暂无更新日志" },
            onDismiss = {
                val currentInfo = updateDialogItem!!
                scope.launch {
                    repo.saveSettings(settings.copy(ignoredVersionCode = currentInfo.versionCode))
                }
                updateDialogItem = null
            },
            onConfirm = {
                UpdateManager.downloadUpdate(context, updateDialogItem!!.url)
                updateDialogItem = null
            }
        )
    }

    Scaffold(
        bottomBar = {
            if (currentRoute in topLevelRoutes) {
                AppBottomBar(
                    currentRoute = currentRoute ?: Routes.Home,
                    onNavigate = { route ->
                        navController.navigate(route) {
                            launchSingleTop = true
                            restoreState = true
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Routes.Home,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Routes.Home) {
                val running by TranslationServiceState.running.collectAsState()
                MainScreen(
                    onOpenPermissions = { navController.navigate(Routes.Permissions) },
                    onStart = {
                        val intent = Intent(context, TranslationService::class.java)
                            .setAction(TranslationService.ACTION_START)
                            .putExtra(TranslationService.EXTRA_RESULT_CODE, CapturePermissionStore.resultCode)
                            .putExtra(TranslationService.EXTRA_RESULT_DATA, CapturePermissionStore.data)
                        ContextCompat.startForegroundService(context, intent)
                        (context as? Activity)?.moveTaskToBack(true)
                    },
                    onStop = {
                        val intent = Intent(context, TranslationService::class.java)
                            .setAction(TranslationService.ACTION_STOP)
                        context.startService(intent)
                    },
                    isRunning = running
                )
            }
            composable(Routes.Translate) {
                TranslateScreen()
            }
            composable(Routes.Settings) {
                SettingsScreen(
                    hasNewVersion = hasNewVersion,
                    onCheckUpdate = {
                        // 手动点击检查更新时，无视“忽略此版本”的标记，强行显示弹窗
                        UpdateManager.checkUpdate(context) { versionName, changelog, url, versionCode ->
                            updateDialogItem = UpdateInfo(versionName, versionCode, url, changelog)
                        }
                    },
                    onBack = { navController.popBackStack() }
                )
            }
            composable(Routes.Permissions) {
                PermissionsScreen(
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}

private data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val url: String,
    val changelog: String
)

@Composable
private fun AppBottomBar(
    currentRoute: String,
    onNavigate: (String) -> Unit
) {
    val items = remember {
        listOf(
            Triple(Routes.Home, "屏幕翻译", Icons.Filled.Home),
            Triple(Routes.Translate, "图文翻译", Icons.Filled.Translate),
            Triple(Routes.Settings, "设置", Icons.Filled.Settings)
        )
    }

    NavigationBar(
        containerColor = Color(0xFFFAFAFA),
        contentColor = Color(0xFF64748B),
        tonalElevation = 0.dp,
        modifier = Modifier.height(90.dp)
    ) {
        items.forEach { (route, label, icon) ->
            val selected = currentRoute == route
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(route) },
                icon = {
                    Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) Color(0xFF10B981) else Color(0xFF94A3B8),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color(0xFF10B981) else Color(0xFF64748B)
                        )
                    }
                },
                label = null,
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Color(0xFF10B981),
                    selectedTextColor = Color(0xFF10B981),
                    unselectedIconColor = Color(0xFF94A3B8),
                    unselectedTextColor = Color(0xFF64748B),
                    indicatorColor = Color(0xFFD1FAE5)
                )
            )
        }
    }
}
