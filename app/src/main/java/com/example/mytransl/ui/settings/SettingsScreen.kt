package com.example.mytransl.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mytransl.R
import com.example.mytransl.data.settings.ApiConfig
import com.example.mytransl.data.settings.SettingsRepository
import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.data.translation.engines.fetchOnlineApiModels
import com.example.mytransl.data.translation.engines.testOnlineApiConnection
import com.example.mytransl.data.ocr.MlKitDownloadManager
import okhttp3.OkHttpClient
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.SpanStyle
import com.example.mytransl.ui.components.*
import com.example.mytransl.ui.theme.*


private fun toMode(trigger: String, area: String): String {
    return when {
        trigger == "manual" && area == "full" -> "单次全屏"
        trigger == "manual" && area == "area" -> "单次区域"
        trigger == "auto" && area == "full" -> "自动全屏"
        trigger == "auto" && area == "area" -> "自动区域"
        else -> "单次全屏"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val repo = remember { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = SettingsState())
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var saving by remember { mutableStateOf(false) }
    var apiToDeleteIndex by remember { mutableStateOf<Int?>(null) }

    var draft by remember { mutableStateOf(settings) }
    LaunchedEffect(settings) { draft = settings }
    var visibleKeyIndices by remember { mutableStateOf(setOf<Int>()) }
    var expandedIndices by remember { mutableStateOf(setOf<Int>()) }
    var testingIndices by remember { mutableStateOf(setOf<Int>()) }
    var testStatusByIndex by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var fetchingModelIndices by remember { mutableStateOf(setOf<Int>()) }
    var modelStatusByIndex by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    val testClient = remember { OkHttpClient() }

    val offlineEngineId = "微软离线"
    val offlineEngineLabel = "ML Kit"
    val visualEngineId = "视觉大模型"

    val mlkitDownloading by MlKitDownloadManager.downloading.collectAsState()
    val mlkitDownloadingText by MlKitDownloadManager.progressText.collectAsState()
    val mlkitError by MlKitDownloadManager.error.collectAsState()
    val mlkitDownloadedByLabel by MlKitDownloadManager.downloadedModels.collectAsState()
    val mlkitRequiredModels = MlKitDownloadManager.requiredModels

    var showSpeedHelp by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        MlKitDownloadManager.checkStatus()
    }

    fun normalizeDefaultEngine(state: SettingsState): SettingsState {
        val available = buildList {
            add(offlineEngineId)
            add("谷歌翻译（免费）")
            add("Bing翻译（免费）")
            addAll(state.apiConfigs.map { it.name }.filter { it.isNotBlank() })
        }.distinct()

        val normalizedDefault = state.defaultEngine.takeIf { it in available } ?: offlineEngineId
        val next = state.copy(defaultEngine = normalizedDefault)
        return if (next == state) state else next
    }

    Scaffold(
        containerColor = Slate50,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    onClick = onBack,
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White,
                    shadowElevation = 2.dp,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Slate700,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "设置",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = Slate800,
                    modifier = Modifier.weight(1f)
                )
                // 开源项目文本和 GitHub 图标
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "开源项目",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Normal,
                        color = Slate400
                    )
                    val context = LocalContext.current
                    IconButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/INK666/myTranslate"))
                            context.startActivity(intent)
                        },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_github),
                            contentDescription = "GitHub",
                            tint = Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = "v1.4",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate400
                )
            }
        },
        floatingActionButton = {
            val gradientColors = listOf(Color(0xFF2563EB), Color(0xFF6366F1))
            Surface(
                onClick = {
                    if (saving) return@Surface
                    saving = true
                    scope.launch {
                        val start = System.currentTimeMillis()
                        val result = runCatching { repo.saveSettings(normalizeDefaultEngine(draft)) }
                        val duration = System.currentTimeMillis() - start
                        val minWait = 50L
                        val wait = (minWait - duration).coerceAtLeast(0L)
                        if (wait > 0) kotlinx.coroutines.delay(wait)
                        saving = false
                        result.fold(
                            onSuccess = {
                                snackbarHostState.showSnackbar("保存成功")
                            },
                            onFailure = {
                                snackbarHostState.showSnackbar("保存失败：${it.message ?: "未知错误"}")
                            }
                        )
                    }
                },
                enabled = !saving,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.height(56.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(Brush.horizontalGradient(gradientColors))
                        .padding(horizontal = 32.dp, vertical = 12.dp)
                        .wrapContentWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (saving) {
                            CircularProgressIndicator(
                                strokeWidth = 3.dp,
                                color = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("保存中…", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        } else {
                            Text("保存", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .padding(innerPadding)
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
            contentPadding = PaddingValues(top = 12.dp, bottom = 100.dp)
        ) {
            // API Management Section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SectionHeader(
                            title = "API 管理",
                            icon = Icons.Filled.Info,
                            themeColor = Sky600,
                            themeBg = Sky100
                        )

                        Button(
                            onClick = {
                                val nextIndex = (draft.apiConfigs.size + 1).coerceAtLeast(1)
                                val baseName = "自定义API$nextIndex"
                                val existing = draft.apiConfigs.map { it.name }.toSet()
                                val name = generateSequence(0) { it + 1 }
                                    .map { if (it == 0) baseName else "$baseName-$it" }
                                    .first { it !in existing }

                                val newApi = ApiConfig(
                                    name = name,
                                    baseUrl = "",
                                    apiKey = "",
                                    model = ""
                                )
                                val next = draft.copy(apiConfigs = listOf(newApi) + draft.apiConfigs)
                                visibleKeyIndices = visibleKeyIndices.map { it + 1 }.toSet()
                                expandedIndices = expandedIndices.map { it + 1 }.toSet() + 0
                                testingIndices = testingIndices.map { it + 1 }.toSet()
                                testStatusByIndex = testStatusByIndex.mapKeys { (k, _) -> k + 1 }
                                fetchingModelIndices = fetchingModelIndices.map { it + 1 }.toSet()
                                modelStatusByIndex = modelStatusByIndex.mapKeys { (k, _) -> k + 1 }
                                draft = normalizeDefaultEngine(next)
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Sky500),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("新增 API", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (draft.apiConfigs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("暂无自定义 API", color = Slate400)
                        }
                    }
                }
            }

            itemsIndexed(draft.apiConfigs, key = { idx, _ -> idx }) { index, cfg ->
                val isExpanded = index in expandedIndices
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = cfg.name.ifBlank { "未命名配置" },
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate700,
                                modifier = Modifier.weight(1f)
                            )

                            Text(
                                text = if (isExpanded) "收起" else "展开",
                                color = Emerald500,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .clickable {
                                        expandedIndices = if (isExpanded) expandedIndices - index else expandedIndices + index
                                    }
                                    .padding(8.dp)
                            )

                            IconButton(
                                onClick = {
                                    apiToDeleteIndex = index
                                }
                            ) {
                                Text("删除", color = Color.Red, fontSize = 12.sp)
                            }
                        }

                        AnimatedVisibility(visible = isExpanded) {
                            Column(
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.padding(top = 16.dp)
                            ) {
                                HorizontalDivider(color = Slate100)

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "引擎类型",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate400
                                    )
                                    EngineDropdown(
                                        value = if (cfg.type == "microsoft") "微软翻译" else "OpenAI 兼容",
                                        options = listOf("OpenAI 兼容"),
                                        onValueChange = { selected ->
                                            val newType = if (selected == "微软翻译") "microsoft" else "openai"
                                            if (newType != cfg.type) {
                                                val updated = draft.apiConfigs.toMutableList()
                                                updated[index] = cfg.copy(type = newType)
                                                draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                            }
                                        }
                                    )
                                }

                                StyledTextField(
                                    value = cfg.name,
                                    onValueChange = { v ->
                                        val oldName = cfg.name
                                        val newName = v.trim()
                                        
                                        // 检查是否与其他配置重名
                                        val isDuplicate = draft.apiConfigs.any { it.name == newName && it.name != oldName }
                                        if (isDuplicate) {
                                            // 如果重名，不允许修改
                                            return@StyledTextField
                                        }
                                        
                                        val updated = draft.apiConfigs.toMutableList()
                                        updated[index] = cfg.copy(name = newName)
                                        val renamed = draft.copy(
                                            apiConfigs = updated,
                                            defaultEngine = if (draft.defaultEngine == oldName) newName else draft.defaultEngine
                                        )
                                        draft = normalizeDefaultEngine(renamed)
                                    },
                                    label = "配置名称",
                                    icon = Icons.Filled.Settings
                                )

                                if (cfg.type != "microsoft") {
                                    StyledTextField(
                                        value = cfg.baseUrl,
                                        onValueChange = { v ->
                                            val updated = draft.apiConfigs.toMutableList()
                                            updated[index] = cfg.copy(baseUrl = v)
                                            draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                        },
                                        label = "API URL",
                                        placeholder = "https://api.siliconflow.cn/v1/",
                                        icon = Icons.Filled.Search,
                                        keyboardType = KeyboardType.Uri
                                    )
                                }

                                StyledTextField(
                                    value = cfg.apiKey,
                                    onValueChange = { v ->
                                        val normalized = v.replace("\r", "").replace("\n", "")
                                        val updated = draft.apiConfigs.toMutableList()
                                        updated[index] = cfg.copy(apiKey = normalized)
                                        draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                    },
                                    label = if (cfg.type == "microsoft") "订阅密钥 (Key)" else "API Key",
                                    icon = Icons.Filled.Lock,
                                    isPassword = true,
                                    isVisible = index in visibleKeyIndices,
                                    onToggleVisibility = {
                                        visibleKeyIndices = if (index in visibleKeyIndices) {
                                            visibleKeyIndices - index
                                        } else {
                                            visibleKeyIndices + index
                                        }
                                    }
                                )

                                if (cfg.type == "microsoft") {
                                    StyledTextField(
                                        value = cfg.model,
                                        onValueChange = { v ->
                                            val updated = draft.apiConfigs.toMutableList()
                                            updated[index] = cfg.copy(model = v)
                                            draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                        },
                                        label = "区域 (Region)",
                                        placeholder = "eastasia",
                                        icon = Icons.Filled.Info
                                    )
                                } else {
                                    val options = cfg.modelOptions
                                    if (options.isNotEmpty()) {
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text(
                                                text = "模型名称",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate400
                                            )
                                            val dropdownOptions = if (cfg.model.isNotBlank() && cfg.model !in options) {
                                                listOf(cfg.model) + options
                                            } else {
                                                options
                                            }
                                            EngineDropdown(
                                                value = cfg.model.ifBlank { dropdownOptions.firstOrNull().orEmpty() },
                                                options = dropdownOptions,
                                                onValueChange = { selected ->
                                                    val updated = draft.apiConfigs.toMutableList()
                                                    updated[index] = cfg.copy(model = selected)
                                                    draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                                }
                                            )
                                        }
                                    } else {
                                        StyledTextField(
                                            value = cfg.model,
                                            onValueChange = { v ->
                                                val updated = draft.apiConfigs.toMutableList()
                                                updated[index] = cfg.copy(model = v)
                                                draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                            },
                                            label = "模型名称",
                                            placeholder = "deepseek-chat",
                                            icon = Icons.Filled.Info
                                        )
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val statusText = modelStatusByIndex[index]
                                            ?: if (options.isNotEmpty()) "已加载 ${options.size} 个模型" else ""
                                        Text(
                                            text = statusText,
                                            color = if (statusText.startsWith("失败")) Color.Red else Slate500,
                                            fontSize = 12.sp,
                                            modifier = Modifier.weight(1f)
                                        )
                                        Button(
                                            onClick = {
                                                scope.launch {
                                                    fetchingModelIndices = fetchingModelIndices + index
                                                    modelStatusByIndex = modelStatusByIndex + (index to "拉取中…")
                                                    val result = runCatching {
                                                        fetchOnlineApiModels(
                                                            config = draft.apiConfigs[index],
                                                            client = testClient
                                                        )
                                                    }
                                                    result.onSuccess { models ->
                                                        val updated = draft.apiConfigs.toMutableList()
                                                        val old = updated[index]
                                                        val nextModel = if (old.model.isBlank()) {
                                                            models.firstOrNull().orEmpty()
                                                        } else old.model
                                                        updated[index] = old.copy(
                                                            model = nextModel,
                                                            modelOptions = models
                                                        )
                                                        draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                                        modelStatusByIndex = modelStatusByIndex + (index to "已加载 ${models.size} 个模型")
                                                    }.onFailure { e ->
                                                        modelStatusByIndex = modelStatusByIndex + (index to "失败：${e.message ?: "未知错误"}")
                                                    }
                                                    fetchingModelIndices = fetchingModelIndices - index
                                                }
                                            },
                                            enabled = index !in fetchingModelIndices,
                                            colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                                            shape = RoundedCornerShape(8.dp),
                                            modifier = Modifier.height(36.dp),
                                            contentPadding = PaddingValues(horizontal = 16.dp)
                                        ) {
                                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(if (index in fetchingModelIndices) "..." else "拉取模型")
                                        }
                                    }
                                }

                                if (cfg.type != "microsoft") {
                                    StyledTextField(
                                        value = cfg.prompt,
                                        onValueChange = { v ->
                                            val updated = draft.apiConfigs.toMutableList()
                                            updated[index] = cfg.copy(prompt = v)
                                            draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                        },
                                        label = "自定义 Prompt",
                                        placeholder = "例如：更口语/更正式/保留专有名词/翻译成更简洁的中文",
                                        icon = Icons.Filled.Info,
                                        singleLine = false,
                                        maxLines = 6
                                    )
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val statusText = testStatusByIndex[index].orEmpty()
                                    Text(
                                        text = statusText,
                                        color = if (statusText.startsWith("失败")) Color.Red else Emerald500,
                                        fontSize = 12.sp,
                                        modifier = Modifier.weight(1f)
                                    )

                                    if (cfg.type != "microsoft") {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(end = 8.dp)
                                        ) {
                                            val visualTip =
                                                "仅多模态模型可开启，使用此模型时会跳过本地ocr以实现更精密的图像翻译。"
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        snackbarHostState.showSnackbar(visualTip)
                                                    }
                                                },
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .padding(end = 2.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Filled.Info,
                                                    contentDescription = "视觉模型提示",
                                                    tint = Slate400,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                            Text(
                                                text = "多模态模型",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Slate400
                                            )
                                            Switch(
                                                checked = cfg.isVisualModel,
                                                onCheckedChange = { checked ->
                                                    val updated = draft.apiConfigs.toMutableList()
                                                    updated[index] = cfg.copy(isVisualModel = checked)
                                                    draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                                                },
                                                colors = SwitchDefaults.colors(
                                                    checkedThumbColor = Color.White,
                                                    checkedTrackColor = Emerald500,
                                                    uncheckedThumbColor = Slate400,
                                                    uncheckedTrackColor = Slate200
                                                ),
                                                modifier = Modifier.scale(0.8f)
                                            )
                                        }
                                    }

                                    Button(
                                        onClick = {
                                            scope.launch {
                                                testingIndices = testingIndices + index
                                                testStatusByIndex = testStatusByIndex + (index to "测试中…")
                                                val result = runCatching {
                                                    testOnlineApiConnection(
                                                        config = draft.apiConfigs[index],
                                                        client = testClient
                                                    )
                                                }
                                                testStatusByIndex = testStatusByIndex + (index to result.fold(
                                                    onSuccess = { it },
                                                    onFailure = { "失败：${it.message ?: "未知错误"}" }
                                                ))
                                                testingIndices = testingIndices - index
                                            }
                                        },
                                        enabled = index !in testingIndices,
                                        colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 16.dp)
                                    ) {
                                        Text(if (index in testingIndices) "..." else "测试连接")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Translation Engine Section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeader(
                        title = "本地OCR配置",
                        icon = Icons.Filled.Home, // Using Home/House icon concept
                        themeColor = Orange600,
                        themeBg = Orange100
                    )
                    
                    Card(
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {
                            val activeOcrId = if (draft.ocrEngine.trim().equals("MLKit", ignoreCase = true)) "MLKit" else "PaddleOCR"
                            SectionToggle(
                                label = "OCR 模型",
                                badgeText = "OCR",
                                colorTheme = ToggleTheme.Sky,
                                options = listOf(
                                    ToggleOption("PaddleOCR", "PaddleOCR", "🐼"),
                                    ToggleOption("MLKit", "ML Kit", "🔤")
                                ),
                                activeId = activeOcrId,
                                onSelect = { id -> draft = draft.copy(ocrEngine = id) }
                            )

                            SectionToggle(
                                label = "文字顺序",
                                badgeText = "ORDER",
                                colorTheme = ToggleTheme.Sky,
                                options = listOf(
                                    ToggleOption("standard", "标准 (Z形)", "➡️"),
                                    ToggleOption("manga", "漫画 (纵向)", "⬇️")
                                ),
                                activeId = if (draft.isMangaMode) "manga" else "standard",
                                onSelect = { id ->
                                    draft = draft.copy(isMangaMode = (id == "manga"))
                                }
                            )

                            // Speed Mode
                            SectionToggle(
                                label = "自动翻译性能",
                                badgeText = "SPEED",
                                colorTheme = ToggleTheme.Emerald,
                                options = listOf(
                                    ToggleOption("正常", "正常看书", "🐢"),
                                    ToggleOption("字幕", "积极追赶", "🐇")
                                ),
                                activeId = draft.autoSpeedMode,
                                onSelect = { draft = draft.copy(autoSpeedMode = it) },
                                onInfoClick = { showSpeedHelp = true }
                            )
                        }
                    }

                    Card(
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "ML Kit",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Slate700
                                    )
                                    val ok = mlkitRequiredModels.all { (label, _) -> mlkitDownloadedByLabel[label] == true }
                                    val downloadedCount = mlkitDownloadedByLabel.values.count { it }
                                    val sub = if (ok) "已就绪 · 中英日韩法俄德离线互译" else "未就绪 · 已下载 $downloadedCount/${mlkitRequiredModels.size} · 首次需联网下载模型"
                                    Text(
                                        text = sub,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate400
                                    )
                                }

                                Button(
                                    onClick = {
                                        MlKitDownloadManager.startDownload()
                                    },
                                    enabled = !mlkitDownloading,
                                    colors = ButtonDefaults.buttonColors(containerColor = Slate800),
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    if (mlkitDownloading) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            color = Color.White,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(10.dp))
                                    }
                                    Text(
                                        text = if (mlkitRequiredModels.all { (label, _) -> mlkitDownloadedByLabel[label] == true }) "检查" else "下载",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            if (mlkitDownloading || mlkitDownloadingText != null) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    LinearProgressIndicator(modifier = Modifier.weight(1f))
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Text(
                                        text = mlkitDownloadingText ?: "下载中…",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate500
                                    )
                                }
                            }

                            val anyDownloaded = mlkitDownloadedByLabel.values.any { it }
                            if (anyDownloaded) {
                                OutlinedButton(
                                    onClick = {
                                        MlKitDownloadManager.deleteModels()
                                    },
                                    enabled = !mlkitDownloading,
                                    shape = RoundedCornerShape(14.dp),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                                ) {
                                    Text(
                                        text = "删除模型",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate700
                                    )
                                }
                            }

                            if (mlkitError != null) {
                                Text(
                                    text = "下载失败：$mlkitError（若中国大陆网络无法访问 Google 下载源，可能失败）",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                }
            }

            // Performance and Cache Section
            item {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    SectionHeader(
                        title = "性能与缓存",
                        icon = Icons.Filled.Refresh,
                        themeColor = Emerald500,
                        themeBg = Emerald50
                    )
                    
                    Card(
                        shape = RoundedCornerShape(32.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp)
                        ) {


                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "启用缓存",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate700,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = draft.cacheEnabled,
                                    onCheckedChange = { draft = draft.copy(cacheEnabled = it) },
                                    colors = SwitchDefaults.colors(
                                        checkedThumbColor = Color.White,
                                        checkedTrackColor = Emerald500,
                                        uncheckedThumbColor = Slate400,
                                        uncheckedTrackColor = Slate200
                                    )
                                )
                            }

                            if (draft.cacheEnabled) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "缓存大小（条目数）",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate400
                                    )
                                    OutlinedTextField(
                                        value = draft.cacheSize.toString(),
                                        onValueChange = { v ->
                                            val parsed = v.toIntOrNull()
                                            if (parsed != null) {
                                                draft = draft.copy(cacheSize = parsed.coerceIn(64, 4096))
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                        singleLine = true,
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Number,
                                            imeAction = ImeAction.Done
                                        ),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Emerald500,
                                            unfocusedBorderColor = Slate200
                                        )
                                    )
                                    Text(
                                        text = "范围：64 - 4096",
                                        fontSize = 10.sp,
                                        color = Slate400
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (apiToDeleteIndex != null) {
        val index = apiToDeleteIndex!!
        val configName = draft.apiConfigs.getOrNull(index)?.name ?: "此配置"
        
        AlertDialog(
            onDismissRequest = { apiToDeleteIndex = null },
            title = { Text("确认删除？", fontWeight = FontWeight.Bold) },
            text = { Text("确定要删除 API 配置“$configName”吗？此操作无法撤销。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        val updated = draft.apiConfigs.toMutableList()
                        if (index in updated.indices) {
                            updated.removeAt(index)
                            visibleKeyIndices = visibleKeyIndices
                                .filter { it != index }
                                .map { if (it > index) it - 1 else it }
                                .toSet()
                            expandedIndices = expandedIndices
                                .filter { it != index }
                                .map { if (it > index) it - 1 else it }
                                .toSet()
                            testingIndices = testingIndices
                                .filter { it != index }
                                .map { if (it > index) it - 1 else it }
                                .toSet()
                            testStatusByIndex = buildMap {
                                for ((k, v) in testStatusByIndex) {
                                    if (k == index) continue
                                    put(if (k > index) k - 1 else k, v)
                                }
                            }
                            fetchingModelIndices = fetchingModelIndices
                                .filter { it != index }
                                .map { if (it > index) it - 1 else it }
                                .toSet()
                            modelStatusByIndex = buildMap {
                                for ((k, v) in modelStatusByIndex) {
                                    if (k == index) continue
                                    put(if (k > index) k - 1 else k, v)
                                }
                            }
                            draft = normalizeDefaultEngine(draft.copy(apiConfigs = updated))
                        }
                        apiToDeleteIndex = null
                    }
                ) {
                    Text("删除", color = Color.Red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { apiToDeleteIndex = null }) {
                    Text("取消", color = Slate500)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(24.dp)
        )
    }

    if (showSpeedHelp) {
        AlertDialog(
            onDismissRequest = { showSpeedHelp = false },
            title = { Text("翻译性能说明", fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("🐢 正常模式：1.2s 左右延迟。开启防抖和背景校验，零闪烁。适合看小说、网页。")
                    Text(
                        text = buildAnnotatedString {
                            append("🐇 字幕模式：0.2s 左右极速响应。关闭大部分防抖，建议搭配“")
                            withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("局部翻译")
                            }
                            append("”。适合看视频、直播。")
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedHelp = false }) {
                    Text("知道了", color = Emerald600)
                }
            },
            containerColor = Color.White,
            shape = RoundedCornerShape(28.dp)
        )
    }
}

// --- Components ---

@Composable
fun SectionHeader(title: String, icon: ImageVector, themeColor: Color, themeBg: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(themeBg),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = themeColor,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.ExtraBold,
            color = Slate800
        )
    }
}

@Composable
fun EngineDropdown(
    value: String,
    options: List<String>,
    onValueChange: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Slate100)
            .clickable { expanded = true }
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = Slate800
            )
            Icon(
                imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Slate500
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color.White)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option, color = Slate800) },
                    onClick = {
                        onValueChange(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
fun StyledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String = "",
    icon: ImageVector,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    isVisible: Boolean = true,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    onToggleVisibility: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = Slate400
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(placeholder, color = Slate300) },
            leadingIcon = {
                Icon(imageVector = icon, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp))
            },
            trailingIcon = if (isPassword) {
                {
                    IconButton(onClick = onToggleVisibility) {
                        Text(if (isVisible) "隐藏" else "显示", fontSize = 12.sp, color = Slate400)
                    }
                }
            } else null,
            visualTransformation = if (isPassword && !isVisible) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = keyboardType,
                imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
            ),
            singleLine = singleLine,
            maxLines = if (singleLine) 1 else maxLines.coerceAtLeast(2),
            modifier = Modifier.fillMaxWidth()
        )
    }
}
