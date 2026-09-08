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

        val payloadObj = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0)
            .put("stream", false)
        applyThinkingControl(payloadObj, config.enableThinking, model, 3072)
        val payload = payloadObj.toString()
        val body = payload.toRequestBody("application/json; charset=utf-8".toMediaType())

        val requestBuilder = Request.Builder()
            .url(resolvedUrl)
            .post(body)

        val apiKey = config.apiKey.trim()
        if (apiKey.isNotEmpty()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        try {
            var resp = client.newCall(requestBuilder.build()).execute()
            var raw = resp.use { it.body?.string().orEmpty() }

            // 容错机制：如果关闭思考参数被非标/严格 API 报错 400，自动剥离思考参数回退重试一次
            if (resp.code == 400 && !config.enableThinking && (raw.contains("thinking", ignoreCase = true) || raw.contains("unrecognized", ignoreCase = true) || raw.contains("argument", ignoreCase = true))) {
                val fallbackPayload = JSONObject()
                    .put("model", model)
                    .put("messages", messagesArray)
                    .put("temperature", 0)
                    .put("stream", false)
                    .put("max_tokens", 3072)
                    .toString()
                val fallbackReq = Request.Builder()
                    .url(resolvedUrl)
                    .post(fallbackPayload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                if (apiKey.isNotEmpty()) {
                    fallbackReq.header("Authorization", "Bearer $apiKey")
                }
                resp = client.newCall(fallbackReq.build()).execute()
                raw = resp.use { it.body?.string().orEmpty() }
            }

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
                return@withContext translation.trim()
            }

            val translationText = json
                ?.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
            if (translationText != null) {
                return@withContext translationText.trim()
            }

            val legacyTranslation = json?.optString("translation")?.takeIf { it.isNotBlank() }
            if (legacyTranslation != null) {
                return@withContext legacyTranslation.trim()
            }

            val alt = json?.optString("text")?.takeIf { it.isNotBlank() }
            if (alt != null) {
                return@withContext alt.trim()
            }

            val choice0 = json?.optJSONArray("choices")?.optJSONObject(0)
            val finishReason = choice0?.optString("finish_reason")
            val hasReasoning = !choice0?.optJSONObject("message")?.optString("reasoning_content").isNullOrBlank()
            if (finishReason == "length" && hasReasoning) {
                throw IllegalStateException("Token 耗尽（当前模型强制思考导致预算不足，请开启【深度思考】或更换为通用翻译模型）")
            }

            if (raw.isNotBlank()) {
                return@withContext raw.trim()
            }
            throw IllegalStateException("空响应")
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

        val payload = buildImagePayload(image, model, userPrompt, finalSystemPrompt, config.enableThinking)
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
                return@withContext translation.trim()
            }

            val translationText = json
                ?.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
            if (translationText != null) {
                return@withContext translationText.trim()
            }

            val legacyTranslation = json?.optString("translation")?.takeIf { it.isNotBlank() }
            if (legacyTranslation != null) {
                return@withContext legacyTranslation.trim()
            }

            val alt = json?.optString("text")?.takeIf { it.isNotBlank() }
            if (alt != null) {
                return@withContext alt.trim()
            }

            val choice0 = json?.optJSONArray("choices")?.optJSONObject(0)
            val finishReason = choice0?.optString("finish_reason")
            val hasReasoning = !choice0?.optJSONObject("message")?.optString("reasoning_content").isNullOrBlank()
            if (finishReason == "length" && hasReasoning) {
                throw IllegalStateException("Token 耗尽（当前模型强制思考导致预算不足，请开启【深度思考】或更换为通用翻译模型）")
            }

            if (raw.isNotBlank()) {
                return@withContext raw.trim()
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

        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) {
            return@withContext batch.map { "" }
        }

        // Chunk large batches to avoid context/token overflow (max 25 items per batch)
        val chunkSize = 25
        if (batch.size > chunkSize) {
            val chunks = batch.chunked(chunkSize)
            val allResults = mutableListOf<String>()
            for (chunk in chunks) {
                val chunkResults = translateBatchChunk(chunk, sourceLanguage, targetLanguage, settings, resolvedUrl)
                allResults.addAll(chunkResults)
            }
            return@withContext allResults
        }

        return@withContext translateBatchChunk(batch, sourceLanguage, targetLanguage, settings, resolvedUrl)
    }

    private suspend fun translateBatchChunk(
        chunk: List<String>,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState,
        resolvedUrl: String
    ): List<String> = withContext(Dispatchers.IO) {
        val model = config.model.trim().ifEmpty { id }
        val source = sourceLanguage?.trim()?.takeIf { it.isNotEmpty() }
        val target = targetLanguage.trim().takeIf { it.isNotEmpty() } ?: "英语"

        // 1. 构建输入 JSON 列表与全屏上下文参考
        val inputJsonArray = JSONArray()
        chunk.forEachIndexed { id, text ->
            inputJsonArray.put(
                JSONObject().put("id", id).put("text", text)
            )
        }
        val inputJsonStr = inputJsonArray.toString()
        val plainContext = chunk.filter { it.isNotBlank() }.joinToString("\n")

        val userPrompt = buildString {
            append("【屏幕整体语境参考（请先通读以理解全文语义与连贯逻辑）】：\n")
            append(plainContext)
            append("\n\n")
            if (source == null) {
                append("【翻译任务】：结合上述完整语境，将以下 JSON 数据中的各个 \"text\" 片段翻译成")
                append(toPromptLanguage(target))
            } else {
                append("【翻译任务】：结合上述完整语境，将以下 JSON 数据中的各个 \"text\" 片段从")
                append(toPromptLanguage(source))
                append("翻译成")
                append(toPromptLanguage(target))
            }
            append("。\n")
            append("【核心要求】：各个片段的译文必须在语法、代词和语序上与上下文自然连贯，消除因断句切分带来的生硬割裂感。严格输出对应 JSON 数组：[{\"id\": 0, \"text\": \"译文\"}, ...]。\n")
            append("待翻译数据：\n")
            append(inputJsonStr)
        }

        val outputConstraint = when (target.trim()) {
            "英语" -> ""
            "中文" -> ""
            else -> "输出语言必须与目标语言一致。"
        }

        val systemPrompt = """
            # Role：上下文感知的高级翻译专家 (Context-Aware Translation Expert)
            ## Workflow & Rules：
            1. 你将收到一段【屏幕整体语境参考】和对应的待翻译 JSON 列表。
            2. 首先通读整体语境，把握段落大意、对话语气、代词指代和专有名词。
            3. 在全局语境指导下翻译每个 text 字段。即使原文被截断成多行，各片段翻译组合起来也必须是通顺地道、符合母语习惯的句子，严禁孤立逐词机翻。
            4. 必须保留原始 "id" 字段（与输入完全对应，不得遗漏、合并或修改 id）。
            5. 仅翻译 "text" 字段的值，如果原文为纯乱码或无意义符号，text 留空 ""。
            6. 严格只输出标准 JSON 数组，严禁包含任何 markdown 代码块标记（如 ```json）或前后解释说明。
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

        val payloadObj = JSONObject()
            .put("model", model)
            .put("messages", messagesArray)
            .put("temperature", 0)
            .put("stream", false)
        val defaultTokens = (inputJsonStr.length * 3).coerceAtLeast(1024)
        applyThinkingControl(payloadObj, config.enableThinking, model, defaultTokens)
        val payload = payloadObj.toString()

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
                    throw IllegalStateException("HTTP ${resp.code}: $raw")
                }

                val jsonResponse = runCatching { JSONObject(raw) }.getOrNull()
                val content = jsonResponse
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    .orEmpty()

                var cleanContent = content.trim()
                // 移除可能的 markdown 标记
                cleanContent = cleanContent.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()

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
                            val id = item.optInt("id", item.optString("id").toIntOrNull() ?: -1)
                            val text = item.optString("text").ifEmpty {
                                item.optString("translation").ifEmpty {
                                    item.optString("t")
                                }
                            }
                            if (id != -1) {
                                resultMap[id] = text
                            }
                        }
                    }
                }

                // 正则容错 1：JSON 键值对提取
                if (resultMap.size < chunk.size) {
                    val regex = Regex("""["']?id["']?\s*:\s*(\d+)\s*,\s*["']?(?:text|translation|t)["']?\s*:\s*["'](.*?)["'](?:\s*[,}])""")
                    regex.findAll(cleanContent).forEach { match ->
                        val id = match.groupValues[1].toIntOrNull()
                        val txt = match.groupValues[2]
                        if (id != null && !resultMap.containsKey(id)) {
                            resultMap[id] = txt
                        }
                    }
                }

                // 正则容错 2：行编号匹配（如 0: 译文 或 0. 译文）
                if (resultMap.size < chunk.size) {
                    val lineRegex = Regex("""(?m)^\s*(\d+)[\.\:\、\s]\s*(.+)$""")
                    lineRegex.findAll(content).forEach { match ->
                        val id = match.groupValues[1].toIntOrNull()
                        val txt = match.groupValues[2].trim()
                        if (id != null && id < chunk.size && !resultMap.containsKey(id)) {
                            resultMap[id] = txt
                        }
                    }
                }

                // 按原输入顺序 1:1 输出
                return@withContext chunk.mapIndexed { index, originalText ->
                    val trans = resultMap[index]?.trim()
                    if (trans != null && trans.isNotEmpty() && trans != originalText) trans else ""
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

        val payload = buildImagePayload(image, model, userPrompt, finalSystemPrompt, config.enableThinking)
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
            
            var cleanContent = content.trim()
            
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

// 统一注入思考控制参数
private fun applyThinkingControl(
    payload: JSONObject,
    enableThinking: Boolean,
    modelName: String,
    defaultMaxTokens: Int = 3072
): JSONObject {
    if (enableThinking) {
        payload.put("max_tokens", maxOf(defaultMaxTokens, 4096))
        payload.put("thinking", JSONObject().put("type", "enabled"))
    } else {
        // 关闭深度思考：适配 DeepSeek、火山方舟 (Volcengine Ark)、Claude 与通义千问等
        payload.put("thinking", JSONObject().put("type", "disabled"))
        payload.put("enable_thinking", false)
        val lowerModel = modelName.lowercase()
        if (lowerModel.startsWith("o1") || lowerModel.startsWith("o3") || lowerModel.startsWith("o4")) {
            payload.put("reasoning_effort", "low")
        }
        // 底线防护：即使用户关闭思考开关，也将基础 max_tokens 保守设定为 3072，防范关不掉思考的模型吃满预算导致空响应
        payload.put("max_tokens", maxOf(defaultMaxTokens, 3072))
    }
    return payload
}

// 将图片封装为多模态请求体
private fun buildImagePayload(
    image: Bitmap,
    model: String,
    userPrompt: String,
    systemPrompt: String,
    enableThinking: Boolean = false
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

    val payloadObj = JSONObject()
        .put("model", model)
        .put("messages", messages)
        .put("temperature", 0)
        .put("stream", false)

    applyThinkingControl(payloadObj, enableThinking, model, 3072)
    return payloadObj.toString()
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
        val payloadObj = JSONObject()
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
            .put("stream", false)
        applyThinkingControl(payloadObj, config.enableThinking, model, 16)
        val payload = payloadObj.toString()
    
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
