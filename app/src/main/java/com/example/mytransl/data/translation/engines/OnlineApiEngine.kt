package com.example.mytransl.data.translation.engines

import android.graphics.Bitmap
import android.util.Base64
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

class OnlineApiEngine(
    private val config: ApiConfig,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()
) : TranslationEngine {
    override val id: String = config.name

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) {
            throw IllegalArgumentException("API URL 为空")
        }

        val model = config.model.trim().ifEmpty { id }
        val source = sourceLanguage?.trim()?.takeIf { it.isNotEmpty() }
        val target = targetLanguage.trim().takeIf { it.isNotEmpty() } ?: "英语"

        val outputConstraint = when (target.trim()) {
            "英语" -> ""
            "中文" -> ""
            else -> "输出语言必须与目标语言一致。"
        }

        val userPrompt = buildString {
            if (source == null) {
                append("把下面内容翻译成")
                append(toPromptLanguage(target))
                append("，只输出")
                append(toPromptLanguage(target))
            } else {
                append("把下面内容从")
                append(toPromptLanguage(source))
                append("翻译成")
                append(toPromptLanguage(target))
                append("，只输出")
                append(toPromptLanguage(target))
            }
            append(text)
        }

        val systemPrompt = """
            # Role：严格翻译执行器
            ## Constraints：
            - 必须执行翻译操作，严禁直接输出原文
            - 确保语序符合译文表达习惯
            - 返回内容只能是目标语言的译文，禁止包含任何原文片段
            ## Workflow：
            1. 接收输入后立即识别为翻译任务
            2. 执行逐句翻译，确保每句都有对应译文
            3. 返回内容前必须检查返回的内容是否只有译文，禁止含有原文和系统提示词
            $outputConstraint
        """.trimIndent()
        val customPrompt = config.prompt.trim().takeIf { it.isNotEmpty() }
        val finalSystemPrompt = buildString {
            append(systemPrompt)
            if (customPrompt != null) {
                append("\n\n")
                append(customPrompt)
            }
        }

        val messagesArray = JSONArray()
        // 1. System Message
        messagesArray.put(
            JSONObject()
                .put("role", "system")
                .put("content", finalSystemPrompt)
        )
        // 2. User Message
        messagesArray.put(
            JSONObject()
                .put("role", "user")
                .put("content", userPrompt)
        )

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0)
            .put("stream", false)
            .put("max_tokens", 1024)
            .toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(resolvedUrl)
            .post(body)

        val apiKey = config.apiKey.trim()
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IllegalStateException("HTTP ${resp.code}: $raw")
                }

                val json = runCatching { JSONObject(raw) }.getOrNull()
                val translation = json
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                if (translation != null) {
                    return@withContext stripThinkTags(translation).trim()
                }

                val translationText = json
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                if (translationText != null) {
                    return@withContext stripThinkTags(translationText).trim()
                }

                val legacyTranslation = json?.optString("translation")?.takeIf { it.isNotBlank() }
                if (legacyTranslation != null) {
                    return@withContext stripThinkTags(legacyTranslation).trim()
                }

                val alt = json?.optString("text")?.takeIf { it.isNotBlank() }
                if (alt != null) {
                    return@withContext stripThinkTags(alt).trim()
                }

                if (raw.isNotBlank()) {
                    return@withContext stripThinkTags(raw)
                }
                throw IllegalStateException("空响应")
            }
        } catch (t: Throwable) {
            throw t
        }
    }

    // 识别图片并直接翻译为目标语言
    suspend fun translateImage(
        image: Bitmap,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) {
            throw IllegalArgumentException("API URL 为空")
        }

        val model = config.model.trim().ifEmpty { id }
        val source = sourceLanguage?.trim()?.takeIf { it.isNotEmpty() }
        val target = targetLanguage.trim().takeIf { it.isNotEmpty() } ?: "英语"

        val outputConstraint = when (target.trim()) {
            "英语" -> " "
            "中文" -> " "
            else -> " "
        }

        val userPrompt = buildString {
            if (source == null) {
                append("识别图片中的文字并翻译成")
                append(toPromptLanguage(target))
                append("，只输出")
                append(toPromptLanguage(target))
            } else {
                append("识别图片中的文字并从")
                append(toPromptLanguage(source))
                append("翻译成")
                append(toPromptLanguage(target))
                append("，只输出")
                append(toPromptLanguage(target))
            }
        }

        val systemPrompt = """
            # Role：严格翻译执行器
            ## Constraints：
            - 必须执行翻译操作，严禁直接输出原文
            - 确保语序符合译文表达习惯
            - 返回内容只能是目标语言的译文，禁止包含任何原文片段
            ## Workflow：
            1. 接收输入后立即识别为翻译任务
            2. 执行逐句翻译，确保每句都有对应译文
            3. 仅当通过所有检查时返回译文
            $outputConstraint
        """.trimIndent()
        val customPrompt = config.prompt.trim().takeIf { it.isNotEmpty() }
        val finalSystemPrompt = buildString {
            append(systemPrompt)
            if (customPrompt != null) {
                append("\n\n")
                append(customPrompt)
            }
        }

        val payload = buildImagePayload(image, model, userPrompt, finalSystemPrompt)
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(resolvedUrl)
            .post(body)

        val apiKey = config.apiKey.trim()
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $raw")
            }

            val json = runCatching { JSONObject(raw) }.getOrNull()
            val translation = json
                ?.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                ?.takeIf { it.isNotBlank() }
            if (translation != null) {
                return@withContext stripThinkTags(translation).trim()
            }

            val translationText = json
                ?.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
            if (translationText != null) {
                return@withContext stripThinkTags(translationText).trim()
            }

            val legacyTranslation = json?.optString("translation")?.takeIf { it.isNotBlank() }
            if (legacyTranslation != null) {
                return@withContext stripThinkTags(legacyTranslation).trim()
            }

            val alt = json?.optString("text")?.takeIf { it.isNotBlank() }
            if (alt != null) {
                return@withContext stripThinkTags(alt).trim()
            }

            if (raw.isNotBlank()) {
                return@withContext stripThinkTags(raw)
            }
            throw IllegalStateException("空响应")
        }
    }
}

