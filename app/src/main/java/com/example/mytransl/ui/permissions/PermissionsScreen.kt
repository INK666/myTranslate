package com.example.mytransl.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.example.mytransl.system.permissions.CapturePermissionStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val activity = context as? Activity

    var overlayGranted by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var notificationGranted by remember {
        mutableStateOf(
            Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var projectionGranted by remember { mutableStateOf(CapturePermissionStore.data != null) }

    val overlayLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        overlayGranted = Settings.canDrawOverlays(context)
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CapturePermissionStore.set(result.resultCode, result.data)
            projectionGranted = true
        }
    }

    val accent = MaterialTheme.colorScheme.primary
    val bgA = MaterialTheme.colorScheme.background
    val bgB = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    val headerBrush = Brush.linearGradient(listOf(bgA, bgB))
    val scrollState = rememberScrollState()
    val allReady = overlayGranted && notificationGranted && projectionGranted

    LaunchedEffect(overlayGranted, notificationGranted, projectionGranted) {
        if (overlayGranted && notificationGranted && !projectionGranted) {
            val mgr = context.getSystemService(MediaProjectionManager::class.java)
            projectionLauncher.launch(mgr.createScreenCaptureIntent())
        } else if (overlayGranted && notificationGranted && projectionGranted) {
            onBack()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("权限检查") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = Color.Transparent
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(headerBrush)
                .statusBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(scrollState)
                    .navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                HeaderCard(
                    allReady = allReady,
                    overlayGranted = overlayGranted,
                    notificationGranted = notificationGranted,
                    projectionGranted = projectionGranted
                )

                PermissionRow(
                    icon = Icons.Filled.Settings,
                    title = "悬浮窗权限",
                    description = "用于显示悬浮球与翻译结果窗口",
                    statusText = if (overlayGranted) "已开启" else "未开启",
                    statusTone = if (overlayGranted) PermissionTone.Success else PermissionTone.Warning,
                    actionText = if (overlayGranted) "已完成" else "去开启",
                    enabled = !overlayGranted
                ) {
                    val intent = Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                    overlayLauncher.launch(intent)
                }

                val notifEnabled = !notificationGranted && Build.VERSION.SDK_INT >= 33
                PermissionRow(
                    icon = Icons.Filled.Info,
                    title = "通知权限",
                    description = if (Build.VERSION.SDK_INT >= 33) "用于展示运行状态与错误提示" else "Android 13 以下默认允许",
                    statusText = if (notificationGranted) "已开启" else if (Build.VERSION.SDK_INT >= 33) "未开启" else "无需",
                    statusTone = if (notificationGranted) PermissionTone.Success else if (Build.VERSION.SDK_INT >= 33) PermissionTone.Warning else PermissionTone.Neutral,
                    actionText = if (!notifEnabled) "已完成" else "请求",
                    enabled = notifEnabled
                ) {
                    notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }

                PermissionRow(
                    icon = Icons.Filled.Search,
                    title = "截屏权限",
                    description = "用于识别屏幕文字（仅在本地处理截图）",
                    statusText = if (projectionGranted) "已获取" else "未获取",
                    statusTone = if (projectionGranted) PermissionTone.Success else PermissionTone.Warning,
                    actionText = if (projectionGranted) "已完成" else "获取",
                    enabled = !projectionGranted && activity != null
                ) {
                    val mgr = context.getSystemService(MediaProjectionManager::class.java)
                    projectionLauncher.launch(mgr.createScreenCaptureIntent())
                }

                Spacer(modifier = Modifier.height(4.dp))

                Button(
                    onClick = onBack,
                    enabled = allReady,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(
                        text = if (allReady) "完成" else "请先补全权限",
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Text(
                    text = "提示：权限齐全后才能启动悬浮翻译。",
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f),
                    modifier = Modifier.padding(bottom = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun PermissionRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String,
    statusText: String,
    statusTone: PermissionTone,
    actionText: String,
    enabled: Boolean,
    onAction: () -> Unit
) {
    val cardShape = RoundedCornerShape(18.dp)
    val statusBg = when (statusTone) {
        PermissionTone.Success -> MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        PermissionTone.Warning -> Color(0xFFFFB020).copy(alpha = 0.16f)
        PermissionTone.Neutral -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f)
    }
    val statusFg = when (statusTone) {
        PermissionTone.Success -> MaterialTheme.colorScheme.primary
        PermissionTone.Warning -> Color(0xFFB45309)
        PermissionTone.Neutral -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f)
    }
    val iconBg = MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)
    val iconRing = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.10f)

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(iconBg)
                    .border(1.dp, iconRing, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    StatusChip(
                        text = statusText,
                        bg = statusBg,
                        fg = statusFg
                    )
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.70f)
                )
            }

            if (enabled) {
                Button(
                    onClick = onAction,
                    enabled = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    )
                ) {
                    Text(actionText, fontWeight = FontWeight.SemiBold)
                }
            } else {
                FilledTonalButton(
                    onClick = onAction,
                    enabled = false,
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Text(actionText, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private enum class PermissionTone { Success, Warning, Neutral }

@Composable
private fun HeaderCard(
    allReady: Boolean,
    overlayGranted: Boolean,
    notificationGranted: Boolean,
    projectionGranted: Boolean
) {
    val shape = RoundedCornerShape(22.dp)
    val onBg = MaterialTheme.colorScheme.onBackground
    val surface = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val ring = onBg.copy(alpha = 0.08f)
    val accent = MaterialTheme.colorScheme.primary

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = shape
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(surface)
                .border(1.dp, ring, shape)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(accent.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = accent
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (allReady) "已就绪" else "还差一步",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (allReady) "可以返回首页启动悬浮翻译" else "补齐权限后即可开始翻译",
                        style = MaterialTheme.typography.bodyMedium,
                        color = onBg.copy(alpha = 0.70f)
                    )
                }
                ProgressPills(
                    done = listOf(overlayGranted, notificationGranted, projectionGranted).count { it },
                    total = 3
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String, bg: Color, fg: Color) {
    Box(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun ProgressPills(done: Int, total: Int) {
    val active = MaterialTheme.colorScheme.primary
    val inactive = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.12f)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(total) { idx ->
            val isOn = idx < done
            Box(
                modifier = Modifier
                    .width(if (isOn) 22.dp else 10.dp)
                    .height(10.dp)
                    .clip(CircleShape)
                    .background(if (isOn) active else inactive)
            )
        }
    }
}
