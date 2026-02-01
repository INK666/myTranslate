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
}
