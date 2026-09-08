package com.example.mytransl.domain.translation

import com.example.mytransl.data.settings.SettingsState

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

interface TranslationEngine {
    val id: String
    suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String

    suspend fun translateBatch(
        batch: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): List<String> = coroutineScope {
        if (batch.isEmpty()) return@coroutineScope emptyList()
        val semaphore = Semaphore(5)
        batch.map { text ->
            async(Dispatchers.IO) {
                if (text.isBlank()) ""
                else {
                    semaphore.withPermit {
                        runCatching {
                            translate(text, sourceLanguage, targetLanguage, settings)
                        }.getOrDefault("")
                    }
                }
            }
        }.awaitAll()
    }
}
