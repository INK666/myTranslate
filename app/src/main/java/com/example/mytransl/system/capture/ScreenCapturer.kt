package com.example.mytransl.system.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.example.mytransl.system.permissions.CapturePermissionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ScreenCapturer(
    private val context: Context
) {
    companion object {
        private const val TAG = "ScreenCapturer"
    }
    
    enum class CaptureState {
        IDLE,           // 空闲，无令牌
        ACTIVE,         // 正在捕获
        PAUSED,         // 暂停（有令牌，Surface 已释放）
        WAITING_TOKEN   // 等待令牌释放
    }
    
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val lock = Any()
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var surfaceReleaseTimer: Handler? = null
    private var tokenReleaseTimer: Handler? = null
    
    @Volatile
    private var currentState: CaptureState = CaptureState.IDLE
    
    // 状态变化回调
    var onStateChanged: ((CaptureState) -> Unit)? = null

    fun resize() {
        synchronized(lock) {
            val projection = mediaProjection ?: return
            val (width, height) = captureSize()
            
            // Re-create reader if size changed
            if (imageReader?.width != width || imageReader?.height != height) {
                imageReader?.close()
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
                
                val metrics = context.resources.displayMetrics
                virtualDisplay?.resize(width, height, metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT))
                virtualDisplay?.surface = imageReader?.surface
            }
        }
    }

    fun start(mediaProjection: MediaProjection) {
        Log.d(TAG, "Starting capture with new MediaProjection")
        stopTimers()
        releaseAll()
        val (width, height) = captureSize()
        val metrics = context.resources.displayMetrics

        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.w(TAG, "MediaProjection stopped by system")
                releaseAfterProjectionStop()
            }
        }
        projectionCallback = callback

        mediaProjection.registerCallback(callback, Handler(Looper.getMainLooper()))

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val display = try {
            mediaProjection.createVirtualDisplay(
                "myTransl-capture",
                width,
                height,
                metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT),
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to create VirtualDisplay", t)
            runCatching { mediaProjection.unregisterCallback(callback) }
            runCatching { reader.close() }
            throw t
        }
        synchronized(lock) {
            this.mediaProjection = mediaProjection
            imageReader = reader
            virtualDisplay = display
        }
        
        // 清理初始缓冲区
        flushImageReader(reader)
        
        updateState(CaptureState.ACTIVE)
        Log.i(TAG, "Capture started successfully")
    }

    fun resume(): Boolean {
        Log.d(TAG, "Attempting to resume capture")
        stopTimers()
        
        val projection = synchronized(lock) { mediaProjection }
        if (projection == null) {
            Log.w(TAG, "Resume failed: no MediaProjection token")
            updateState(CaptureState.IDLE)
            return false
        }
        
        val (width, height) = captureSize()
        val metrics = context.resources.displayMetrics
        var reader: ImageReader?
        var display: VirtualDisplay?
        
        synchronized(lock) {
            // 检查或重建 ImageReader
            if (imageReader == null || imageReader?.width != width || imageReader?.height != height) {
                Log.d(TAG, "Recreating ImageReader due to size change")
                imageReader?.close()
                imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            }
            reader = imageReader
            display = virtualDisplay
            
            // 检查 VirtualDisplay 是否有效
            if (display != null && display?.display?.isValid != true) {
                Log.w(TAG, "VirtualDisplay is invalid, will recreate")
                runCatching { display?.release() }
                virtualDisplay = null
                display = null
            }
        }
        
        val finalReader = reader
        if (finalReader == null) {
            Log.e(TAG, "Resume failed: ImageReader is null")
            return false
        }
        
        // 重建或恢复 VirtualDisplay
        if (display == null) {
            Log.d(TAG, "Creating new VirtualDisplay")
            val newDisplay = try {
                projection.createVirtualDisplay(
                    "myTransl-capture",
                    width,
                    height,
                    metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT),
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    finalReader.surface,
                    null,
                    null
                )
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to create VirtualDisplay", e)
                return false
            }
            synchronized(lock) { virtualDisplay = newDisplay }
        } else {
            Log.d(TAG, "Reusing existing VirtualDisplay")
            runCatching { 
                display?.resize(width, height, metrics.densityDpi.coerceAtLeast(DisplayMetrics.DENSITY_DEFAULT))
            }.onFailure {
                Log.w(TAG, "Failed to resize VirtualDisplay", it)
            }
            runCatching { 
                display?.surface = finalReader.surface 
            }.onFailure {
                Log.e(TAG, "Failed to set surface", it)
                return false
            }
        }
        
        // 清理残留帧
        flushImageReader(finalReader)
        
        updateState(CaptureState.ACTIVE)
        Log.i(TAG, "Resume successful")
        return true
    }

    private fun captureSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    suspend fun capture(): Bitmap? = withContext(Dispatchers.Default) {
        // 在锁内同时检查状态和获取 reader 引用，防止并发释放
        val reader = synchronized(lock) {
            // 只在 ACTIVE 状态下允许捕获
            if (currentState != CaptureState.ACTIVE) {
                Log.d(TAG, "Capture skipped: state is $currentState")
                return@withContext null
            }
            imageReader
        } ?: return@withContext null
        
        // 在锁外获取图像，避免长时间持有锁
        val image = runCatching { 
            reader.acquireLatestImage() 
        }.getOrElse { e ->
            Log.w(TAG, "Failed to acquire image: ${e.message}")
            return@withContext null
        } ?: return@withContext null

        runCatching {
            image.use { img ->
                val plane = img.planes.firstOrNull() ?: return@use null
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * img.width

                // 优化：如果没有 padding（大多数标准分辨率），直接拷贝，避免二次创建 Bitmap
                if (rowPadding == 0) {
                    val bitmap = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
                    bitmap.copyPixelsFromBuffer(buffer)
                    return@use bitmap
                }

                // 处理有 padding 的情况（某些设备或特定分辨率会强制字节对齐）
                val bitmap = Bitmap.createBitmap(
                    img.width + rowPadding / pixelStride,
                    img.height,
                    Bitmap.Config.ARGB_8888
                )
                bitmap.copyPixelsFromBuffer(buffer)
                Bitmap.createBitmap(bitmap, 0, 0, img.width, img.height).also {
                    bitmap.recycle()
                }
            }
        }.getOrElse { e ->
            Log.w(TAG, "Failed to create bitmap: ${e.message}")
            null
        }
    }

    fun stop() {
        Log.d(TAG, "Stopping capture, scheduling delayed release")
        stopTimers()
        
        val projection = synchronized(lock) { 
            // 先更新状态，防止新的 capture 调用
            if (currentState == CaptureState.ACTIVE) {
                updateState(CaptureState.PAUSED)
            }
            mediaProjection 
        } ?: run {
            Log.d(TAG, "No projection to stop, cleaning up readers")
            releaseReaders()
            updateState(CaptureState.IDLE)
            return
        }
        
        // 1分钟后释放 Surface
        surfaceReleaseTimer = Handler(Looper.getMainLooper())
        surfaceReleaseTimer?.postDelayed({
            Log.d(TAG, "Releasing surface (1 minute elapsed)")
            synchronized(lock) {
                virtualDisplay?.surface = null
            }
            updateState(CaptureState.WAITING_TOKEN)
        }, 60_000L)
        
        // 3分钟后释放令牌
        tokenReleaseTimer = Handler(Looper.getMainLooper())
        tokenReleaseTimer?.postDelayed({
            Log.d(TAG, "Releasing MediaProjection token (3 minutes elapsed)")
            val toRelease: VirtualDisplay?
            val toClose: ImageReader?
            val callback: MediaProjection.Callback?
            synchronized(lock) {
                toRelease = virtualDisplay
                toClose = imageReader
                callback = projectionCallback
                virtualDisplay = null
                imageReader = null
                mediaProjection = null
                projectionCallback = null
            }
            runCatching { toRelease?.release() }
            runCatching { toClose?.close() }
            if (callback != null) {
                runCatching { projection.unregisterCallback(callback) }
            }
            runCatching { projection.stop() }
            CapturePermissionStore.clear()
            updateState(CaptureState.IDLE)
            Log.i(TAG, "MediaProjection fully released")
        }, 180_000L)
        
        Log.i(TAG, "Surface will release in 60s, token in 180s")
    }

    private fun releaseAfterProjectionStop() {
        Log.w(TAG, "Releasing after system stopped projection")
        stopTimers()
        val toRelease: VirtualDisplay?
        val toClose: ImageReader?
        synchronized(lock) {
            toRelease = virtualDisplay
            toClose = imageReader
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            projectionCallback = null
        }
        runCatching { toRelease?.release() }
        runCatching { toClose?.close() }
        CapturePermissionStore.clear()
        updateState(CaptureState.IDLE)
        Log.i(TAG, "Cleanup complete after system stop")
    }

    fun stopTimers() {
        surfaceReleaseTimer?.removeCallbacksAndMessages(null)
        tokenReleaseTimer?.removeCallbacksAndMessages(null)
        surfaceReleaseTimer = null
        tokenReleaseTimer = null
    }

    fun hasProjection(): Boolean {
        return synchronized(lock) { mediaProjection != null }
    }
    
    fun getState(): CaptureState {
        return currentState
    }
    
    private fun updateState(newState: CaptureState) {
        if (currentState != newState) {
            Log.d(TAG, "State changed: $currentState -> $newState")
            currentState = newState
            onStateChanged?.invoke(newState)
        }
    }

    private fun releaseAll() {
        // 先更新状态，防止并发的 capture 调用
        updateState(CaptureState.IDLE)
        
        val toRelease: VirtualDisplay?
        val toClose: ImageReader?
        val projection: MediaProjection?
        val callback: MediaProjection.Callback?
        synchronized(lock) {
            toRelease = virtualDisplay
            toClose = imageReader
            projection = mediaProjection
            callback = projectionCallback
            virtualDisplay = null
            imageReader = null
            mediaProjection = null
            projectionCallback = null
        }
        runCatching { toRelease?.release() }
        runCatching { toClose?.close() }
        if (projection != null && callback != null) {
            runCatching { projection.unregisterCallback(callback) }
        }
        runCatching { projection?.stop() }
    }

    private fun releaseReaders() {
        val toRelease: VirtualDisplay?
        val toClose: ImageReader?
        synchronized(lock) {
            toRelease = virtualDisplay
            toClose = imageReader
            virtualDisplay = null
            imageReader = null
        }
        runCatching { toRelease?.release() }
        runCatching { toClose?.close() }
    }

    private fun flushImageReader(reader: ImageReader) {
        while (true) {
            val image = runCatching { reader.acquireNextImage() }.getOrNull() ?: break
            runCatching { image.close() }
        }
    }
}
