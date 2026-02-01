package com.example.mytransl.system.resource

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 内存监控器 - 定期检查内存使用情况，在内存压力大时触发清理
 */
class MemoryMonitor(
    private val scope: CoroutineScope,
    private val checkIntervalMs: Long = 30_000L, // 30秒检查一次
    private val warningThreshold: Int = 80, // 80% 内存使用率触发警告
    private val criticalThreshold: Int = 90, // 90% 触发强制清理
    private val onMemoryWarning: (ResourceManager.MemoryInfo) -> Unit = {},
    private val onMemoryCritical: (ResourceManager.MemoryInfo) -> Unit = {}
) {
    private var monitorJob: Job? = null
    private var isMonitoring = false
    
    companion object {
        private const val TAG = "MemoryMonitor"
    }
    
    /**
     * 开始监控
     */
    fun start() {
        if (isMonitoring) {
            Log.d(TAG, "Monitor already running")
            return
        }
        
        isMonitoring = true
        monitorJob = scope.launch {
            Log.d(TAG, "Memory monitor started")
            while (isActive && isMonitoring) {
                checkMemory()
                delay(checkIntervalMs)
            }
        }
    }
    
    /**
     * 停止监控
     */
    fun stop() {
        isMonitoring = false
        monitorJob?.cancel()
        monitorJob = null
        Log.d(TAG, "Memory monitor stopped")
    }
    
    /**
     * 检查内存使用情况
     */
    private fun checkMemory() {
        val memInfo = ResourceManager.getMemoryInfo()
        
        Log.d(TAG, "Memory usage: ${memInfo.usagePercent}% " +
                "(${memInfo.usedMemory / 1024 / 1024}MB / ${memInfo.maxMemory / 1024 / 1024}MB)")
        
        when {
            memInfo.usagePercent >= criticalThreshold -> {
                Log.w(TAG, "CRITICAL: Memory usage at ${memInfo.usagePercent}%")
                onMemoryCritical(memInfo)
                // 触发强制清理
                scope.launch {
                    ResourceManager.clearBitmaps()
                    System.gc() // 建议 GC 运行
                }
            }
            memInfo.usagePercent >= warningThreshold -> {
                Log.w(TAG, "WARNING: Memory usage at ${memInfo.usagePercent}%")
                onMemoryWarning(memInfo)
            }
        }
    }
    
    /**
     * 立即检查一次内存
     */
    fun checkNow() {
        checkMemory()
    }
}
