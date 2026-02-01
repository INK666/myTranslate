package com.example.mytransl.ui.translate

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.launch
import androidx.compose.ui.graphics.asImageBitmap
import com.example.mytransl.data.ocr.MlKitOcrEngine
import com.example.mytransl.data.ocr.PaddleOcrEngine
import com.example.mytransl.data.settings.SettingsRepository
import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.data.translation.engines.GoogleFreeEngine
import com.example.mytransl.data.translation.engines.BingFreeEngine
import com.example.mytransl.data.translation.engines.MicrosoftTranslationEngine
import com.example.mytransl.data.translation.engines.OfflineDictionaryEngine
import com.example.mytransl.data.translation.engines.OnlineApiEngine
import com.example.mytransl.domain.ocr.PreferredLanguageAwareOcrEngine
import com.example.mytransl.domain.ocr.TextBlock
import com.example.mytransl.domain.translation.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.mytransl.data.codec.BeastCodec
import java.io.File

// Modern Color Palette
val PrimaryColor = Color(0xFF10B981) // Emerald 500
val PrimaryContainer = Color(0xFFD1FAE5) // Emerald 100
val OnPrimaryContainer = Color(0xFF064E3B) // Emerald 900
val SurfaceColor = Color(0xFFFFFFFF)
val BackgroundColor = Color(0xFFF8FAFC) // Slate 50
val TextPrimary = Color(0xFF0F172A) // Slate 900
val TextSecondary = Color(0xFF64748B) // Slate 500
val TextTertiary = Color(0xFF94A3B8) // Slate 400
val BorderColor = Color(0xFFE2E8F0) // Slate 200
val ErrorColor = Color(0xFFEF4444) // Red 500
val Slate100 = Color(0xFFF1F5F9)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranslateScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    
    val repo = remember { SettingsRepository(context) }
    val settings by repo.settings.collectAsState(initial = SettingsState())
    
    var inputText by rememberSaveable { mutableStateOf("") }
    var outputText by rememberSaveable { mutableStateOf("") }
    var isTranslating by remember { mutableStateOf(false) }
    
    // Modes
    val modes = listOf("文本翻译", "兽音加解密", "图像识别")
    var currentMode by rememberSaveable { mutableStateOf("文本翻译") }

    // Image Recognition State
    var selectedImageBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var selectedImagePath by rememberSaveable { mutableStateOf<String?>(null) }

    fun updateSelectedImagePath(newPath: String?) {
        val old = selectedImagePath
        if (old != null && old != newPath) {
            if (old.startsWith(context.cacheDir.absolutePath)) {
                runCatching { File(old).delete() }
            }
        }
        selectedImagePath = newPath
        if (newPath == null) {
            selectedImageBitmap = null
        }
    }

    LaunchedEffect(selectedImagePath) {
        val path = selectedImagePath
        if (path.isNullOrBlank()) {
            selectedImageBitmap = null
            return@LaunchedEffect
        }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(path) }.getOrNull()
        }
        if (selectedImagePath == path) {
            selectedImageBitmap = bmp
        }
    }
    
    // Image Pickers
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            runCatching {
                val file = copyImageToCache(context, it)
                if (file != null) {
                    updateSelectedImagePath(file.absolutePath)
                } else {
                    Toast.makeText(context, "加载图片失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    var cameraImageUri by remember { mutableStateOf<Uri?>(null) }
    var cameraImageFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val uri = cameraImageUri
        val file = cameraImageFile
        if (success && uri != null && file != null) {
            updateSelectedImagePath(file.absolutePath)
        } else {
            runCatching { file?.delete() }
        }
        cameraImageUri = null
        cameraImageFile = null
    }

    // Languages
    val languages = listOf("自动检测", "中文", "英语", "日语", "韩语", "法语", "德语", "俄语")
    var sourceLang by rememberSaveable { mutableStateOf("自动检测") }
    var targetLang by rememberSaveable { mutableStateOf("中文") }
    
    // Beast Mode State
    var beastAction by rememberSaveable { mutableStateOf("加密") }
    
    // Engine selection
    var selectedEngineId by rememberSaveable { mutableStateOf<String?>(null) }
    
    // Initialize selected engine from settings once
    LaunchedEffect(settings.defaultEngine) {
        if (selectedEngineId == null) {
            selectedEngineId = settings.defaultEngine
        }
    }

    LaunchedEffect(currentMode) {
        if (currentMode == "图像识别" && sourceLang == "自动检测") {
            sourceLang = "日语"
        }
    }
    
    // Swap languages
    fun swapLanguages() {
        if (sourceLang == "自动检测") {
            sourceLang = targetLang
            targetLang = "中文"
        } else {
            val temp = sourceLang
            sourceLang = targetLang
            targetLang = temp
        }
    }

    // Build engine on the fly
    fun buildEngine(name: String): TranslationEngine? {
        if (name == "微软离线") return OfflineDictionaryEngine(context)
        if (name == "谷歌翻译（免费）") return GoogleFreeEngine()
        if (name == "Bing翻译（免费）") return BingFreeEngine()
        val config = settings.apiConfigs.find { it.name == name } ?: return null
        return if (config.type == "microsoft") {
            MicrosoftTranslationEngine(config)
        } else {
            OnlineApiEngine(config)
        }
    }

    fun doTranslate() {
        if (currentMode != "图像识别" && inputText.isBlank()) return
        if (currentMode == "图像识别" && selectedImageBitmap == null) {
            Toast.makeText(context, "请先选择或拍摄图片", Toast.LENGTH_SHORT).show()
            return
        }
        
        isTranslating = true
        keyboardController?.hide()
        
        scope.launch {
            val result = runCatching {
                if (currentMode == "兽音加解密") {
                    if (beastAction == "加密") {
                        BeastCodec.encode(inputText, settings.beastChars)
                    } else {
                        // Decode attempts auto-detection inside BeastCodec, falling back to settings.beastChars
                        BeastCodec.decode(inputText, settings.beastChars)
                    }
                } else if (currentMode == "图像识别") {
                     val bitmap = selectedImageBitmap!!
                     val engineId = selectedEngineId ?: settings.defaultEngine
                     val config = settings.apiConfigs.find { it.name == engineId }
                     
                     if (config?.isVisualModel == true) {
                         val engine = buildEngine(engineId) as? OnlineApiEngine
                            ?: throw IllegalStateException("引擎初始化失败")
                         engine.translateImage(
                             image = bitmap,
                             sourceLanguage = if (sourceLang == "自动检测") null else sourceLang,
                             targetLanguage = targetLang,
                             settings = settings
                         )
                     } else {
                         val ocr = buildOcrEngine(settings.ocrEngine)
                         ocr.preferredLanguage = sourceLang.takeIf { it != "自动检测" }
                         val blocks = ocr.recognize(bitmap)
                         val orderedBlocks = if (settings.isMangaMode) sortBlocksForManga(blocks) else blocks
                         val text = orderedBlocks.joinToString("\n") { it.text }.trim()
                         if (text.isBlank()) throw IllegalStateException("未识别到文字")
                         
                         val engine = buildEngine(engineId) ?: throw IllegalStateException("引擎初始化失败")
                         val src = if (sourceLang == "自动检测") null else sourceLang
                         engine.translate(
                             text = text,
                             sourceLanguage = src,
                             targetLanguage = targetLang,
                             settings = settings
                         )
                     }
                } else {
                    val engineId = selectedEngineId ?: settings.defaultEngine
                    val engine = buildEngine(engineId) ?: throw IllegalStateException("引擎初始化失败")
                    // Use translateStrict-like logic or just direct translate
                    // We handle "Auto" source here
                    val src = if (sourceLang == "自动检测") null else sourceLang
                    engine.translate(
                        text = inputText,
                        sourceLanguage = src,
                        targetLanguage = targetLang,
                        settings = settings
                    )
                }
            }.getOrElse {
                it.message ?: "处理失败"
            }
            outputText = result
            isTranslating = false
        }
    }

    Scaffold(
        containerColor = BackgroundColor,
        topBar = {
            ModernTopBar(
                currentMode = currentMode,
                modes = modes,
                onModeSelected = { 
                    currentMode = it
                    if (currentMode == "图像识别" && sourceLang == "自动检测") {
                        sourceLang = "日语"
                    }
                    outputText = "" // Clear output on mode switch
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 20.dp), // Top padding removed to reduce spacing
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Controls Section (Language/Engine or Beast Mode)
            AnimatedVisibility(
                visible = true,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                if (currentMode == "兽音加解密") {
                    BeastModeControls(
                        beastAction = beastAction,
                        onActionChange = { beastAction = it },
                        settings = settings,
                        repo = repo,
                        scope = scope
                    )
                } else {
                    val sourceOptions = if (currentMode == "图像识别") {
                        languages.filter { it != "自动检测" }
                    } else {
                        languages
                    }
                    TranslationControls(
                        sourceLang = sourceLang,
                        targetLang = targetLang,
                        languages = sourceOptions,
                        selectedEngineId = selectedEngineId,
                        settings = settings,
                        onSourceChange = { sourceLang = it },
                        onTargetChange = { targetLang = it },
                        onSwap = { swapLanguages() },
                        onEngineChange = { selectedEngineId = it }
                    )
                }
            }
            
            // Input Area
            if (currentMode == "图像识别") {
                ImageRecognitionControls(
                    bitmap = selectedImageBitmap,
                    onPickGallery = { galleryLauncher.launch("image/*") },
                    onPickCamera = { 
                        val file = runCatching { createTempImageFile(context) }.getOrNull()
                        if (file == null) {
                            Toast.makeText(context, "创建拍照文件失败", Toast.LENGTH_SHORT).show()
                            return@ImageRecognitionControls
                        }
                        val uri = runCatching {
                            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                        }.getOrElse {
                            runCatching { file.delete() }
                            Toast.makeText(context, "无法打开相机", Toast.LENGTH_SHORT).show()
                            return@ImageRecognitionControls
                        }
                        cameraImageFile = file
                        cameraImageUri = uri
                        cameraLauncher.launch(uri)
                    },
                    onTranslate = { doTranslate() },
                    isTranslating = isTranslating
                )
            } else {
                ModernInputCard(
                    inputText = inputText,
                    onInputChange = { inputText = it },
                    onClear = { inputText = "" },
                    onPaste = {
                        clipboardManager.getText()?.let {
                            inputText = it.text
                        }
                    },
                    onTranslate = { doTranslate() },
                    isTranslating = isTranslating,
                    actionLabel = if (currentMode == "文本翻译") "翻译" else if (beastAction == "加密") "加密" else "解密"
                )
            }
            
            // Output Area
            AnimatedVisibility(visible = outputText.isNotEmpty()) {
                ModernOutputCard(
                    outputText = outputText,
                    onOutputChange = { outputText = it },
                    targetLang = if (currentMode == "兽音加解密") (if (beastAction == "加密") "兽音密文" else "解密结果") else targetLang,
                    onCopy = {
                        clipboardManager.setText(AnnotatedString(outputText))
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

// 简易拍照临时文件创建
private fun createTempImageFile(context: Context): File {
    val dir = File(context.cacheDir, "camera").apply { if (!exists()) mkdirs() }
    return File(dir, "capture_${System.currentTimeMillis()}.jpg")
}

private fun copyImageToCache(context: Context, uri: Uri): File? {
    val dir = File(context.cacheDir, "gallery").apply { if (!exists()) mkdirs() }
    val file = File(dir, "pick_${System.currentTimeMillis()}.jpg")
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            file.outputStream().use { output ->
                input.copyTo(output)
            }
        }
        if (file.exists() && file.length() > 0L) file else null
    }.getOrNull()
}

private fun buildOcrEngine(ocrId: String): PreferredLanguageAwareOcrEngine {
    val usePaddle = ocrId.isBlank() || ocrId.equals("PaddleOCR", ignoreCase = true)
    return if (usePaddle) PaddleOcrEngine() else MlKitOcrEngine()
}

private fun sortBlocksForManga(blocks: List<TextBlock>): List<TextBlock> {
    if (blocks.isEmpty()) return blocks
    val (verticalBlocks, horizontalBlocks) = blocks.partition {
        val h = it.bounds.height()
        val w = it.bounds.width()
        h >= w * 0.4f || it.text.length <= 1
    }
    val sortedByXDesc = verticalBlocks.sortedByDescending { it.bounds.centerX() }
    val lanes = mutableListOf<MutableList<TextBlock>>()
    for (block in sortedByXDesc) {
        var bestLane: MutableList<TextBlock>? = null
        var maxOverlapRatio = 0f
        for (lane in lanes) {
            val laneLeft = lane.minOf { it.bounds.left }
            val laneRight = lane.maxOf { it.bounds.right }
            val laneWidth = laneRight - laneLeft
            val blockLeft = block.bounds.left
            val blockRight = block.bounds.right
            val overlapLeft = maxOf(laneLeft, blockLeft)
            val overlapRight = minOf(laneRight, blockRight)
            val overlapWidth = overlapRight - overlapLeft
            val overlapRatio = if (overlapWidth <= 0f || laneWidth <= 0f) 0f else overlapWidth / laneWidth
            if (overlapRatio > maxOverlapRatio) {
                maxOverlapRatio = overlapRatio
                bestLane = lane
            }
        }
        if (bestLane != null && maxOverlapRatio > 0.2f) {
            bestLane.add(block)
        } else {
            lanes.add(mutableListOf(block))
        }
    }
    val sortedLanes = lanes.sortedByDescending { lane ->
        lane.map { it.bounds.centerX() }.average()
    }
    val result = ArrayList<TextBlock>(blocks.size)
    for (lane in sortedLanes) {
        lane.sortBy { it.bounds.top }
        result.addAll(lane)
    }
    if (horizontalBlocks.isNotEmpty()) {
        result.addAll(horizontalBlocks.sortedWith(compareBy<TextBlock> { it.bounds.top }.thenBy { it.bounds.left }))
    }
    return result
}

@Composable
fun ImageRecognitionControls(
    bitmap: Bitmap?,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
    onTranslate: () -> Unit,
    isTranslating: Boolean
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, BorderColor, RoundedCornerShape(20.dp))
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(BackgroundColor)
                    .border(1.dp, BorderColor, RoundedCornerShape(12.dp))
                    .clickable { onPickGallery() },
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    androidx.compose.foundation.Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Selected Image",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = androidx.compose.ui.layout.ContentScale.Fit
                    )
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Image, 
                            contentDescription = null,
                            tint = TextTertiary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("点击选择图片", color = TextTertiary)
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onPickCamera,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PhotoCamera, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("拍照")
                }
                
                Button(
                    onClick = onTranslate,
                    enabled = bitmap != null && !isTranslating,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryColor)
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("开始识别")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    currentMode: String,
    modes: List<String>,
    onModeSelected: (String) -> Unit
) {
    Column(modifier = Modifier.background(BackgroundColor)) {
        // Handle status bar insets manually to control spacing
        Spacer(modifier = Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp), // Reduced height to minimize empty space
            contentAlignment = Alignment.Center
        ) {
            var expanded by remember { mutableStateOf(false) }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SurfaceColor)
                    .border(1.dp, BorderColor, RoundedCornerShape(50))
                    .clickable { expanded = true }
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = currentMode,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.Default.ArrowDropDown,
                        contentDescription = "Select Mode",
                        tint = TextSecondary,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    modifier = Modifier.background(SurfaceColor)
                ) {
                    modes.forEach { mode ->
                        DropdownMenuItem(
                            text = { 
                                Text(
                                    mode, 
                                    fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal,
                                    color = if (mode == currentMode) PrimaryColor else TextPrimary
                                ) 
                            },
                            onClick = {
                                onModeSelected(mode)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TranslationControls(
    sourceLang: String,
    targetLang: String,
    languages: List<String>,
    selectedEngineId: String?,
    settings: SettingsState,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSwap: () -> Unit,
    onEngineChange: (String) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.border(1.dp, BorderColor, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Engine Selector
            Row(
                verticalAlignment = Alignment.CenterVertically, 
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Translate,
                    contentDescription = null,
                    tint = PrimaryColor,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("翻译引擎", fontSize = 14.sp, color = TextSecondary, fontWeight = FontWeight.SemiBold)
                
                Spacer(modifier = Modifier.width(12.dp))
                
                var expanded by remember { mutableStateOf(false) }
                val engineList = buildList {
                    add("微软离线")
                    add("谷歌翻译（免费）")
                    add("Bing翻译（免费）")
                    addAll(settings.apiConfigs.map { it.name })
                }.distinct()
                
                Box(modifier = Modifier.weight(1f)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(BackgroundColor)
                            .clickable { expanded = true }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            val isVisual = settings.apiConfigs.find { it.name == selectedEngineId }?.isVisualModel == true
                            if (isVisual) {
                                Icon(
                                    imageVector = Icons.Default.Visibility,
                                    contentDescription = "Visual Model",
                                    tint = PrimaryColor,
                                    modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                )
                            }
                            Text(
                                text = if (selectedEngineId == "微软离线") "ML Kit (离线)" else selectedEngineId ?: "选择引擎",
                                color = TextPrimary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1
                            )
                        }
                        Icon(Icons.Default.ArrowDropDown, null, tint = TextSecondary)
                    }
                    DropdownMenu(
                        expanded = expanded, 
                        onDismissRequest = { expanded = false },
                        modifier = Modifier.background(SurfaceColor)
                    ) {
                        engineList.forEach { name ->
                            DropdownMenuItem(
                                text = { 
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        val isVisual = settings.apiConfigs.find { it.name == name }?.isVisualModel == true
                                        if (isVisual) {
                                            Icon(
                                                imageVector = Icons.Default.Visibility,
                                                contentDescription = "Visual Model",
                                                tint = PrimaryColor,
                                                modifier = Modifier.size(16.dp).padding(end = 4.dp)
                                            )
                                        }
                                        Text(if (name == "微软离线") "ML Kit (离线)" else name)
                                    }
                                },
                                onClick = {
                                    onEngineChange(name)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
            
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = Slate100)
            
            // Language Selector Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    ModernLanguageSelector(sourceLang, languages, onSourceChange)
                }
                
                IconButton(
                    onClick = onSwap,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SwapHoriz,
                        contentDescription = "Swap",
                        tint = PrimaryColor,
                        modifier = Modifier.size(28.dp)
                    )
                }
                
                Box(modifier = Modifier.weight(1f)) {
                    ModernLanguageSelector(targetLang, languages.filter { it != "自动检测" }, onTargetChange)
                }
            }
        }
    }
}

@Composable
fun ModernLanguageSelector(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(BackgroundColor)
                .clickable { expanded = true }
                .padding(vertical = 10.dp, horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selected, 
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold, 
                color = TextPrimary
            )
            Icon(Icons.Default.ArrowDropDown, null, tint = TextTertiary, modifier = Modifier.size(20.dp))
        }
        
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(SurfaceColor).heightIn(max = 600.dp)
        ) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = { 
                        Text(
                            lang, 
                            fontWeight = if (lang == selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (lang == selected) PrimaryColor else TextPrimary
                        ) 
                    },
                    onClick = {
                        onSelect(lang)
                        expanded = false
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeastModeControls(
    beastAction: String,
    onActionChange: (String) -> Unit,
    settings: SettingsState,
    repo: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier.border(1.dp, BorderColor, RoundedCornerShape(20.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            // Action Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf("加密", "解密").forEach { action ->
                    val isSelected = beastAction == action
                    FilterChip(
                        selected = isSelected,
                        onClick = { onActionChange(action) },
                        label = { 
                            Text(
                                action, 
                                modifier = Modifier.fillMaxWidth(), 
                                textAlign = TextAlign.Center,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                            ) 
                        },
                        modifier = Modifier.weight(1f).height(40.dp),
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = PrimaryColor,
                            selectedLabelColor = Color.White,
                            containerColor = BackgroundColor,
                            labelColor = TextSecondary,
                            disabledContainerColor = BackgroundColor,
                            disabledLabelColor = TextTertiary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = isSelected,
                            borderColor = if (isSelected) PrimaryColor else BorderColor
                        )
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(20.dp))
            
            // Custom Chars Input
            var tempChars by remember(settings.beastChars) { mutableStateOf(settings.beastChars) }
            val isError = tempChars.length == 4 && tempChars.toSet().size != 4
            
            Text(
                "兽音密钥 (4个不重复字符)", 
                style = MaterialTheme.typography.labelMedium, 
                color = TextSecondary,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            
            OutlinedTextField(
                value = tempChars,
                onValueChange = { newValue ->
                    if (newValue.length <= 4) {
                        tempChars = newValue
                        if (newValue.length == 4 && newValue.toSet().size == 4) {
                            scope.launch {
                                repo.saveSettings(settings.copy(beastChars = newValue))
                            }
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryColor,
                    unfocusedBorderColor = BorderColor,
                    focusedContainerColor = BackgroundColor,
                    unfocusedContainerColor = BackgroundColor
                ),
                textStyle = TextStyle(
                    fontSize = 16.sp, 
                    fontWeight = FontWeight.Medium,
                    letterSpacing = 4.sp,
                    textAlign = TextAlign.Center
                ),
                isError = isError,
                trailingIcon = {
                    if (tempChars != "嗷呜啊~") {
                        IconButton(onClick = { 
                            scope.launch { repo.saveSettings(settings.copy(beastChars = "嗷呜啊~")) }
                        }) {
                            Icon(Icons.Default.Refresh, "重置", tint = TextSecondary)
                        }
                    }
                }
            )
            
            if (isError) {
                Text(
                    "字符必须互不相同", 
                    color = ErrorColor, 
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                )
            }
        }
    }
}

@Composable
fun ModernInputCard(
    inputText: String,
    onInputChange: (String) -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
    onTranslate: () -> Unit,
    isTranslating: Boolean,
    actionLabel: String
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SurfaceColor),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp, pressedElevation = 2.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(144.dp) // Reduced by 2/5 from 240.dp (240 * 0.6 = 144)
            .shadow(
                elevation = 8.dp, 
                shape = RoundedCornerShape(24.dp), 
                spotColor = Color(0x14000000), 
                ambientColor = Color(0x14000000)
            )
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TextField(
                    value = inputText,
                    onValueChange = onInputChange,
                    placeholder = { 
                        Text(
                            "请输入文本...", 
                            color = TextTertiary, 
                            style = MaterialTheme.typography.bodyLarge
                        ) 
                    },
                    modifier = Modifier.fillMaxSize(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = TextPrimary,
                        lineHeight = 24.sp
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = PrimaryColor
                    )
                )
                
                if (inputText.isNotEmpty()) {
                    IconButton(
                        onClick = onClear,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(4.dp)
                    ) {
                        Icon(Icons.Default.Clear, "Clear", tint = TextTertiary)
                    }
                }
            }
            
            // Action Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = onPaste,
                    colors = ButtonDefaults.textButtonColors(contentColor = TextSecondary)
                ) {
                    Icon(Icons.Default.ContentPaste, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("粘贴")
                }
                
                Button(
                    onClick = onTranslate,
                    enabled = !isTranslating && inputText.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = PrimaryColor,
                        disabledContainerColor = PrimaryColor.copy(alpha = 0.5f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 10.dp)
                ) {
                    if (isTranslating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.5.dp
                        )
                    } else {
                        Text(actionLabel, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ModernOutputCard(
    outputText: String,
    onOutputChange: (String) -> Unit,
    targetLang: String,
    onCopy: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = PrimaryContainer),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 140.dp)
            .border(1.dp, PrimaryColor.copy(alpha = 0.2f), RoundedCornerShape(24.dp))
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(PrimaryColor)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = targetLang,
                        fontSize = 13.sp,
                        color = OnPrimaryContainer,
                        fontWeight = FontWeight.Bold
                    )
                }
                
                IconButton(
                    onClick = onCopy,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy, 
                        "Copy", 
                        tint = OnPrimaryContainer,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            TextField(
                value = outputText,
                onValueChange = onOutputChange,
                modifier = Modifier.fillMaxWidth(),
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    fontSize = 17.sp,
                    lineHeight = 26.sp,
                    color = TextPrimary
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    cursorColor = PrimaryColor
                )
            )
        }
    }
}
