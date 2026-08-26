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

        val isOverlayMode = settings.resultMode == "覆盖层"
        val userPrompt = buildString {
            if (isOverlayMode) {
                if (source == null) {
                    append("任务：翻译为")
                    append(toPromptLanguage(target))
                } else {
                    append("任务：")
                    append(toPromptLanguage(source))
                    append("翻译为")
                    append(toPromptLanguage(target))
                }
                append("\n要求：只输出译文\n文本：\n")
            } else if (source == null) {
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

        val systemPrompt = if (isOverlayMode) {
            "只做翻译。输出${toPromptLanguage(target)}译文；不要解释，不要复述原文或指令。$outputConstraint"
        } else {
            """
                # Role：严格翻译执行器
                ## Constraints：
                - 必须执行翻译操作，严禁直接输出原文
                - 确保语序符合译文表达习惯
                - 返回内容只能是目标语言的译文，禁止包含任何原文片段
                - 如果输入内容无法翻译（如纯符号、乱码），直接返回空字符串，严禁输出任何解释或指令复述
                ## Workflow：
                1. 接收输入后立即识别为翻译任务
                2. 执行逐句翻译，确保每句都有对应译文
                3. 严禁输出任何元信息或指令复述，只返回纯译文
                $outputConstraint
            """.trimIndent()
        }
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
                    return@withContext cleanTranslationOutput(stripThinkTags(translation), source, target)
                }

                val translationText = json
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optString("text")
                    ?.takeIf { it.isNotBlank() }
                if (translationText != null) {
                    return@withContext cleanTranslationOutput(stripThinkTags(translationText), source, target)
                }

                val legacyTranslation = json?.optString("translation")?.takeIf { it.isNotBlank() }
                if (legacyTranslation != null) {
                    return@withContext cleanTranslationOutput(stripThinkTags(legacyTranslation), source, target)
                }

                val alt = json?.optString("text")?.takeIf { it.isNotBlank() }
                if (alt != null) {
                    return@withContext cleanTranslationOutput(stripThinkTags(alt), source, target)
                }

                if (raw.isNotBlank()) {
                    return@withContext cleanTranslationOutput(stripThinkTags(raw), source, target)
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
    override suspend fun translateBatch(
        batch: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): List<String> = withContext(Dispatchers.IO) {
        if (batch.isEmpty()) return@withContext emptyList()
        
        // Convert List to Map<Index, Text>
        val batchMap = batch.mapIndexed { index, text -> index to text }.toMap()

        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) {
            // Fallback to sequential if URL invalid (though translate() handles empty URL check too)
             return@withContext batch.map { "" }
        }

        val model = config.model.trim().ifEmpty { id }
        val source = sourceLanguage?.trim()?.takeIf { it.isNotEmpty() }
        val target = targetLanguage.trim().takeIf { it.isNotEmpty() } ?: "英语"

        // 1. 构建输入 JSON 列表
        val inputJsonArray = JSONArray()
        batchMap.forEach { (id, text) ->
            inputJsonArray.put(
                JSONObject().put("id", id).put("text", text)
            )
        }
        val inputJsonStr = inputJsonArray.toString()

        val userPrompt = buildString {
            if (source == null) {
                append("将以下 JSON 数据中的 \"text\" 字段内容翻译成")
                append(toPromptLanguage(target))
            } else {
                append("将以下 JSON 数据中的 \"text\" 字段内容从")
                append(toPromptLanguage(source))
                append("翻译成")
                append(toPromptLanguage(target))
            }
            append("。请严格保持 JSON 格式返回，结构为 [{\"id\": 1, \"text\": \"译文\"}, ...]。\n")
            append("数据内容：\n")
            append(inputJsonStr)
        }

        val outputConstraint = when (target.trim()) {
            "英语" -> ""
            "中文" -> ""
            else -> "输出语言必须与目标语言一致。"
        }

        val systemPrompt = """
            # Role：JSON 翻译专家
            ## Constraints：
            - 你只输出标准的 JSON 数组，严禁包含 markdown 代码块标记（如 ```json）。
            - 严禁输出任何解释性文字或前言/后缀。
            - 必须保留原始 "id" 字段不变。
            - 仅翻译 "text" 字段的值，不要修改键名。
            - 如果原文无法翻译（如纯乱码），text 字段留空。
            - 对每个text 独立翻译，
            - 翻译完一个text后立即将译文对原文进行替换，替换完成才能翻译下一个text。
            - 严禁对文本进行续写或补全，只翻译提供的片段。
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
        messagesArray.put(JSONObject().put("role", "system").put("content", finalSystemPrompt))
        messagesArray.put(JSONObject().put("role", "user").put("content", userPrompt))

        val payload = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0)
            .put("stream", false)
            .put("max_tokens", (inputJsonStr.length * 2).coerceAtLeast(1024))
            .toString()

        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())
        val requestBuilder = Request.Builder().url(resolvedUrl).post(body)
        val apiKey = config.apiKey.trim()
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        try {
            client.newCall(requestBuilder.build()).execute().use { resp ->
                val raw = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                   // Log error and fallback? Or throw? Throwing allows Manager to handle.
                   throw IllegalStateException("HTTP ${resp.code}: $raw")
                }

                val jsonResponse = runCatching { JSONObject(raw) }.getOrNull()
                val content = jsonResponse
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()
                
                var cleanContent = stripThinkTags(content).trim()
                
                val jsonStart = cleanContent.indexOf('[')
                val jsonEnd = cleanContent.lastIndexOf(']')
                if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                    cleanContent = cleanContent.substring(jsonStart, jsonEnd + 1)
                }

                val resultMap = mutableMapOf<Int, String>()
                val resultJsonArray = runCatching { JSONArray(cleanContent) }.getOrNull()

                if (resultJsonArray != null) {
                    for (i in 0 until resultJsonArray.length()) {
                        val item = resultJsonArray.optJSONObject(i)
                        if (item != null) {
                            val id = item.optInt("id", -1)
                            val text = item.optString("text")
                            if (id != -1) {
                                resultMap[id] = text
                            }
                        }
                    }
                } else {
                     val regex = Regex("\\{\"id\":\\s*(\\d+),\\s*\"text\":\\s*\"(.*?)\"\\}")
                     regex.findAll(cleanContent).forEach { match ->
                         val id = match.groupValues[1].toIntOrNull()
                         val txt = match.groupValues[2]
                         if (id != null) resultMap[id] = txt
                     }
                }
                
                // Convert Map back to List, preserving order
                return@withContext batch.mapIndexed { index, _ -> 
                    resultMap[index] ?: "" // Return empty string if failed, or maybe original? Empty implies failure.
                }
            }
        } catch (t: Throwable) {
            throw t
        }
    }
    // 多模态识别并翻译，返回结构化数据（包含坐标）
    suspend fun recognizeAndTranslate(
        image: Bitmap,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): List<com.example.mytransl.domain.ocr.TextBlock> = withContext(Dispatchers.IO) {
        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) throw IllegalArgumentException("API URL 为空")

        val model = config.model.trim().ifEmpty { id }
        val source = sourceLanguage?.trim()?.takeIf { it.isNotEmpty() } ?: "自动检测"
        val target = targetLanguage.trim().takeIf { it.isNotEmpty() } ?: "中文"

        val imgW = image.width.toFloat()
        val imgH = image.height.toFloat()

        val systemPrompt = """
            # Role：视觉翻译专家
            ## Task：
            - 识别图片中的所有文字块。
            - 将识别到的文字从${toPromptLanguage(source)}翻译成${toPromptLanguage(target)}。
            - 确定每个文字块在图中的边界框（Bounds）。

            ## Output Format：
            请严格输出标准 JSON 列表，禁止包含 Markdown 标记或任何额外说明：
            [
              {
                "translation": "翻译后的内容",
                "bounds": [xmin, ymin, xmax, ymax]
              }
            ]
            
            ## Critical Constraints：
             - **"translation" 字段必须是翻译后的${toPromptLanguage(target)}文字，严禁输出${toPromptLanguage(source)}原文。**
             - 如果无法翻译，跳过该文字块，不要原样输出。

            ## Coordinates：
            - 坐标必须归一化到 [0, 1000] 范围。
            - [0, 0] 为左上角，[1000, 1000] 为右下角。
            - 格式为：[xmin, ymin, xmax, ymax]。
        """.trimIndent()

        val userPrompt = "开始任务。只输出 JSON。"

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

        val requestBuilder = Request.Builder().url(resolvedUrl).post(body)
        val apiKey = config.apiKey.trim()
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        client.newCall(requestBuilder.build()).execute().use { resp ->
            val raw = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $raw")
            }

            val jsonResponse = runCatching { JSONObject(raw) }.getOrNull()
            val content = jsonResponse
                ?.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
            
            var cleanContent = stripThinkTags(content).trim()
            
            // 某些 API 返回的 content 可能错误地保留了转义符，导致 JSON 解析失败
            // 强制还原：将 \" 替换为 "
            cleanContent = cleanContent.replace("\\\"", "\"")
            
            val jsonStart = cleanContent.indexOf('[')
            val jsonEnd = cleanContent.lastIndexOf(']')
            if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
                cleanContent = cleanContent.substring(jsonStart, jsonEnd + 1)
            }

            val resultList = mutableListOf<com.example.mytransl.domain.ocr.TextBlock>()
            val jsonArray = runCatching { JSONArray(cleanContent) }.getOrNull()

            if (jsonArray != null) {
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optJSONObject(i) ?: continue
                    // 支持 translation, text, t 三种 key，兼容性拉满
                    val text = item.optString("translation").ifEmpty { 
                        item.optString("text").ifEmpty { 
                           item.optString("t").ifEmpty { item.optString("origin") } 
                        }
                    }
                    // 支持 bounds, b 两种 key
                    val boundsArr = item.optJSONArray("bounds") ?: item.optJSONArray("b")
                    
                    if (text.isNotEmpty() && boundsArr != null && boundsArr.length() >= 4) {
                        try {
                            val x1 = boundsArr.getDouble(0).toFloat() / 1000f * imgW
                            val y1 = boundsArr.getDouble(1).toFloat() / 1000f * imgH
                            val x2 = boundsArr.getDouble(2).toFloat() / 1000f * imgW
                            val y2 = boundsArr.getDouble(3).toFloat() / 1000f * imgH
                            
                            val rect = android.graphics.RectF(x1, y1, x2, y2)
                            resultList.add(com.example.mytransl.domain.ocr.TextBlock(text, rect))
                        } catch (e: Exception) {
                            // ignore malformed bounds
                        }
                    }
                }
            }
            return@withContext resultList
        }
    }
}

// 去除大模型返回的思考标签内容
private fun stripThinkTags(text: String): String {
    return text.replace(Regex("(?is)<think>.*?</think>"), "").trim()
}

/**
 * 清洗翻译输出，移除 LLM 可能回显的提示词前缀
 */
private fun cleanTranslationOutput(text: String, sourceLanguage: String?, targetLanguage: String): String {
    var cleaned = text.trim()
    
    // Pattern 1: "把下面内容从XX翻译成XX，只输出XX" 开头
    val pattern1 = Regex("^把下面内容(从.{1,10})?翻译成.{1,10}[，,]?只?输出.{1,10}[:：]?\\s*")
    cleaned = cleaned.replace(pattern1, "")
    
    // Pattern 2: "翻译：" 或 "翻译结果：" 开头
    val pattern2 = Regex("^翻译(结果)?[:：]\\s*")
    cleaned = cleaned.replace(pattern2, "")
    
    // Pattern 3: 如果第一行是提示词，提取后续内容
    if (cleaned.contains('\n')) {
        val lines = cleaned.split('\n')
        if (lines[0].length < 50 && lines[0].contains("翻译") && lines[0].contains("输出")) {
            cleaned = lines.drop(1).joinToString("\n").trim()
        }
    }
    
    return cleaned.trim()
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
