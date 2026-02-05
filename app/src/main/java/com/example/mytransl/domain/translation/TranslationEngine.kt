package com.example.mytransl.domain.translation

import com.example.mytransl.data.settings.SettingsState

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
    ): List<String> {
        return batch.map { text ->
            translate(text, sourceLanguage, targetLanguage, settings)
        }
    }
}
