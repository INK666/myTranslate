package com.example.mytransl.ui.home

import androidx.compose.animation.core.animateFloatAsState
import android.Manifest
import android.os.Build
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mytransl.ui.components.*
import com.example.mytransl.data.settings.ApiConfig
import com.example.mytransl.data.settings.SettingsRepository
import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.system.permissions.CapturePermissionStore
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.app.Activity
import android.media.projection.MediaProjectionManager
import com.example.mytransl.ui.theme.*

@Composable
fun MainScreen(
    onOpenPermissions: () -> Unit = {},
    onStart: () -> Unit = {},
    onStop: () -> Unit = {},
    isRunning: Boolean = false
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = SettingsState())
    val scope = rememberCoroutineScope()

    var draft by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { draft = settings }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            CapturePermissionStore.set(result.resultCode, result.data)
            onStart()
        }
    }

    fun normalizeLanguages(state: SettingsState): SettingsState {
        val source = state.sourceLanguage.trim().ifEmpty { "自动检测" }
        val target = state.targetLanguage.trim().ifEmpty { "英语" }
        return state.copy(sourceLanguage = source, targetLanguage = target)
    }

    fun updateDraft(newState: SettingsState) {
        val normalized = normalizeLanguages(newState)
        draft = normalized
        scope.launch { repo.saveSettings(normalized) }
    }

    Scaffold(
        containerColor = Slate50,
        contentWindowInsets = WindowInsets(0, 0, 0, 0)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            // Background decoration (simplified subtle float)
            Box(
                modifier = Modifier
                    .offset(x = (-50).dp, y = (-50).dp)
                    .size(300.dp)
                    .clip(CircleShape)
                    .background(Emerald50.copy(alpha = 0.6f))
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(top = 16.dp, bottom = 120.dp)
            ) {
                item {
                    Text(
                        text = "myTranslate",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate400,
                        letterSpacing = 1.sp
                    )
                }
                item {
                    LanguageCard(
                        source = draft.sourceLanguage,
                        target = draft.targetLanguage,
                        onSwap = {
                            val src = draft.sourceLanguage
                            val tgt = draft.targetLanguage
                            val nextSource = if (tgt == "自动检测") "英语" else tgt
                            val nextTarget = when {
                                src == "自动检测" -> if (nextSource == "中文") "英语" else "中文"
                                src.isBlank() -> "中文"
                                else -> src
                            }
                            updateDraft(draft.copy(sourceLanguage = nextSource, targetLanguage = nextTarget))
                        },
                        onSourceChange = { updateDraft(draft.copy(sourceLanguage = it)) },
                        onTargetChange = { updateDraft(draft.copy(targetLanguage = it)) }
                    )
                }

                item {
                    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        // OCR Engine
                        val ocrOptions = buildList {
                            add(ToggleOption("PaddleOCR", "PaddleOCR", "📝"))
                            // ML Kit 已隐藏，但后台逻辑保留
                            // add(ToggleOption("MLKit", "ML Kit", "⚡"))
                            draft.apiConfigs.forEach { cfg ->
                                if (cfg.name.isNotBlank()) {
                                    if (cfg.isVisualModel) {
                                        add(ToggleOption(cfg.name, cfg.name, "📖"))
                                    } else if (cfg.isOcrModel) {
                                        add(ToggleOption(cfg.name, cfg.name, "👁️"))
                                    }
                                }
                            }
                        }.distinctBy { it.id }

                        val activeOcrId = if (ocrOptions.any { it.id == draft.ocrEngine }) draft.ocrEngine else "PaddleOCR"

                        EngineSection(
                            label = "OCR 模型",
                            badgeText = "RECOGNITION",
                            colorTheme = ToggleTheme.Sky,
                            options = ocrOptions,
                            activeId = activeOcrId,
                            onSelect = { id ->
                                val config = draft.apiConfigs.find { it.name == id }
                                val isVisual = config?.isVisualModel == true
                                
                                val nextDraft = if (isVisual) {
                                    // Visual OCR implies Visual Translate
                                    draft.copy(
                                        ocrEngine = id,
                                        defaultEngine = id
                                    )
                                } else {
                                    // Standard OCR or Pure OCR: set OCR engine, revert Translate if it was Visual
                                    val currentTranslateIsVisual = draft.apiConfigs.find { it.name == draft.defaultEngine }?.isVisualModel == true
                                    val nextTranslate = if (currentTranslateIsVisual) "微软离线" else draft.defaultEngine
                                    draft.copy(ocrEngine = id, defaultEngine = nextTranslate)
                                }
                                updateDraft(nextDraft)
                            }
                        )

                        // Translation Engine
                        val engineOptions = buildList {
                            add(ToggleOption("微软离线", "ML Kit", "🏠"))
                            add(ToggleOption("谷歌翻译（免费）", "谷歌翻译", "🌐"))
                            add(ToggleOption("Bing翻译（免费）", "Bing翻译", "🅱️"))
                            draft.apiConfigs.forEach { cfg ->
                                if (cfg.name.isNotBlank()) {
                                    if (cfg.isVisualModel) {
                                        add(ToggleOption(cfg.name, cfg.name, "📖"))
                                    } else if (!cfg.isOcrModel) {
                                        add(ToggleOption(cfg.name, cfg.name, "🔗"))
                                    }
                                }
                            }
                        }.distinctBy { it.id }

                        // Handle case where selected engine is not in the list (custom APIs removed etc)
                        val activeEngineId = if (engineOptions.any { it.id == draft.defaultEngine }) draft.defaultEngine else {
                            "微软离线"
                        }
                        
                        EngineSection(
                            label = "翻译引擎",
                            badgeText = "AI CORE",
                            colorTheme = ToggleTheme.Orange,
                            options = engineOptions,
                            activeId = activeEngineId,
                            onSelect = { id ->
                                val config = draft.apiConfigs.find { it.name == id }
                                val isVisual = config?.isVisualModel == true

                                val next = if (isVisual) {
                                    draft.copy(defaultEngine = id, ocrEngine = id)
                                } else {
                                   val currentOcrIsVisual = draft.apiConfigs.find { it.name == draft.ocrEngine }?.isVisualModel == true
                                   val nextOcr = if (currentOcrIsVisual) "PaddleOCR" else draft.ocrEngine
                                   draft.copy(defaultEngine = id, ocrEngine = nextOcr)
                                }
                                updateDraft(next)
                            }
                        )

                        // Trigger Mode
                        val currentTrigger = if (draft.translationMode.startsWith("固定") || draft.translationMode.startsWith("自动")) "auto" else "manual"
                        val currentArea = if (draft.translationMode.contains("区域")) "area" else "full"

                        SectionToggle(
                            label = "触发方式",
                            badgeText = "TRIGGER",
                            colorTheme = ToggleTheme.Emerald,
                            options = listOf(
                                ToggleOption("manual", "手动触发", "⚡"),
                                ToggleOption("auto", "自动检测", "✨")
                            ),
                            activeId = currentTrigger,
                            onSelect = { id ->
                                val newMode = toMode(id, currentArea)
                                updateDraft(draft.copy(translationMode = newMode))
                            }
                        )

                        // Region
                        SectionToggle(
                            label = "翻译区域",
                            badgeText = "REGION",
                            colorTheme = ToggleTheme.Sky,
                            options = listOf(
                                ToggleOption("full", "全屏捕捉", "📱"),
                                ToggleOption("area", "局部翻译", "🎯")
                            ),
                            activeId = currentArea,
                            onSelect = { id ->
                                val newMode = toMode(currentTrigger, id)
                                updateDraft(draft.copy(translationMode = newMode))
                            }
                        )



                        // Display Mode
                        SectionToggle(
                            label = "显示方式",
                            badgeText = "LAYOUT",
                            colorTheme = ToggleTheme.Purple,
                            options = listOf(
                                ToggleOption("覆盖层", "覆盖原文", null),
                                ToggleOption("独立窗口", "独立浮窗", null)
                            ),
                            activeId = draft.resultMode,
                            onSelect = { mode ->
                                updateDraft(draft.copy(resultMode = mode))
                            }
                        )
                    }
                }
            }

            // Bottom Start Button
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                val trigger = if (draft.translationMode.startsWith("固定") || draft.translationMode.startsWith("自动")) "auto" else "manual"
                StartButton(
                    isRunning = isRunning,
                    isAuto = trigger == "auto",
                    onClick = {
                        if (isRunning) {
                            onStop()
                        } else {
                            val overlayGranted = android.provider.Settings.canDrawOverlays(context)
                            val notificationGranted = Build.VERSION.SDK_INT < 33 ||
                                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                            val projectionGranted = CapturePermissionStore.data != null

                            if (!overlayGranted || !notificationGranted) {
                                onOpenPermissions()
                            } else if (!projectionGranted) {
                                val mgr = context.getSystemService(MediaProjectionManager::class.java)
                                projectionLauncher.launch(mgr.createScreenCaptureIntent())
                            } else {
                                onStart()
                            }
                        }
                    }
                )
            }
        }
    }
}

