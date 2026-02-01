package com.example.mytransl.ui.nav

import android.app.Activity
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
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
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val topLevelRoutes = remember {
        listOf(Routes.Home, Routes.Translate, Routes.Settings)
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
                val context = LocalContext.current
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
                    // 自定义布局：图标和文字一起包裹在指示器中
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = label,
                            tint = if (selected) Color(0xFF10B981) else Color(0xFF94A3B8),
                            modifier = Modifier.size(24.dp)
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) Color(0xFF10B981) else Color(0xFF64748B)
                        )
                    }
                },
                label = null, // 不使用默认的 label，因为已经在 icon 中自定义了
                alwaysShowLabel = true,
                colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
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
