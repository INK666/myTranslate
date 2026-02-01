package com.example.mytransl.system.resource

import android.util.Log
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * HTTP 客户端池 - 复用 OkHttpClient 实例，避免重复创建
 */
object HttpClientPool {
    private const val TAG = "HttpClientPool"
    
    // 标准客户端（180秒超时）
    val standardClient: OkHttpClient by lazy {
        Log.d(TAG, "Creating standard HTTP client")
        OkHttpClient.Builder()
            .connectTimeout(180, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .build()
    }
    
    // 快速客户端（10秒超时）
    val fastClient: OkHttpClient by lazy {
        Log.d(TAG, "Creating fast HTTP client")
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
    
    /**
     * 清理所有连接池
     */
    fun cleanup() {
        runCatching {
            standardClient.connectionPool.evictAll()
            standardClient.dispatcher.executorService.shutdown()
            Log.d(TAG, "Standard client cleaned up")
        }
        runCatching {
            fastClient.connectionPool.evictAll()
            fastClient.dispatcher.executorService.shutdown()
            Log.d(TAG, "Fast client cleaned up")
        }
    }
}
