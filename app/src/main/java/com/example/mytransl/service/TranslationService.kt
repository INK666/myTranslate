package com.example.mytransl.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.RectF
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.example.mytransl.R
import com.example.mytransl.data.ocr.PaddleOcrEngine
import com.example.mytransl.data.ocr.MlKitOcrEngine
import com.example.mytransl.data.settings.SettingsRepository
import com.example.mytransl.data.settings.ApiConfig
import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.data.translation.LruTranslationCache
import com.example.mytransl.data.translation.engines.OfflineDictionaryEngine
import com.example.mytransl.data.translation.engines.OnlineApiEngine
import com.example.mytransl.data.translation.engines.MicrosoftTranslationEngine
import com.example.mytransl.data.translation.engines.GoogleFreeEngine
import com.example.mytransl.data.translation.engines.BingFreeEngine
import com.example.mytransl.domain.translation.TranslationEngine
import com.example.mytransl.domain.translation.TranslationEngineManager
import com.example.mytransl.system.capture.ScreenCapturer
import com.example.mytransl.system.overlay.OverlayController
import com.example.mytransl.system.permissions.CapturePermissionStore
import com.example.mytransl.system.service.TranslationServiceState
import com.example.mytransl.domain.ocr.PreferredLanguageAwareOcrEngine
import com.example.mytransl.domain.ocr.TextBlock
import com.example.mytransl.system.resource.ResourceManager
import com.example.mytransl.system.resource.OcrEnginePool
import com.example.mytransl.system.resource.HttpClientPool
import com.example.mytransl.system.resource.MemoryMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class TranslationService : Service() {
    companion object {
        const val ACTION_START = "com.example.mytransl.action.START"
        const val ACTION_STOP = "com.example.mytransl.action.STOP"

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "translation"
        private const val NOTIFICATION_ID = 1001
        private const val VISUAL_ENGINE_ID = "视觉大模型"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var overlay: OverlayController
    private lateinit var capturer: ScreenCapturer
    private val ocrEnginePool = OcrEnginePool()
    private var ocr: PreferredLanguageAwareOcrEngine = PaddleOcrEngine()

    private val cache = LruTranslationCache(256)
    // 使用共享的 HTTP 客户端池
    private val visualClient get() = HttpClientPool.standardClient
    @Volatile
    private lateinit var engineManager: TranslationEngineManager

    private lateinit var settingsRepo: SettingsRepository
    private var currentSettings: SettingsState = SettingsState()
    
    // 内存监控器
    private lateinit var memoryMonitor: MemoryMonitor

    private var selectedRect: RectF? = null
    private var selectedRectBaseWidth = 0f
    private var selectedRectBaseHeight = 0f
    private var lastOcrText: String = ""
    private var lastFrameHash: Long? = null
    private var lastOcrAtMs: Long = 0L
    private var lastOverlayUpdateAtMs: Long = 0L
    private var showingText = true
    private var busy = false
    private var overlayReady = false
    private var captureJob: Job? = null
    private var autoDebounceJob: Job? = null
    private val ocrMutex = Mutex()
    private var autoArmed = false
    private var autoPausedByVisibility = false
    private var captureReady = false
    private var stopped = false


    private fun getStatusBarHeight(): Float {
        val res = resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) {
            res.getDimensionPixelSize(id).toFloat()
        } else {
            TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                32f,
                res.displayMetrics
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        overlay = OverlayController(applicationContext)
        capturer = ScreenCapturer(applicationContext)
        settingsRepo = SettingsRepository(applicationContext)
        createChannel()
        
        // 监听捕获状态变化，更新通知栏
        capturer.onStateChanged = { state ->
            val statusText = when (state) {
                ScreenCapturer.CaptureState.ACTIVE -> "正在翻译屏幕"
                ScreenCapturer.CaptureState.PAUSED -> "翻译服务已暂停（待命）"
                ScreenCapturer.CaptureState.WAITING_TOKEN -> "等待释放资源"
                ScreenCapturer.CaptureState.IDLE -> "服务已停止"
            }
            updateNotification(statusText)
        }
        
        // 初始化内存监控器
        memoryMonitor = MemoryMonitor(
            scope = scope,
            checkIntervalMs = 30_000L, // 30秒检查一次
            warningThreshold = 80,
            criticalThreshold = 90,
            onMemoryWarning = { memInfo ->
                Log.w("TranslationService", "内存使用率较高: ${memInfo.usagePercent}%")
            },
            onMemoryCritical = { memInfo ->
                Log.e("TranslationService", "内存使用率过高: ${memInfo.usagePercent}%, 触发清理")
                runCatching { 
                    overlay.showStatusOverlay("内存不足，正在清理...", 1500)
                }
            }
        )
        memoryMonitor.start()
        
        // 注册资源清理回调
        ResourceManager.registerCleanupCallback {
            runCatching { capturer.stop() }
            runCatching { overlay.hideAll() }
        }
        
        engineManager = TranslationEngineManager(
            enginesById = buildEngines(applicationContext, SettingsState()),
            cache = cache,
            onValidationFailed = { _ -> }
        )
        scope.launch {
            settingsRepo.settings.collect { state ->
                currentSettings = state
                // 使用 OCR 引擎池管理引擎
                ocr = ocrEnginePool.getOrCreate(state.ocrEngine) {
                    buildOcrEngine(state, ocr)
                }
                cache.updateCapacity(state.cacheSize.coerceIn(64, 4096))
                engineManager = TranslationEngineManager(
                    enginesById = buildEngines(applicationContext, state),
                    cache = cache,
                    onValidationFailed = { _ -> }
                )
            }
        }
    }

    private fun buildOcrEngine(
        state: SettingsState,
        current: PreferredLanguageAwareOcrEngine
    ): PreferredLanguageAwareOcrEngine {
        val id = state.ocrEngine.trim()
        val usePaddle = id.isBlank() || id.equals("PaddleOCR", ignoreCase = true)
        return if (usePaddle) {
            if (current is PaddleOcrEngine) current else PaddleOcrEngine()
        } else {
            if (current is MlKitOcrEngine) current else MlKitOcrEngine()
        }
    }

    // 判断是否启用视觉大模型
    private fun isVisualEngineActive(settings: SettingsState): Boolean {
        return resolveVisualConfig(settings) != null
    }

    // 获取视觉大模型对应的 API 配置
    private fun resolveVisualConfig(settings: SettingsState): ApiConfig? {
        val config = settings.apiConfigs.firstOrNull { it.name == settings.defaultEngine }
        return if (config != null && config.isVisualModel) config else null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTranslation(intent)
            ACTION_STOP -> stopTranslation()
        }
        return START_STICKY
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        android.util.Log.d("TranslationService", "Orientation changed: ${newConfig.orientation}")
        
        // 1. Resize capturer for new layout
        runCatching { capturer.resize() }
        
        // 2. Clear selected area because absolute coordinates are now invalid
        selectedRect = null
        
        // 3. Reset UI and jobs
        resetTranslationSession()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopTranslation(true)
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startTranslation(intent: Intent) {
        stopped = false
        startInForeground("运行中")
        TranslationServiceState.setRunning(true)

        autoArmed = false
        captureReady = false

        var floatingBallClick: (() -> Unit)? = null
        overlayReady = runCatching {
            overlay.showFloatingBall(
                onClick = {
                    floatingBallClick?.invoke()
                }
            )
            // 设置独立浮窗关闭回调
            overlay.onTextOverlayClosed = {
                val isAutoMode = currentSettings.translationMode.startsWith("自动")
                if (isAutoMode) {
                    // 自动模式下，关闭译文框时同步更新状态
                    showingText = false
                    overlay.updateAutoVisibility(showingText)
                    // 如果正在运行，暂停翻译
                    if (autoArmed) {
                        autoArmed = false
                        autoPausedByVisibility = true
                        overlay.updateAutoControl(false)
                    }
                }
            }
            if (currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)) {
                overlay.showTextOverlay()
                overlay.hideContentOverlay()
            } else {
                overlay.hideTextOverlay()
                overlay.setContentOverlayVisible(showingText)
            }
            true
        }.getOrElse {
            updateNotification("悬浮窗创建失败：${it.message ?: it.javaClass.simpleName}")
            Toast.makeText(applicationContext, "悬浮窗创建失败，请检查悬浮窗权限", Toast.LENGTH_LONG).show()
            false
        }
        if (!overlayReady) {
            stopTranslation()
            return
        }

        capturer.stopTimers()
        val resumed = runCatching { capturer.resume() }.getOrDefault(false)
        if (resumed) {
            Log.i("TranslationService", "Successfully resumed existing projection")
        } else {
            Log.d("TranslationService", "Resume failed or no projection, requesting new permission")
            Log.d("TranslationService", "Resume failed or no projection, requesting new permission")
            val (resultCode, resultData) = extractProjectionResult(intent)
            if (resultCode == 0 || resultData == null) {
                if (overlayReady) runCatching { overlay.updateTextOverlay("未获取截屏权限") }
                updateNotification("未获取截屏权限")
                stopTranslation()
                return
            }

            val mgr = getSystemService(MediaProjectionManager::class.java)
            val projection = runCatching {
                mgr.getMediaProjection(resultCode, resultData)
            }.getOrElse {
                updateNotification("截屏权限异常：${it.message ?: it.javaClass.simpleName}")
                runCatching { Toast.makeText(applicationContext, "截屏权限异常，请重新授权", Toast.LENGTH_LONG).show() }
                stopTranslation()
                return
            } ?: run {
                if (overlayReady) runCatching { overlay.updateTextOverlay("截屏权限不可用") }
                updateNotification("截屏权限不可用（请重新授权）")
                stopTranslation()
                return
            }

            runCatching {
                capturer.start(projection)
            }.onFailure {
                if (it is SecurityException || it is IllegalStateException) {
                    CapturePermissionStore.clear()
                }
                updateNotification("启动截屏失败：${it.message ?: it.javaClass.simpleName}")
                runCatching { Toast.makeText(applicationContext, "启动截屏失败", Toast.LENGTH_LONG).show() }
                stopTranslation()
                return
            }
        }

        captureReady = true

        val needsRegion = currentSettings.translationMode.contains("区域")
        if (!needsRegion) {
            selectedRect = null
        }

        fun startCaptureFlow() {
            val isFixed = currentSettings.translationMode.startsWith("固定")
            val isAuto = currentSettings.translationMode.startsWith("自动")
            if (isFixed) {
                captureJob?.cancel()
                captureJob = scope.launch {
                    while (isActive && !stopped) {
                        val bitmap = try {
                            captureCleanFrame()
                        } catch (t: Throwable) {
                            runCatching { overlay.updateTextOverlay("截屏失败：${t.message ?: t.javaClass.simpleName}") }
                            null
                        }
                        if (bitmap != null) {
                            runCatching { processFrame(bitmap) }
                                .onFailure { runCatching { overlay.updateTextOverlay("处理失败：${it.message ?: it.javaClass.simpleName}") } }
                        }
                        delay(3000)
                    }
                }
            } else if (isAuto) {
                autoArmed = true
                overlay.showAutoControl(
                    onToggle = {
                        autoArmed = !autoArmed
                        overlay.updateAutoControl(autoArmed)
                    },
                    onVisibility = {
                        showingText = !showingText
                        overlay.updateAutoVisibility(showingText)
                        val isAutoMode = currentSettings.translationMode.startsWith("自动")
                        if (isAutoMode) {
                            if (!showingText) {
                                // 隐藏译文时：暂停 OCR
                                if (autoArmed) {
                                    autoArmed = false
                                    autoPausedByVisibility = true
                                    overlay.updateAutoControl(false)
                                }
                            }
                            // 展示译文时：不自动恢复 OCR，保持暂停状态
                            // 用户需要手动点击暂停/开始按钮来恢复翻译
                        }
                        // Trigger immediate visibility update for the overlay
                        if (currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)) {
                            overlay.setTextOverlayVisible(showingText)
                        } else {
                            overlay.setContentOverlayVisible(showingText)
                        }
                    },
                    onStop = {
                        stopTranslation()
                    }
                )
                
                captureJob?.cancel()
                autoDebounceJob?.cancel()
                lastFrameHash = null
                lastOcrAtMs = 0L

                fun computeAHash(bitmap: Bitmap): Long {
                    val scaled = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
                    val pixels = IntArray(64)
                    scaled.getPixels(pixels, 0, 8, 0, 0, 8, 8)
                    ResourceManager.recycleBitmap(scaled)
                    var sum = 0
                    val lumas = IntArray(64)
                    for (i in 0 until 64) {
                        val c = pixels[i]
                        val r = (c shr 16) and 0xFF
                        val g = (c shr 8) and 0xFF
                        val b = c and 0xFF
                        val y = (r * 299 + g * 587 + b * 114) / 1000
                        lumas[i] = y
                        sum += y
                    }
                    val avg = sum / 64
                    var hash = 0L
                    for (i in 0 until 64) {
                        if (lumas[i] >= avg) {
                            hash = hash or (1L shl i)
                        }
                    }
                    return hash
                }


                captureJob = scope.launch {
                    val isSubtitle = currentSettings.autoSpeedMode == "字幕"
                    val isLandscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                    
                    val sampleMs = if (isSubtitle) 250L else 500L
                    var diffThreshold = if (isSubtitle) 0.010f else 0.018f
                    if (isLandscape) {
                        diffThreshold *= 0.6f // Higher sensitivity booster for landscape
                    }
                    val overlayCooldownMs = if (isSubtitle) 500L else 1500L
                    
                    var lastHash: LongArray? = null

                    while (isActive && !stopped) {
                        if (!autoArmed || autoDebounceJob?.isActive == true) {
                            delay(sampleMs)
                            continue
                        }
                        
                        val now = SystemClock.elapsedRealtime()
                        val isCooling = now - lastOverlayUpdateAtMs < overlayCooldownMs
                        
                        val bitmap = runCatching { capturer.capture() }.getOrNull()
                        if (bitmap != null) {
                            val needsRegion = currentSettings.translationMode.contains("区域")
                            val targetBmp = if (needsRegion && selectedRect != null) {
                                runCatching { 
                                    val (sw, sh) = getRealScreenSize()
                                    val scaleX = bitmap.width.toFloat() / sw
                                    val scaleY = bitmap.height.toFloat() / sh
                                    val left = (selectedRect!!.left * scaleX).toInt().coerceIn(0, bitmap.width - 1)
                                    val top = (selectedRect!!.top * scaleY).toInt().coerceIn(0, bitmap.height - 1)
                                    val right = (selectedRect!!.right * scaleX).toInt().coerceIn(left + 1, bitmap.width)
                                    val bottom = (selectedRect!!.bottom * scaleY).toInt().coerceIn(top + 1, bitmap.height)
                                    Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
                                }.getOrElse { bitmap }
                            } else bitmap

                            val hash = calculateImageHash(targetBmp)
                            if (targetBmp !== bitmap) ResourceManager.recycleBitmap(targetBmp)
                            ResourceManager.recycleBitmap(bitmap)
                            
                            if (isCooling) {
                                lastHash = hash
                                delay(sampleMs)
                                continue
                            }

                            val changed = if (lastHash != null) {
                                calculateDifference(lastHash!!, hash) > diffThreshold
                            } else true
                            
                            lastHash = hash
                            if (changed) requestOcrDebounced()
                        }
                        delay(sampleMs)
                    }
                }
                
                // Kickstart the first OCR immediately upon starting the flow
                requestOcrDebounced()
            } else {
                if (overlayReady) runCatching { overlay.showStatusOverlay("点击悬浮球开始翻译") }
                updateNotification("等待点击悬浮球")
            }
        }

        fun toggleOverlayVisibility() {
            showingText = !showingText
            if (currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)) {
                overlay.setTextOverlayVisible(showingText)
            } else {
                overlay.setContentOverlayVisible(showingText)
            }
        }

        floatingBallClick = click@{
            val mode = currentSettings.translationMode
            val isSingle = mode.startsWith("单次")
            val isAutoMode = mode.startsWith("自动")

            if (!captureReady) {
                runCatching { overlay.showStatusOverlay("截屏未就绪") }
                return@click
            }

            if (isSingle) {
                if (busy) return@click
                busy = true
                scope.launch {
                    try {
                        runSingleOnce()
                    } finally {
                        busy = false
                    }
                }
                return@click
            }

            if (isAutoMode) {
                if (!autoArmed) {
                    autoArmed = true
                    overlay.updateAutoControl(true)
                    // 启动自动检测时，如果译文被隐藏，需要显示译文并同步图标状态
                    if (!showingText) {
                        showingText = true
                        overlay.updateAutoVisibility(showingText)
                        if (currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)) {
                            overlay.setTextOverlayVisible(showingText)
                        } else {
                            overlay.setContentOverlayVisible(showingText)
                        }
                    }
                    if (mode.contains("区域") && selectedRect == null) {
                        runCatching {
                            overlay.showSelectionOverlay(
                                onConfirm = { rect, w, h ->
                                    selectedRect = rect
                                    selectedRectBaseWidth = w.coerceAtLeast(1).toFloat()
                                    selectedRectBaseHeight = h.coerceAtLeast(1).toFloat()
                                    runCatching { overlay.showStatusOverlay("自动检测中") }
                                    updateNotification("自动检测中")
                                    selectedRect?.let { overlay.ensureTextOverlayAwayFromRegion(it) }
                                    startCaptureFlow()
                                },
                                onCancel = {
                                    autoArmed = false
                                    runCatching { overlay.showStatusOverlay("已取消区域选择") }
                                    updateNotification("等待开始自动检测")
                                }
                            )
                        }.onFailure {
                            autoArmed = false
                            runCatching { overlay.showStatusOverlay("区域选择失败") }
                            updateNotification("区域选择失败：${it.message ?: it.javaClass.simpleName}")
                        }
                    } else {
                        runCatching { overlay.showStatusOverlay("自动检测中") }
                        updateNotification("自动检测中")
                        selectedRect?.let { overlay.ensureTextOverlayAwayFromRegion(it) }
                        startCaptureFlow()
                    }
                } else {
                    // Manual OCR trigger in auto mode
                    if (!busy) {
                        busy = true
                        scope.launch {
                            try {
                                runSingleOnce()
                            } finally {
                                busy = false
                            }
                        }
                    }
                }
                return@click
            }

            // Manual mode logic
            if (mode.contains("区域") && selectedRect == null) {
                runCatching {
                    overlay.showSelectionOverlay(
                        onConfirm = { rect, w, h ->
                            selectedRect = rect
                            selectedRectBaseWidth = w.coerceAtLeast(1).toFloat()
                            selectedRectBaseHeight = h.coerceAtLeast(1).toFloat()
                            // Auto-trigger OCR after selection for manual mode
                            if (!busy) {
                                busy = true
                                scope.launch {
                                    try {
                                        runSingleOnce()
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                        },
                        onCancel = {
                            runCatching { overlay.showStatusOverlay("已取消区域选择") }
                        }
                    )
                }
                return@click
            }

            toggleOverlayVisibility()
        }

        val isAutoMode = currentSettings.translationMode.startsWith("自动")
        if (isAutoMode) {
            if (overlayReady) runCatching { overlay.showStatusOverlay("点击悬浮球开始自动检测") }
            updateNotification("等待开始自动检测")
            return
        }

        if (needsRegion && selectedRect == null) {
            val isFixed = currentSettings.translationMode.startsWith("固定")
            if (!isFixed) {
                runCatching { overlay.showStatusOverlay("点击悬浮球开始翻译") }
                updateNotification("等待点击悬浮球")
                return
            }
            runCatching {
                overlay.showSelectionOverlay(
                    onConfirm = { rect, w, h ->
                        selectedRect = rect
                        selectedRectBaseWidth = w.coerceAtLeast(1).toFloat()
                        selectedRectBaseHeight = h.coerceAtLeast(1).toFloat()
                        startCaptureFlow()
                    },
                    onCancel = {
                        updateNotification("已取消区域选择")
                        stopTranslation()
                    }
                )
            }.onFailure {
                updateNotification("区域选择失败：${it.message ?: it.javaClass.simpleName}")
                stopTranslation()
            }
            return
        }

        startCaptureFlow()
    }

    private fun stopTranslation(forceStop: Boolean = false) {
        if (stopped && !forceStop) return
        if (!stopped) {
            stopped = true
            busy = false  // 重置 busy 状态，防止悬浮球点击无响应
            TranslationServiceState.setRunning(false)
            resetTranslationSession()
            runCatching { overlay.hideAll() }
            runCatching { capturer.stop() }
        }
        val hasToken = runCatching { capturer.hasProjection() }.getOrDefault(false)
        if (forceStop || !hasToken) {
            CapturePermissionStore.clear()
            runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            runCatching { stopSelf() }
        }
    }

    private fun resetTranslationSession() {
        captureJob?.cancel()
        captureJob = null
        autoDebounceJob?.cancel()
        autoDebounceJob = null
        autoArmed = false
        busy = false  // 重置 busy 状态，确保下次可以正常点击
        
        runCatching {
            overlay.hideContentOverlay()
            overlay.hideTextOverlay()
            overlay.hideAutoControl()
            overlay.hideStatusOverlay()
            overlay.setFloatingBallLoading(false)
            overlay.setContentOverlaySelectedRect(null)
        }
        
        lastOcrText = ""
        // lastFrameHash = null // depends on field name
        showingText = true 
    }

    private suspend fun processFrame(bitmap: Bitmap, validationHash: LongArray? = null) {
        try {
            if (isVisualEngineActive(currentSettings)) {
                processVisualFrame(bitmap, validationHash)
                return
            }
            ocr.preferredLanguage = currentSettings.sourceLanguage.takeIf { it != "自动检测" }
            val (realScreenWidth, realScreenHeight) = getRealScreenSize()
            
            val region = selectedRect?.let { rect ->
                val scaleX = if (realScreenWidth > 0) bitmap.width.toFloat() / realScreenWidth else 1f
                val scaleY = if (realScreenHeight > 0) bitmap.height.toFloat() / realScreenHeight else 1f
                RectF(
                    rect.left * scaleX,
                    rect.top * scaleY,
                    rect.right * scaleX,
                    rect.bottom * scaleY
                )
            }

            var cropLeft = 0
            var cropTop = 0
            val ocrBitmap = if (region != null) {
                val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
                val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
                val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
                val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
                cropLeft = left
                cropTop = top
                runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }.getOrElse { bitmap }
            } else {
                bitmap
            }

            val rawBlocks = runCatching { ocr.recognize(ocrBitmap) }.getOrNull().orEmpty()
            if (ocrBitmap !== bitmap) {
                ResourceManager.recycleBitmap(ocrBitmap)
            }
            val allBlocks = if (region != null && (cropLeft != 0 || cropTop != 0)) {
                rawBlocks.map { block ->
                    val bounds = RectF(block.bounds)
                    bounds.offset(cropLeft.toFloat(), cropTop.toFloat())
                    block.copy(bounds = bounds)
                }
            } else {
                rawBlocks
            }
            
            val blocks = allBlocks.filter { b ->
                val r = b.bounds
                if (region != null) {
                    val left = maxOf(r.left, region.left)
                    val top = maxOf(r.top, region.top)
                    val right = minOf(r.right, region.right)
                    val bottom = minOf(r.bottom, region.bottom)
                    val iw = right - left
                    val ih = bottom - top
                    if (iw <= 0f || ih <= 0f) return@filter false
                    val intersection = iw * ih
                    val area = (r.width() * r.height()).coerceAtLeast(1f)
                    if (intersection / area < 0.25f) return@filter false
                }
                true
            }

            val sortedBlocks = if (currentSettings.isMangaMode) sortBlocksForManga(blocks) else blocks
            val text = sortedBlocks.joinToString("\n") { it.text }.trim()
            val isSingle = currentSettings.translationMode.startsWith("单次")
            if (!isSingle && (text.isBlank() || text == lastOcrText)) return
            lastOcrText = text

            val settings = currentSettings
            val sameLang = settings.sourceLanguage.trim().isNotEmpty() &&
                settings.targetLanguage.trim().isNotEmpty() &&
                settings.sourceLanguage.trim() == settings.targetLanguage.trim()
            
            val shouldIndicate = !sameLang
            if (shouldIndicate) runCatching { overlay.setFloatingBallLoading(true) }
            
            try {
                val source = settings.sourceLanguage.takeIf { it != "自动检测" }
                val translated = if (sameLang) text else {
                    val res = engineManager.translateStrict(text, source, settings.targetLanguage, settings)
                    if (res == null) {
                        // Callback already handled the popup
                        text
                    } else {
                        res
                    }
                }

                // VALIDATION CHECK
                if (validationHash != null) {
                    val checkBmp = runCatching { capturer.capture() }.getOrNull()
                    if (checkBmp != null) {
                        val currentHash = calculateImageHash(checkBmp)
                        ResourceManager.recycleBitmap(checkBmp)
                        if (calculateDifference(validationHash, currentHash) > 0.04f) {
                             android.util.Log.d("TranslationService", "Screen changed. Discarding old result.")
                             requestOcrDebounced()
                             return
                        }
                    }
                }

                if (settings.resultMode == "独立窗口") {
                    lastOverlayUpdateAtMs = SystemClock.elapsedRealtime()
                    runCatching { overlay.updateTextOverlay(translated) }
                    runCatching { overlay.setTextOverlayVisible(true) }
                    runCatching { overlay.setContentOverlaySelectedRect(selectedRect) }
                    runCatching { overlay.updateContentOverlay(emptyList()) }
                    runCatching { overlay.setContentOverlayVisible(selectedRect != null) }
                } else {
                    val targetW = realScreenWidth
                    val targetH = realScreenHeight
                    // Stop subtracting offsets here. OverlayController will handle it during draw.
                    val items = buildContentOverlayItems(blocks, source, settings.targetLanguage, sameLang, settings, targetW, targetH, bitmap.width, bitmap.height, 0, 0)
                    
                    lastOverlayUpdateAtMs = SystemClock.elapsedRealtime()
                    runCatching { overlay.setContentOverlaySelectedRect(selectedRect) }
                    runCatching { overlay.updateContentOverlay(items) }
                    runCatching { overlay.setContentOverlayVisible(true) }
                    runCatching { overlay.setTextOverlayVisible(false) }
                }
            } finally {
                if (shouldIndicate) runCatching { overlay.setFloatingBallLoading(false) }
            }
        } finally {
            ResourceManager.recycleBitmap(bitmap)
        }
    }

    // 视觉大模型流程：直接识别图片并翻译
    private suspend fun processVisualFrame(bitmap: Bitmap, validationHash: LongArray? = null) {
        val settings = currentSettings
        val config = resolveVisualConfig(settings)
        if (config == null) {
            runCatching { overlay.showTextOverlay() }
            runCatching { overlay.updateTextOverlay("未选择视觉大模型") }
            runCatching { overlay.setTextOverlayVisible(true) }
            runCatching { overlay.setContentOverlayVisible(false) }
            return
        }

        val (realScreenWidth, realScreenHeight) = getRealScreenSize()
        val region = selectedRect?.let { rect ->
            val scaleX = if (realScreenWidth > 0) bitmap.width.toFloat() / realScreenWidth else 1f
            val scaleY = if (realScreenHeight > 0) bitmap.height.toFloat() / realScreenHeight else 1f
            RectF(
                rect.left * scaleX,
                rect.top * scaleY,
                rect.right * scaleX,
                rect.bottom * scaleY
            )
        }

        val visualBitmap = if (region != null) {
            val left = region.left.toInt().coerceIn(0, bitmap.width - 1)
            val top = region.top.toInt().coerceIn(0, bitmap.height - 1)
            val right = region.right.toInt().coerceIn(left + 1, bitmap.width)
            val bottom = region.bottom.toInt().coerceIn(top + 1, bitmap.height)
            runCatching { Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top) }.getOrElse { bitmap }
        } else {
            bitmap
        }

        val source = settings.sourceLanguage.takeIf { it != "自动检测" }
        
        // 设置悬浮球为加载状态（橙色）
        runCatching { overlay.setFloatingBallLoading(true) }
        
        val translated = try {
            OnlineApiEngine(config, visualClient)
                .translateImage(visualBitmap, source, settings.targetLanguage, settings)
        } finally {
            // 翻译完成后恢复悬浮球状态
            runCatching { overlay.setFloatingBallLoading(false) }
            if (visualBitmap !== bitmap) {
                ResourceManager.recycleBitmap(visualBitmap)
            }
        }

        if (validationHash != null) {
            val checkBmp = runCatching { capturer.capture() }.getOrNull()
            if (checkBmp != null) {
                val currentHash = calculateImageHash(checkBmp)
                ResourceManager.recycleBitmap(checkBmp)
                if (calculateDifference(validationHash, currentHash) > 0.04f) {
                    requestOcrDebounced()
                    return
                }
            }
        }

        lastOverlayUpdateAtMs = SystemClock.elapsedRealtime()
        runCatching { overlay.showTextOverlay() }
        runCatching { overlay.updateTextOverlay(translated) }
        runCatching { overlay.setTextOverlayVisible(showingText) }
        runCatching { overlay.updateContentOverlay(emptyList()) }
        runCatching { overlay.setContentOverlayVisible(false) }
    }

    private fun sortBlocksForManga(blocks: List<TextBlock>): List<TextBlock> {
        if (blocks.isEmpty()) return blocks

        // Split blocks: Main vertical content vs. Horizontal noise
        // Relaxed condition: Only treat VERY wide blocks as horizontal noise.
        // Standard Kanji/Kana blocks (even if slightly wide) should be treated as vertical flow candidates.
        val (verticalBlocks, horizontalBlocks) = blocks.partition {
            val h = it.bounds.height()
            val w = it.bounds.width()
            // Treat as vertical if height is at least 40% of width (allows aspect ratio up to 2.5:1 width:height)
            // Or if the text is very short (likely single char or punctuation), force vertical processing
            h >= w * 0.4f || it.text.length <= 1
        }

        // 2. Process Vertical blocks from Right to Left
        // Use Horizontal Overlap Clustering instead of Distance Threshold
        val sortedByXDesc = verticalBlocks.sortedByDescending { it.bounds.centerX() }
        val lanes = mutableListOf<MutableList<TextBlock>>()

        for (block in sortedByXDesc) {
            var bestLane: MutableList<TextBlock>? = null
            var maxOverlapRatio = 0f

            for (lane in lanes) {
                // Calculate average bounds of the lane
                val laneLeft = lane.minOf { it.bounds.left }
                val laneRight = lane.maxOf { it.bounds.right }
                val laneWidth = laneRight - laneLeft
                
                // Calculate overlap with current block
                val overlapLeft = maxOf(block.bounds.left, laneLeft)
                val overlapRight = minOf(block.bounds.right, laneRight)
                val overlapWidth = overlapRight - overlapLeft
                
                if (overlapWidth > 0) {
                    val blockWidth = block.bounds.width()
                    // Check overlap ratio against the smaller width to handle variable font sizes
                    val minWidth = minOf(laneWidth, blockWidth)
                    val ratio = if (minWidth > 0) overlapWidth / minWidth else 0f
                    
                    if (ratio > maxOverlapRatio) {
                        maxOverlapRatio = ratio
                        bestLane = lane
                    }
                }
            }

            // Strict threshold: Must overlap by at least 30% to be considered same column
            if (bestLane != null && maxOverlapRatio > 0.3f) {
                bestLane.add(block)
            } else {
                lanes.add(mutableListOf(block))
            }
        }

        // 3. Merge blocks within each lane (Vertical Merge)
        val mergedVertical = lanes.map { lane ->
            // Sort top-to-bottom first
            val sorted = lane.sortedBy { it.bounds.top }
            val merged = mutableListOf<TextBlock>()
            if (sorted.isEmpty()) return@map emptyList<TextBlock>()
            
            var current = sorted[0]
            for (i in 1 until sorted.size) {
                val next = sorted[i]
                
                // Vertical distance check
                // Calculate gap between current bottom and next top
                val gap = next.bounds.top - current.bounds.bottom
                // Allow merging if gap is small (relative to width, simulating line spacing)
                // or even slightly negative (overlapping)
                val maxGap = maxOf(current.bounds.width(), next.bounds.width()) * 1.5f
                
                if (gap < maxGap) {
                    // Merge!
                    val newRect = RectF(
                        minOf(current.bounds.left, next.bounds.left),
                        current.bounds.top, // Top from current
                        maxOf(current.bounds.right, next.bounds.right),
                        next.bounds.bottom // Bottom from next
                    )
                    // Concatenate text (no space for vertical Japanese/Chinese usually)
                    val newText = current.text + next.text
                    current = current.copy(text = newText, bounds = newRect)
                } else {
                    // Gap too big, push current and start new
                    merged.add(current)
                    current = next
                }
            }
            merged.add(current)
            merged
        }.flatten()

        // 4. Append horizontal blocks at the end (sorted Top-to-Bottom)
        val sortedHorizontal = horizontalBlocks.sortedBy { it.bounds.top }

        return mergedVertical + sortedHorizontal
    }

    private fun requestOcrDebounced() {
        val isSubtitle = currentSettings.autoSpeedMode == "字幕"
        val debounceMs = if (isSubtitle) 80L else 450L 
        val minIntervalMs = if (isSubtitle) 350L else 900L
        autoDebounceJob?.cancel()
        autoDebounceJob = scope.launch {
            delay(debounceMs)
            val now = SystemClock.elapsedRealtime()
            val wait = (minIntervalMs - (now - lastOcrAtMs)).coerceAtLeast(0L)
            if (wait > 0L) delay(wait)
            if (!isActive || stopped) return@launch
            ocrMutex.withLock {
                if (!isActive || stopped) return@withLock
                
                val isLocal = currentSettings.translationMode.contains("区域")
                val isIndependent = currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)
                val fastTrack = isSubtitle && isLocal && isIndependent && !isOverlayOverlapping()

                // Only capture validation fingerprint if we NEED to flicker (hide/show)
                val vHash = if (fastTrack) null else {
                    runCatching {
                        capturer.capture()?.let { 
                            val h = calculateImageHash(it)
                            it.recycle()
                            h
                        }
                    }.getOrNull()
                }

                val bitmap = runCatching { captureFrameForAutoOcr() }.getOrNull()
                if (bitmap != null) {
                    runCatching { processFrame(bitmap, vHash) }
                    lastOcrAtMs = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    private suspend fun captureCleanFrame(): Bitmap? {
        val isSingle = currentSettings.translationMode.startsWith("单次")
        val isSubtitle = currentSettings.autoSpeedMode == "字幕"
        val isLocal = currentSettings.translationMode.contains("区域")
        val isIndependent = currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)
        
        // 手动+积极追赶（字幕）：强制隐藏以确保截图清晰，响应用户“立即隐藏”的需求
        val isManualRabbit = isSingle && isSubtitle
        val needsFlicker = isManualRabbit || (!isSubtitle || !isLocal || !isIndependent || isOverlayOverlapping())

        if (needsFlicker) {
            runCatching { overlay.setTextOverlayVisible(false) }
            runCatching { overlay.setFloatingBallVisible(false) }
            runCatching { overlay.setContentOverlayVisible(false) }
            val hideDelay = if (isSubtitle) 60L else 300L
            delay(hideDelay)
        }
        var bitmap: Bitmap? = null
        for (i in 0 until 3) {
            bitmap = runCatching { capturer.capture() }.getOrNull()
            if (bitmap != null) break
            delay(50)
        }

        if (needsFlicker) {
            val shouldShowTextAfter = isIndependent && (isSingle || showingText)
            val shouldShowContentAfter = !isIndependent && (isSingle || showingText)
            runCatching { overlay.setFloatingBallVisible(true) }
            runCatching { overlay.setTextOverlayVisible(shouldShowTextAfter) }
            runCatching { overlay.setContentOverlayVisible(shouldShowContentAfter) }
        }
        return bitmap
    }
    private suspend fun captureFrameForAutoOcr(): Bitmap? {
        val isSubtitle = currentSettings.autoSpeedMode == "字幕"
        val isLocal = currentSettings.translationMode.contains("区域")
        val isIndependent = currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)
        
        val needsFlicker = !isSubtitle || !isLocal || !isIndependent || isOverlayOverlapping()

        if (needsFlicker) {
            runCatching { overlay.setTextOverlayVisible(false) }
            runCatching { overlay.setContentOverlayVisible(false) }
            runCatching { overlay.setFloatingBallVisible(false) }
            delay(if (isSubtitle) 60L else 150L)
        }

        val bitmap = runCatching { capturer.capture() }.getOrNull()

        if (needsFlicker) {
            val shouldShowTextAfter = isIndependent && showingText
            val shouldShowContentAfter = !isIndependent && showingText
            runCatching { overlay.setFloatingBallVisible(true) }
            runCatching { overlay.setTextOverlayVisible(shouldShowTextAfter) }
            runCatching { overlay.setContentOverlayVisible(shouldShowContentAfter) }
        }
        return bitmap
    }

    private fun isOverlayOverlapping(): Boolean {
        val selected = selectedRect ?: return true // If no region, assume overlap for safety
        val window = overlay.getTextOverlayBounds() ?: return false // If no window, no overlap
        return RectF.intersects(selected, window)
    }

    private suspend fun runSingleOnce() {
        val needsRegion = currentSettings.translationMode.contains("区域")
        if (needsRegion && selectedRect == null) {
            runCatching {
                overlay.showSelectionOverlay(
                    onConfirm = { rect, w, h ->
                        selectedRect = rect
                        selectedRectBaseWidth = w.coerceAtLeast(1).toFloat()
                        selectedRectBaseHeight = h.coerceAtLeast(1).toFloat()
                        // Remove busy check to ensure auto-trigger works even if state is slightly desynced
                        busy = true
                        scope.launch {
                            try {
                                runSingleOnce()
                            } finally {
                                busy = false
                            }
                        }
                    },
                    onCancel = {
                        runCatching { overlay.showStatusOverlay("已取消区域选择") }
                    }
                )
            }
            return
        }

        runCatching { overlay.setFloatingBallLoading(true) }
        try {
            lastOcrText = ""
            val bitmap = captureCleanFrame()
            if (bitmap == null) {
            if (currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)) {
                    runCatching { overlay.showTextOverlay() }
                    runCatching { overlay.updateTextOverlay("未截取到画面") }
                }
                return
            }
            if (currentSettings.resultMode == "独立窗口" || isVisualEngineActive(currentSettings)) {
                runCatching { overlay.showTextOverlay() }
                // 如果是手动+积极追赶模式，不要用“翻译中”覆盖旧译文
                val isSubtitle = currentSettings.autoSpeedMode == "字幕"
                val isSingle = currentSettings.translationMode.startsWith("单次")
                if (!(isSingle && isSubtitle)) {
                    runCatching { overlay.showStatusOverlay("翻译中") }
                }
            }
            processFrame(bitmap)
        } finally {
            runCatching { overlay.setFloatingBallLoading(false) }
        }
    }



    private fun extractProjectionResult(intent: Intent): Pair<Int, Intent?> {
        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0).takeIf { it != 0 } ?: CapturePermissionStore.resultCode
        val data = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
        } ?: CapturePermissionStore.data
        return code to data
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, "屏幕翻译", NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(channel)
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("MyT")
            .setContentText(status)
            .setOngoing(true)
            .build()
    }

    private fun startInForeground(status: String) {
        val notification = buildNotification(status)
        if (Build.VERSION.SDK_INT >= 29) {
            val type = runCatching {
                ServiceInfo::class.java
                    .getField("FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION")
                    .getInt(null)
            }.getOrDefault(0)
            runCatching {
                Service::class.java
                    .getMethod(
                        "startForeground",
                        Int::class.javaPrimitiveType,
                        Notification::class.java,
                        Int::class.javaPrimitiveType
                    )
                    .invoke(this, NOTIFICATION_ID, notification, type)
            }.getOrElse {
                startForeground(NOTIFICATION_ID, notification)
            }
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification(status: String) {
        val mgr = getSystemService(NotificationManager::class.java)
        mgr.notify(NOTIFICATION_ID, buildNotification(status))
    }



    private suspend fun buildContentOverlayItems(
    blocks: List<TextBlock>,
    sourceLanguage: String?,
    targetLanguage: String,
    sameLang: Boolean,
    settings: SettingsState,
    targetWidth: Int,
    targetHeight: Int,
    bitmapWidth: Int,
    bitmapHeight: Int,
    offsetX: Int = 0,
    offsetY: Int = 0
): List<OverlayController.ContentOverlayItem> {
    val scaleX = if (bitmapWidth > 0) targetWidth.toFloat() / bitmapWidth else 1f
    val scaleY = if (bitmapHeight > 0) targetHeight.toFloat() / bitmapHeight else 1f
    
    // 1. Filter invalid blocks
    val rawBlocks = blocks
        .asSequence()
        .mapNotNull { b ->
            val t = b.text.trim()
            if (t.isEmpty()) return@mapNotNull null
            val r = RectF(b.bounds)
            if (r.width() <= 1f || r.height() <= 1f) return@mapNotNull null
            b.copy(text = t, bounds = r)
        }
        .toList()

    // 2. Sort blocks if manga mode, but do not merge them
    val mergedBlocks = (if (settings.isMangaMode) sortBlocksForManga(rawBlocks) else rawBlocks)
        .take(50) // Safety limit

    if (mergedBlocks.isEmpty()) return emptyList()

    val items = ArrayList<OverlayController.ContentOverlayItem>(mergedBlocks.size)
    for (b in mergedBlocks) {
        val rect = RectF(b.bounds)
        // Log original
        // android.util.Log.d("TranslationService", "Overlay Item '${b.text}': Original=$rect")
        
        rect.left *= scaleX
        rect.right *= scaleX
        rect.top *= scaleY
        rect.bottom *= scaleY
        // android.util.Log.d("TranslationService", "  -> Scaled (X=$scaleX, Y=$scaleY): $rect")
        
        // 减去 View 在屏幕上的偏移，转换为 View 坐标系
        rect.left -= offsetX
        rect.right -= offsetX
        rect.top -= offsetY
        rect.bottom -= offsetY
        // android.util.Log.d("TranslationService", "  -> Offset (X=-$offsetX, Y=-$offsetY): $rect")
        
        val translated = if (sameLang) {
            b.text
        } else {
            engineManager.translateStrict(
                text = b.text,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                settings = settings
            )
        }
        if (translated != null && translated.isNotBlank()) {
            // 采纳建议：不再区分竖排/横排，统一使用自适应渲染。
            // 文本会根据 OCR 提供的框体形状自动换行：窄框自动成列（模拟竖排），宽框正常横排。
            items.add(OverlayController.ContentOverlayItem(bounds = rect, text = translated, isVertical = false))
        }
    }
    return items
}

private fun getRealScreenSize(): Pair<Int, Int> {
    val wm = getSystemService(WindowManager::class.java)
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val metrics = wm.maximumWindowMetrics
        val bounds = metrics.bounds
        bounds.width() to bounds.height()
    } else {
        @Suppress("DEPRECATION")
        val display = wm.defaultDisplay
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        metrics.widthPixels to metrics.heightPixels
    }
}



    override fun onDestroy() {
        TranslationServiceState.setRunning(false)
        stopped = true
        busy = false
        
        // 立即取消所有协程，防止翻译结果在app关闭后显示
        captureJob?.cancel()
        captureJob = null
        autoDebounceJob?.cancel()
        autoDebounceJob = null
        scope.cancel()  // 先取消所有协程
        
        // 停止内存监控
        memoryMonitor.stop()
        
        // 立即隐藏所有悬浮窗，防止翻译结果显示
        runCatching { overlay.hideAll() }
        
        // 停止截屏
        runCatching { capturer.stop() }
        
        // 清理资源（使用 runBlocking 同步执行）
        runCatching { 
            kotlinx.coroutines.runBlocking {
                ocrEnginePool.clear()
                ResourceManager.clearBitmaps()
            }
        }
        
        // 执行所有注册的清理回调
        ResourceManager.cleanup()
        
        super.onDestroy()
    }
}

