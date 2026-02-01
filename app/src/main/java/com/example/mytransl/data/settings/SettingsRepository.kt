package com.example.mytransl.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class ApiConfig(
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val modelOptions: List<String> = emptyList(),
    val prompt: String = "",
    val type: String = "openai", // "openai" or "microsoft"
    val isVisualModel: Boolean = false
)

data class SettingsState(
    val sourceLanguage: String = "日语",
    val targetLanguage: String = "中文",
    val defaultEngine: String = "微软离线",
    val ocrEngine: String = "PaddleOCR",
    val translationMode: String = "单次全屏",
    val resultMode: String = "覆盖层",
    val apiConfigs: List<ApiConfig> = emptyList(),
    val cacheEnabled: Boolean = true,
    val cacheSize: Int = 256,
    val autoOcrIntervalMs: Int = 3000,
    val autoSpeedMode: String = "字幕", // "正常" or "字幕"
    val beastChars: String = "嗷呜啊~",
    val isMangaMode: Boolean = false
)

class SettingsRepository(
    private val context: Context
) {
    private object Keys {
        val SourceLanguage = stringPreferencesKey("source_language")
        val TargetLanguage = stringPreferencesKey("target_language")
        val DefaultEngine = stringPreferencesKey("default_engine")
        val OcrEngine = stringPreferencesKey("ocr_engine")
        val TranslationMode = stringPreferencesKey("translation_mode")
        val ResultMode = stringPreferencesKey("result_mode")
        val ApiConfigs = stringPreferencesKey("api_configs")
        val CacheEnabled = booleanPreferencesKey("cache_enabled")
        val CacheSize = intPreferencesKey("cache_size")
        val AutoOcrIntervalMs = intPreferencesKey("auto_ocr_interval_ms")
        val AutoSpeedMode = stringPreferencesKey("auto_speed_mode")
        val BeastChars = stringPreferencesKey("beast_chars")
        val IsMangaMode = booleanPreferencesKey("is_manga_mode")
    }

    val settings: Flow<SettingsState> = context.settingsDataStore.data.map { prefs ->
        val apiConfigs = parseApiConfigs(prefs[Keys.ApiConfigs])

        val availableEngines = buildList {
            add("微软离线")
            add("谷歌翻译（免费）")
            addAll(apiConfigs.map { it.name })
        }.distinct()

        val normalizedDefault = (prefs[Keys.DefaultEngine] ?: SettingsState().defaultEngine)
            .takeIf { it in availableEngines }
            ?: "微软离线"

        SettingsState(
            sourceLanguage = prefs[Keys.SourceLanguage] ?: SettingsState().sourceLanguage,
            targetLanguage = prefs[Keys.TargetLanguage] ?: SettingsState().targetLanguage,
            defaultEngine = normalizedDefault,
            ocrEngine = prefs[Keys.OcrEngine] ?: SettingsState().ocrEngine,
            translationMode = prefs[Keys.TranslationMode] ?: SettingsState().translationMode,
            resultMode = prefs[Keys.ResultMode] ?: SettingsState().resultMode,
            apiConfigs = apiConfigs,
            cacheEnabled = prefs[Keys.CacheEnabled] ?: SettingsState().cacheEnabled,
            cacheSize = prefs[Keys.CacheSize] ?: SettingsState().cacheSize,
            autoOcrIntervalMs = (prefs[Keys.AutoOcrIntervalMs] ?: SettingsState().autoOcrIntervalMs).coerceIn(300, 60_000),
            autoSpeedMode = prefs[Keys.AutoSpeedMode] ?: SettingsState().autoSpeedMode,
            beastChars = prefs[Keys.BeastChars] ?: SettingsState().beastChars,
            isMangaMode = prefs[Keys.IsMangaMode] ?: SettingsState().isMangaMode
        )
    }

    suspend fun saveSettings(state: SettingsState) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SourceLanguage] = state.sourceLanguage
            prefs[Keys.TargetLanguage] = state.targetLanguage
            prefs[Keys.DefaultEngine] = state.defaultEngine
            prefs[Keys.OcrEngine] = state.ocrEngine
            prefs[Keys.TranslationMode] = state.translationMode
            prefs[Keys.ResultMode] = state.resultMode
            prefs[Keys.ApiConfigs] = serializeApiConfigs(state.apiConfigs)
            prefs[Keys.CacheEnabled] = state.cacheEnabled
            prefs[Keys.CacheSize] = state.cacheSize
            prefs[Keys.AutoOcrIntervalMs] = state.autoOcrIntervalMs.coerceIn(300, 60_000)
            prefs[Keys.AutoSpeedMode] = state.autoSpeedMode
            prefs[Keys.BeastChars] = state.beastChars
            prefs[Keys.IsMangaMode] = state.isMangaMode
        }
    }
}

private fun parseApiConfigs(raw: String?): List<ApiConfig> {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return emptyList()
    return runCatching {
        val arr = JSONArray(text)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name").trim()
                val baseUrl = obj.optString("baseUrl").trim()
                val apiKey = obj.optString("apiKey")
                val model = obj.optString("model").trim()
                val modelsArr = obj.optJSONArray("models")
                val modelOptions = buildList {
                    if (modelsArr != null) {
                        for (j in 0 until modelsArr.length()) {
                            val id = modelsArr.optString(j).trim()
                            if (id.isNotEmpty()) add(id)
                        }
                    }
                }.distinct()
                val prompt = obj.optString("prompt")
                val type = obj.optString("type").trim().ifEmpty { "openai" }
                val isVisualModel = obj.optBoolean("isVisualModel", false)
                if (name.isNotEmpty()) {
                    add(
                        ApiConfig(
                            name = name,
                            baseUrl = baseUrl,
                            apiKey = apiKey,
                            model = model,
                            modelOptions = modelOptions,
                            prompt = prompt,
                            type = type,
                            isVisualModel = isVisualModel
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())
}

private fun serializeApiConfigs(configs: List<ApiConfig>): String {
    val arr = JSONArray()
    configs.forEach { cfg ->
        val obj = JSONObject()
            .put("name", cfg.name)
            .put("baseUrl", cfg.baseUrl)
            .put("apiKey", cfg.apiKey)
            .put("model", cfg.model)
            .put("models", JSONArray(cfg.modelOptions))
            .put("prompt", cfg.prompt)
            .put("type", cfg.type)
            .put("isVisualModel", cfg.isVisualModel)
        arr.put(obj)
    }
    return arr.toString()
}
