package com.example.mytransl.data.translation.engines

import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.domain.translation.TranslationEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder

class GoogleFreeEngine(
    private val client: OkHttpClient = OkHttpClient()
) : TranslationEngine {
    override val id: String = "谷歌翻译（免费）"

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String = withContext(Dispatchers.IO) {
        val sl = mapLanguage(sourceLanguage)
        val tl = mapLanguage(targetLanguage)

        val encodedText = URLEncoder.encode(text, "UTF-8")
        val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sl&tl=$tl&dt=t&q=$encodedText"

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/96.0.4664.45 Safari/537.36")
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Google请求失败: ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty()
            // Response format: [[[ "translation", "original", null, null, 10 ], ...], ...]
            val json = runCatching { JSONArray(raw) }.getOrNull() 
                ?: throw IllegalStateException("无效的响应格式")
                
            val sentences = json.optJSONArray(0) 
            if (sentences == null || sentences.length() == 0) {
                 return@use text // Fallback to original if parse fails but request succeeded
            }
            
            val sb = StringBuilder()
            for (i in 0 until sentences.length()) {
                val sentence = sentences.optJSONArray(i)
                if (sentence != null && sentence.length() > 0) {
                    val part = sentence.optString(0, "")
                    if (part != "null") sb.append(part)
                }
            }
            sb.toString().trim()
        }
    }

    private fun mapLanguage(lang: String?): String {
        return when (lang) {
            "中文" -> "zh-CN"
            "英语" -> "en"
            "日语" -> "ja"
            "韩语" -> "ko"
            "法语" -> "fr"
            "德语" -> "de"
            "西班牙语" -> "es"
            "俄语" -> "ru"
            "自动检测", null -> "auto"
            else -> "auto"
        }
    }
}