private fun buildEngines(context: Context, settings: SettingsState): Map<String, TranslationEngine> {
    val engines = LinkedHashMap<String, TranslationEngine>()
    
    // 使用共享的 HTTP 客户端池
    val extendedClient = HttpClientPool.standardClient

    engines["微软离线"] = OfflineDictionaryEngine(context)
    engines["谷歌翻译（免费）"] = GoogleFreeEngine(extendedClient)
    engines["Bing翻译（免费）"] = BingFreeEngine(extendedClient)
    settings.apiConfigs.forEach { cfg ->
        engines[cfg.name] = if (cfg.type == "microsoft") {
            MicrosoftTranslationEngine(cfg)
        } else {
            OnlineApiEngine(cfg, extendedClient)
        }
    }
    return engines
}

private fun calculateImageHash(bitmap: Bitmap): LongArray {
    // Downscale to 32x32 to ignore minor noise but capture text layout structure
    val scaled = Bitmap.createScaledBitmap(bitmap, 32, 32, true)
    val pixels = IntArray(32 * 32)
    scaled.getPixels(pixels, 0, 32, 0, 0, 32, 32)
    ResourceManager.recycleBitmap(scaled)
    
    val grays = LongArray(32 * 32)
    for (i in pixels.indices) {
        val c = pixels[i]
        // Weighted grayscale conversion
        val g = (android.graphics.Color.red(c) * 0.299 + android.graphics.Color.green(c) * 0.587 + android.graphics.Color.blue(c) * 0.114).toLong()
        grays[i] = g
    }
    return grays
}

private fun calculateDifference(pixels1: LongArray, pixels2: LongArray): Float {
    if (pixels1.size != pixels2.size) return 1.0f
    var diffSum = 0L
    for (i in pixels1.indices) {
        diffSum += kotlin.math.abs(pixels1[i] - pixels2[i])
    }
    // Normalize difference (0.0 - 1.0) relative to max possible difference (255 * count)
    return diffSum.toFloat() / (pixels1.size * 255f)
}