// 去除大模型返回的思考标签内容
private fun stripThinkTags(text: String): String {
    return text.replace(Regex("(?is)<think>.*?</think>"), "").trim()
}

// 将图片封装为多模态请求体
private fun buildImagePayload(
    image: Bitmap,
    model: String,
    userPrompt: String,
    systemPrompt: String
): String {
    val base64Image = bitmapToBase64Png(image)
    val messages = JSONArray()
        .put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt)
        )
        .put(
            JSONObject()
                .put("role", "user")
                .put(
                    "content",
                    JSONArray()
                        .put(JSONObject().put("type", "text").put("text", userPrompt))
                        .put(
                            JSONObject()
                                .put("type", "image_url")
                                .put("image_url", JSONObject().put("url", "data:image/png;base64,$base64Image"))
                        )
                )
        )

    return JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("temperature", 0)
        .put("stream", false)
        .put("max_tokens", 1024)
        .toString()
}

// 简易 PNG Base64 编码
private fun bitmapToBase64Png(bitmap: Bitmap): String {
    val stream = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
    val bytes = stream.toByteArray()
    return Base64.encodeToString(bytes, Base64.NO_WRAP)
}

private fun resolveApiUrl(raw: String): String {
    val url = raw.trim()
    if (url.isEmpty()) return ""
    if (url.contains("/chat/completions")) return url

    val noTrailingSlash = url.trimEnd('/')
    if (noTrailingSlash.endsWith("/v1", ignoreCase = true)) {
        return "$noTrailingSlash/chat/completions"
    }
    return "$noTrailingSlash/v1/chat/completions"
}

private fun resolveModelsUrl(raw: String): String {
    val url = raw.trim()
    if (url.isEmpty()) return ""
    if (url.contains("/models")) return url

    val noTrailingSlash = url.trimEnd('/')
    if (noTrailingSlash.endsWith("/v1", ignoreCase = true)) {
        return "$noTrailingSlash/models"
    }
    return "$noTrailingSlash/v1/models"
}

suspend fun fetchOnlineApiModels(
    config: ApiConfig,
    client: OkHttpClient = OkHttpClient()
): List<String> = withContext(Dispatchers.IO) {
    if (config.type == "microsoft") {
        throw IllegalArgumentException("仅 OpenAI 兼容接口支持拉取模型列表")
    }

    val url = resolveModelsUrl(config.baseUrl)
    if (url.isEmpty()) throw IllegalArgumentException("API URL 为空")

    val requestBuilder = Request.Builder().url(url).get()
    val apiKey = config.apiKey.trim()
    if (apiKey.isNotEmpty()) {
        requestBuilder.header("Authorization", "Bearer $apiKey")
    }

    client.newCall(requestBuilder.build()).execute().use { resp ->
        val raw = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) {
            val snippet = raw.replace("\n", " ").replace("\r", " ").take(240)
            throw IllegalStateException("HTTP ${resp.code}：$snippet")
        }

        val json = runCatching { JSONObject(raw) }.getOrNull()
            ?: throw IllegalStateException("响应非 JSON：${raw.replace("\n", " ").replace("\r", " ").take(240)}")
        val data = json.optJSONArray("data") ?: JSONArray()
        val models = buildList {
            for (i in 0 until data.length()) {
                val obj = data.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                if (id.isNotEmpty()) add(id)
            }
        }.distinct().sorted()
        if (models.isEmpty()) throw IllegalStateException("未找到模型列表")
        models
    }
}

