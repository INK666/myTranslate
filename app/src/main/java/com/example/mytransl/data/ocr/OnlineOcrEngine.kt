package com.example.mytransl.data.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import android.util.Base64
import com.example.mytransl.data.settings.ApiConfig
import com.example.mytransl.domain.ocr.PreferredLanguageAwareOcrEngine
import com.example.mytransl.domain.ocr.TextBlock
import com.example.mytransl.system.resource.HttpClientPool
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

class OnlineOcrEngine(
    private val config: ApiConfig
) : PreferredLanguageAwareOcrEngine {
    
    // We use the shared client pool, similar to OnlineApiEngine usage in TranslationService
    private val client: OkHttpClient = HttpClientPool.standardClient

    override var preferredLanguage: String? = null

    override suspend fun recognize(bitmap: Bitmap): List<TextBlock> = withContext(Dispatchers.IO) {
        android.util.Log.d("OnlineOcrEngine", "🚀🚀🚀 OnlineOcrEngine.recognize() 被调用!")
        android.util.Log.d("OnlineOcrEngine", "   config.name = \"${config.name}\"")
        android.util.Log.d("OnlineOcrEngine", "   config.baseUrl = \"${config.baseUrl}\"")
        android.util.Log.d("OnlineOcrEngine", "   config.model = \"${config.model}\"")
        
        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) {
            // If URL is empty, we can't do anything. Return empty list.
            return@withContext emptyList()
        }

        val model = config.model.trim().ifEmpty { config.name }
        
        android.util.Log.d("OnlineOcrEngine", "   resolvedUrl = \"$resolvedUrl\"")
        android.util.Log.d("OnlineOcrEngine", "   最终 model = \"$model\"")
        
        // Construct the prompt for Pure OCR
        val customPrompt = config.prompt.trim().takeIf { it.isNotEmpty() }
        
        // 完全匹配 Ollama 对话的提示词：只用 user message，不用 system prompt
        val systemPrompt = ""
        val userPrompt = customPrompt ?: "识别图中文字"

        val payload = buildImagePayload(bitmap, model, userPrompt, systemPrompt, config.enableThinking)
        
        // 调试日志：打印发送给 Ollama 的完整 payload
        android.util.Log.d("OnlineOcrEngine", "=== OCR Request Payload ===")
        android.util.Log.d("OnlineOcrEngine", "URL: $resolvedUrl")
        android.util.Log.d("OnlineOcrEngine", "Payload: $payload")
        
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
                    // In case of error, we return empty list or throw. 
                    // To avoid crashing the service loop, returning empty list and logging (or using a dummy error block) might be safer, 
                    // but usually OcrEngine implementations just throw or return empty.
                    // Let's print to logcat for debugging and return empty.
                    android.util.Log.e("OnlineOcrEngine", "HTTP Error: ${resp.code} $raw")
                    return@withContext emptyList()
                }

                android.util.Log.d("OnlineOcrEngine", "=== OCR Response ===")
                android.util.Log.d("OnlineOcrEngine", "Raw response (前500字符): ${raw.take(500)}")

                val json = runCatching { JSONObject(raw) }.getOrNull()
                val content = json
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                
                android.util.Log.d("OnlineOcrEngine", "提取的 content 长度: ${content?.length ?: 0}")
                android.util.Log.d("OnlineOcrEngine", "Content 前200字符: ${content?.take(200)}")
                
                if (content != null) {
                    val cleanText = content.trim()
                    if (cleanText.isEmpty()) return@withContext emptyList()
                    
                    // Wrap result in a single TextBlock covering the whole image
                    return@withContext listOf(
                        TextBlock(
                            text = cleanText,
                            bounds = RectF(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
                        )
                    )
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("OnlineOcrEngine", "Exception: ${e.message}")
        }

        emptyList()
    }

    // --- Helpers duplicated/adapted from OnlineApiEngine ---

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

        if (enableThinking) {
            payloadObj.put("max_tokens", 4096)
            payloadObj.put("thinking", JSONObject().put("type", "enabled"))
        } else {
            payloadObj.put("thinking", JSONObject().put("type", "disabled"))
            payloadObj.put("enable_thinking", false)
            val lowerModel = model.lowercase()
            if (lowerModel.startsWith("o1") || lowerModel.startsWith("o3") || lowerModel.startsWith("o4")) {
                payloadObj.put("reasoning_effort", "low")
            }
            payloadObj.put("max_tokens", 3072)
        }
        return payloadObj.toString()
    }

    private fun bitmapToBase64Png(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        // Use higher quality for OCR
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
