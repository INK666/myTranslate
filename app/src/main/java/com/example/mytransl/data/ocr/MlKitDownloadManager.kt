package com.example.mytransl.data.ocr

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object MlKitDownloadManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val modelManager = RemoteModelManager.getInstance()

    val requiredModels = listOf(
        "中文" to TranslateRemoteModel.Builder(TranslateLanguage.CHINESE).build(),
        "英语" to TranslateRemoteModel.Builder(TranslateLanguage.ENGLISH).build(),
        "日语" to TranslateRemoteModel.Builder(TranslateLanguage.JAPANESE).build(),
        "韩语" to TranslateRemoteModel.Builder(TranslateLanguage.KOREAN).build(),
        "法语" to TranslateRemoteModel.Builder(TranslateLanguage.FRENCH).build(),
        "俄语" to TranslateRemoteModel.Builder(TranslateLanguage.RUSSIAN).build(),
        "德语" to TranslateRemoteModel.Builder(TranslateLanguage.GERMAN).build()
    )

    private val _downloading = MutableStateFlow(false)
    val downloading = _downloading.asStateFlow()

    private val _progressText = MutableStateFlow<String?>(null)
    val progressText = _progressText.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _downloadedModels = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val downloadedModels = _downloadedModels.asStateFlow()

    init {
        checkStatus()
    }

    fun checkStatus() {
        scope.launch {
            updateStatus()
        }
    }

    private suspend fun updateStatus() {
        val statuses = buildMap {
            for ((label, model) in requiredModels) {
                put(label, runCatching { modelManager.isModelDownloaded(model).await() }.getOrDefault(false))
            }
        }
        _downloadedModels.value = statuses
    }

    fun startDownload() {
        if (_downloading.value) return

        scope.launch {
            _downloading.value = true
            _progressText.value = "准备下载..."
            _error.value = null

            runCatching {
                val conditions = DownloadConditions.Builder().build()
                updateStatus() // Check current status first
                
                val current = _downloadedModels.value
                val missing = requiredModels.filter { (label, _) -> current[label] != true }

                for ((index, pair) in missing.withIndex()) {
                    val (label, model) = pair
                    _progressText.value = "下载中：$label（${index + 1}/${missing.size}）"
                    modelManager.download(model, conditions).await()
                    
                    // Update status for the downloaded model immediately
                    _downloadedModels.value = _downloadedModels.value.toMutableMap().apply { put(label, true) }
                }
            }.onFailure { e ->
                _error.value = e.message ?: e.javaClass.simpleName
            }

            // Final check
            updateStatus()
            _downloading.value = false
            _progressText.value = null
        }
    }

    fun deleteModels() {
        if (_downloading.value) return

        scope.launch {
            _downloading.value = true
            _progressText.value = "删除中..."
            _error.value = null

            runCatching {
                updateStatus()
                val current = _downloadedModels.value
                for ((label, model) in requiredModels) {
                    if (current[label] == true) {
                        modelManager.deleteDownloadedModel(model).await()
                    }
                }
            }.onFailure { e ->
                _error.value = e.message ?: e.javaClass.simpleName
            }

            updateStatus()
            _downloading.value = false
            _progressText.value = null
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { result ->
            if (cont.isActive) cont.resume(result)
        }
        addOnFailureListener { e ->
            if (cont.isActive) cont.resumeWithException(e)
        }
        addOnCanceledListener {
            cont.cancel()
        }
    }
}
