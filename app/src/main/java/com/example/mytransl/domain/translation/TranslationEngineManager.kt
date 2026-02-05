package com.example.mytransl.domain.translation

import com.example.mytransl.data.settings.SettingsState

class TranslationEngineManager(
    private val enginesById: Map<String, TranslationEngine>,
    private val cache: TranslationCache,
    private val onValidationFailed: ((String) -> Unit)? = null
) {
    suspend fun translateStrict(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String? {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return null

        val engineId = settings.defaultEngine
        val cacheKey = buildString {
            append(engineId)
            append(":")
            append(sourceLanguage ?: "auto")
            append("->")
            append(targetLanguage)
            append(":")
            append(normalizedText)
        }

        if (settings.cacheEnabled) {
            val cached = cache.get(cacheKey)
            if (cached != null) return cached
        }

        val engine = enginesById[engineId] ?: return null
        var exception: Throwable? = null
        val translated = try {
            engine.translate(
                text = normalizedText,
                sourceLanguage = sourceLanguage,
                targetLanguage = targetLanguage,
                settings = settings
            )
        } catch (e: Throwable) {
            exception = e
            null
        }

        if (translated == null) {
            onValidationFailed?.invoke(exception?.message ?: "引擎返回空或发生错误")
            return null
        }

        val normalizedResult = translated.trim()
        val noTranslation = normalizedResult.isEmpty() ||
            normalizedResult == normalizedText ||
            normalizedResult.startsWith("[")
        if (noTranslation) {
            val reason = if (normalizedResult.isEmpty()) "结果为空" 
                         else if (normalizedResult == normalizedText) "结果与原文相同" 
                         else "格式校验失败"
            onValidationFailed?.invoke(reason)
            return null
        }

        if (settings.cacheEnabled) {
            cache.put(cacheKey, translated)
        }
        return translated
    }

    suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String {
        val normalizedText = text.trim()
        if (normalizedText.isEmpty()) return ""
        return translateStrict(
            text = normalizedText,
            sourceLanguage = sourceLanguage,
            targetLanguage = targetLanguage,
            settings = settings
        ) ?: ""
    }

    suspend fun translateBatch(
        texts: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): List<String> {
        val normalizedTexts = texts.map { it.trim() }
        if (normalizedTexts.isEmpty()) return emptyList()

        val engineId = settings.defaultEngine
        val engine = enginesById[engineId] ?: return normalizedTexts

        val results = MutableList(normalizedTexts.size) { "" }
        val indicesToTranslate = mutableListOf<Int>()
        val textsToTranslate = mutableListOf<String>()

        normalizedTexts.forEachIndexed { index, text ->
            if (text.isEmpty()) {
                results[index] = ""
                return@forEachIndexed
            }

            val cacheKey = if (settings.cacheEnabled) {
                buildString {
                    append(engineId)
                    append(":")
                    append(sourceLanguage ?: "auto")
                    append("->")
                    append(targetLanguage)
                    append(":")
                    append(text)
                }
            } else null

            val cached = if (cacheKey != null) cache.get(cacheKey) else null
            if (cached != null) {
                results[index] = cached
            } else {
                indicesToTranslate.add(index)
                textsToTranslate.add(text)
            }
        }

        if (indicesToTranslate.isEmpty()) {
            return results
        }

        try {
            val translatedBatch = engine.translateBatch(
                textsToTranslate,
                sourceLanguage,
                targetLanguage,
                settings
            )

            translatedBatch.forEachIndexed { i, translated ->
                val originalIndex = indicesToTranslate[i]
                val originalText = textsToTranslate[i]
                
                val cleanResult = translated.trim()
                val isValid = cleanResult.isNotEmpty() && 
                              cleanResult != originalText && 
                              !cleanResult.startsWith("[")
                
                val finalResult = if (isValid) cleanResult else ""
                
                results[originalIndex] = finalResult

                if (isValid && settings.cacheEnabled) {
                    val cacheKey = buildString {
                        append(engineId)
                        append(":")
                        append(sourceLanguage ?: "auto")
                        append("->")
                        append(targetLanguage)
                        append(":")
                        append(originalText)
                    }
                    cache.put(cacheKey, finalResult)
                }
            }
        } catch (e: Throwable) {
            onValidationFailed?.invoke(e.message ?: "批量翻译失败")
            indicesToTranslate.forEachIndexed { i, originalIndex ->
                if (results[originalIndex].isEmpty()) {
                    results[originalIndex] = ""
                }
            }
        }

        return results
    }
}