suspend fun testOnlineApiConnection(
    config: ApiConfig,
    client: OkHttpClient = OkHttpClient()
): String = withContext(Dispatchers.IO) {
    if (config.type == "microsoft") {
        val subscriptionKey = config.apiKey.trim()
        if (subscriptionKey.isEmpty()) throw IllegalArgumentException("密钥为空")
        val region = config.model.trim()
        if (region.isEmpty()) throw IllegalArgumentException("区域 (Region) 为空")
        
        val endpoint = config.baseUrl.trim().ifEmpty { "https://api.cognitive.microsofttranslator.com" }.trimEnd('/')
        val url = "$endpoint/translate?api-version=3.0&to=en" 
        
        // Simple payload to translate "ping"
        val payload = "[{'Text':'ping'}]"
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Ocp-Apim-Subscription-Key", subscriptionKey)
            .header("Ocp-Apim-Subscription-Region", region)
            .build()

        val startNs = System.nanoTime()
        client.newCall(request).execute().use { resp ->
             val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
             val raw = resp.body?.string().orEmpty()
             if (!resp.isSuccessful) {
                 val snippet = raw.replace("\n", " ").replace("\r", " ").take(240)
                 throw IllegalStateException("HTTP ${resp.code}：$snippet")
             }
             // Valid Microsoft response is a JSON array
             val valid = runCatching { JSONArray(raw).length() > 0 }.getOrDefault(false)
             if (!valid) throw IllegalStateException("响应格式无效")
             "连接成功（${elapsedMs}ms）"
        }
    } else {
        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) throw IllegalArgumentException("API URL 为空")
    
        val model = config.model.trim().ifEmpty { "gpt-4o-mini" }
        val payload = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("role", "user")
                            .put("content", "ping")
                    )
            )
            .put("temperature", 0)
            .put("max_tokens", 1)
            .put("stream", false)
            .toString()
    
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder()
            .url(resolvedUrl)
            .post(body)
    
        val apiKey = config.apiKey.trim()
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }
    
        val startNs = System.nanoTime()
        client.newCall(requestBuilder.build()).execute().use { resp ->
            val elapsedMs = (System.nanoTime() - startNs) / 1_000_000
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val snippet = raw.replace("\n", " ").replace("\r", " ").take(240)
                throw IllegalStateException("HTTP ${resp.code}：$snippet")
            }
            val hasJson = runCatching { JSONObject(raw) }.isSuccess
            val note = if (hasJson) "响应为 JSON" else "响应非 JSON"
            "连接成功（$note，${elapsedMs}ms）"
        }
    }
}

private fun toPromptLanguage(language: String): String {
    return when (language.trim()) {
        "自动检测" -> "自动检测"
        "中文" -> "中文"
        "英语" -> "英文"
        "日语" -> "日文"
        "韩语" -> "韩文"
        "法语" -> "法文"
        "德语" -> "德文"
        "西班牙语" -> "西班牙文"
        "俄语" -> "俄文"
        else -> language.trim().ifEmpty { "英文" }
    }
}

private fun isLikelyWrongTargetLanguage(targetLanguage: String, translated: String): Boolean {
    val text = translated.trim()
    if (text.isEmpty()) return true
    val (cjkCount, latinCount) = countCjkAndLatin(text)
    if (cjkCount == 0 && latinCount == 0) return false

    return when (targetLanguage.trim()) {
        "英语" -> cjkCount > 0 && latinCount < maxOf(2, cjkCount / 8)
        "中文" -> latinCount > 0 && cjkCount < maxOf(2, latinCount / 8)
        else -> false
    }
}

private fun countCjkAndLatin(text: String): Pair<Int, Int> {
    var cjk = 0
    var latin = 0
    for (ch in text) {
        val block = Character.UnicodeBlock.of(ch)
        val isCjk = block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
        when {
            isCjk -> cjk++
            ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
        }
    }
    return cjk to latin
}
