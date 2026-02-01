package com.example.mytransl.data.translation.engines

import com.example.mytransl.data.settings.ApiConfig
import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.domain.translation.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class MicrosoftTranslationEngine(
    private val config: ApiConfig,
    private val client: OkHttpClient = OkHttpClient()
) : TranslationEngine {
    override val id: String = config.name

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String = withContext(Dispatchers.IO) {
        val subscriptionKey = config.apiKey.trim()
        if (subscriptionKey.isEmpty()) {
            throw IllegalArgumentException("请填写微软翻译订阅密钥")
        }

        val region = config.model.trim()
        if (region.isEmpty()) {
            throw IllegalArgumentException("请在模型字段中填写订阅区域 (Region)，例如 eastasia")
        }

        // Use custom endpoint if provided, else default
        val endpoint = config.baseUrl.trim().ifEmpty { "https://api.cognitive.microsofttranslator.com" }.trimEnd('/')
        val url = "$endpoint/translate?api-version=3.0"

        val from = mapLanguageCode(sourceLanguage)
        val to = mapLanguageCode(targetLanguage) ?: "en"
        
        val fullUrl = buildString {
            append(url)
            if (!from.isNullOrEmpty()) {
                append("&from=").append(from)
            }
            append("&to=").append(to)
        }

        val payload = JSONArray().put(JSONObject().put("Text", text)).toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val request = Request.Builder()
            .url(fullUrl)
            .post(body)
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .header("Ocp-Apim-Subscription-Region", region)
            .build()

        client.newCall(request).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $raw")
            }

            val jsonArray = runCatching { JSONArray(raw) }.getOrNull()
            if (jsonArray == null || jsonArray.length() == 0) {
                throw IllegalStateException("无效的响应格式: $raw")
            }

            val translations = jsonArray.optJSONObject(0)?.optJSONArray("translations")
            val translatedText = translations?.optJSONObject(0)?.optString("text")

            if (translatedText.isNullOrBlank()) {
                throw IllegalStateException("未找到翻译结果")
            }
            translatedText
        }
    }

    private fun mapLanguageCode(lang: String?): String? {
        if (lang == null || lang == "自动检测") return null
        return when (lang) {
            "中文" -> "zh-Hans"
            "英语" -> "en"
            "日语" -> "ja"
            "韩语" -> "ko"
            "法语" -> "fr"
            "德语" -> "de"
            "西班牙语" -> "es"
            "俄语" -> "ru"
            else -> "en" // Default fallback, though ideally should support more
        }
    }
}