private fun toMode(trigger: String, area: String): String {
    return when {
        trigger == "manual" && area == "full" -> "单次全屏"
        trigger == "manual" && area == "area" -> "单次区域"
        trigger == "auto" && area == "full" -> "自动全屏"
        trigger == "auto" && area == "area" -> "自动区域"
        else -> "单次全屏"
    }
}

// --- Components ---

@Composable
fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            // Icon Box
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Emerald500, Emerald600)))
                    .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Emerald500),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "T",
                    color = Color.White,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Black,
                    style = MaterialTheme.typography.displaySmall
                )
            }
            
            Column {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = "My",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = Slate800,
                        lineHeight = 24.sp
                    )
                    Text(
                        text = "T",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        color = Emerald500,
                        lineHeight = 24.sp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = Emerald50,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "v1.1",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Emerald600,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                Text(
                    text = "SMART TRANSLATE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate400,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }
}

@Composable
fun LanguageCard(
    source: String,
    target: String,
    onSwap: () -> Unit,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit
) {
    val sourceOptions = listOf("中文", "英语", "日语", "韩语")
    val targetOptions = listOf("中文", "英语", "日语", "韩语")
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var targetMenuExpanded by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(40.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        border = BorderStroke(1.dp, Slate100),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 24.dp, vertical = 20.dp)
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Source Language
            Column(modifier = Modifier.clickable { sourceMenuExpanded = true }) {
                Text(
                    text = "SOURCE",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = Slate300,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = source,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Slate700
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Slate300,
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(
                    expanded = sourceMenuExpanded,
                    onDismissRequest = { sourceMenuExpanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    sourceOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option, color = Slate800) },
                            onClick = {
                                onSourceChange(option)
                                sourceMenuExpanded = false
                            }
                        )
                    }
                }
            }

            Surface(
                onClick = onSwap,
                shape = RoundedCornerShape(16.dp),
                color = Slate50,
                border = BorderStroke(1.dp, Slate100),
                modifier = Modifier.size(44.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Swap",
                        tint = Emerald600,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Target Language
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.clickable { targetMenuExpanded = true }
            ) {
                Text(
                    text = "TARGET",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Black,
                    color = Slate300,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = target,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Slate700
                    )
                    Icon(
                        imageVector = Icons.Filled.KeyboardArrowDown,
                        contentDescription = null,
                        tint = Slate300,
                        modifier = Modifier.size(16.dp)
                    )
                }
                DropdownMenu(
                    expanded = targetMenuExpanded,
                    onDismissRequest = { targetMenuExpanded = false },
                    modifier = Modifier.background(Color.White)
                ) {
                    targetOptions.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option, color = Slate800) },
                            onClick = {
                                onTargetChange(option)
                                targetMenuExpanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun StartButton(isRunning: Boolean, isAuto: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(if (isRunning) 0.95f else 1f, label = "scale")
    val gradientColors = if (isAuto) {
        listOf(Emerald500, Color(0xFF2DD4BF)) // Emerald to Teal
    } else {
        listOf(Color(0xFF2563EB), Color(0xFF6366F1)) // Blue to Indigo
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(40.dp),
        shadowElevation = 12.dp,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .scale(scale)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(gradientColors)),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isRunning) (if (isAuto) "监听中..." else "停止翻译") else "开启即时翻译",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    color = Color.White
                )
            }
        }
    }
}
