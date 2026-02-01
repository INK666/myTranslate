package com.example.mytransl.system.resource

import android.util.Log
import com.example.mytransl.domain.ocr.PreferredLanguageAwareOcrEngine
import java.lang.ref.WeakReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * OCR 引擎池 - 使用 WeakReference 管理 OCR 引擎实例
 */
class OcrEnginePool {
    private val mutex = Mutex()
    private var currentEngine: WeakReference<PreferredLanguageAwareOcrEngine>? = null
    private var engineType: String = ""
    
    companion object {
        private const val TAG = "OcrEnginePool"
    }
    
    /**
     * 获取或创建 OCR 引擎
     */
    suspend fun getOrCreate(
        type: String,
        factory: () -> PreferredLanguageAwareOcrEngine
    ): PreferredLanguageAwareOcrEngine {
        mutex.withLock {
            // 检查是否需要切换引擎类型
            if (type != engineType) {
                Log.d(TAG, "Engine type changed from $engineType to $type, clearing old engine")
                currentEngine?.get()?.let { releaseEngine(it) }
                currentEngine = null
                engineType = type
            }
            
            // 尝试获取现有引擎
            val existing = currentEngine?.get()
            if (existing != null) {
                Log.d(TAG, "Reusing existing $type engine")
                return existing
            }
            
            // 创建新引擎
            Log.d(TAG, "Creating new $type engine")
            val newEngine = factory()
            currentEngine = WeakReference(newEngine)
            return newEngine
        }
    }
    
    /**
     * 释放引擎资源（如果引擎有清理方法）
     */
    private fun releaseEngine(engine: PreferredLanguageAwareOcrEngine) {
        runCatching {
            // 如果 OCR 引擎有清理方法，在这里调用
            Log.d(TAG, "Released OCR engine")
        }.onFailure {
            Log.w(TAG, "Failed to release engine: ${it.message}")
        }
    }
    
    /**
     * 清理引擎池
     */
    suspend fun clear() {
        mutex.withLock {
            currentEngine?.get()?.let { releaseEngine(it) }
            currentEngine = null
            engineType = ""
            Log.d(TAG, "Engine pool cleared")
        }
    }
}
