package com.example.mytransl.system.resource

import android.graphics.Bitmap
import android.util.Log
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 资源管理器 - 统一管理应用中的各类资源，防止内存泄漏
 */
object ResourceManager {
    private const val TAG = "ResourceManager"
    
    // Bitmap 资源池
    private val bitmapPool = ConcurrentHashMap<String, WeakReference<Bitmap>>()
    private val bitmapMutex = Mutex()
    
    // 资源清理回调
    private val cleanupCallbacks = mutableListOf<() -> Unit>()
    
    /**
     * 注册 Bitmap 到资源池（用于追踪）
     */
    suspend fun trackBitmap(key: String, bitmap: Bitmap) {
        bitmapMutex.withLock {
            bitmapPool[key] = WeakReference(bitmap)
        }
    }
    
    /**
     * 安全回收 Bitmap
     */
    fun recycleBitmap(bitmap: Bitmap?) {
        if (bitmap == null || bitmap.isRecycled) return
        runCatching {
            bitmap.recycle()
            Log.d(TAG, "Bitmap recycled successfully")
        }.onFailure {
            Log.w(TAG, "Failed to recycle bitmap: ${it.message}")
        }
    }
    
    /**
     * 清理所有追踪的 Bitmap
     */
    suspend fun clearBitmaps() {
        bitmapMutex.withLock {
            bitmapPool.values.forEach { ref ->
                ref.get()?.let { bitmap ->
                    if (!bitmap.isRecycled) {
                        recycleBitmap(bitmap)
                    }
                }
            }
            bitmapPool.clear()
            Log.d(TAG, "All bitmaps cleared")
        }
    }
    
    /**
     * 注册资源清理回调
     */
    fun registerCleanupCallback(callback: () -> Unit) {
        cleanupCallbacks.add(callback)
    }
    
    /**
     * 执行所有清理回调
     */
    fun cleanup() {
        Log.d(TAG, "Starting cleanup, ${cleanupCallbacks.size} callbacks registered")
        cleanupCallbacks.forEach { callback ->
            runCatching {
                callback()
            }.onFailure {
                Log.w(TAG, "Cleanup callback failed: ${it.message}")
            }
        }
        cleanupCallbacks.clear()
    }
    
    /**
     * 获取当前内存使用情况
     */
    fun getMemoryInfo(): MemoryInfo {
        val runtime = Runtime.getRuntime()
        val usedMemory = runtime.totalMemory() - runtime.freeMemory()
        val maxMemory = runtime.maxMemory()
        val availableMemory = maxMemory - usedMemory
        
        return MemoryInfo(
            usedMemory = usedMemory,
            maxMemory = maxMemory,
            availableMemory = availableMemory,
            usagePercent = (usedMemory.toFloat() / maxMemory * 100).toInt()
        )
    }
    
    data class MemoryInfo(
        val usedMemory: Long,
        val maxMemory: Long,
        val availableMemory: Long,
        val usagePercent: Int
    )
}
