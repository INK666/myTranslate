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
        val resolvedUrl = resolveApiUrl(config.baseUrl)
        if (resolvedUrl.isEmpty()) {
            // If URL is empty, we can't do anything. Return empty list.
            return@withContext emptyList()
        }

        val model = config.model.trim().ifEmpty { config.name }
        
        // Construct the prompt for Pure OCR
        // User may provide a custom prompt in config.prompt. If not, use a default one.
        val customPrompt = config.prompt.trim().takeIf { it.isNotEmpty() }
        val systemPrompt = "You are a pure OCR engine. Output ONLY the text found in the image. Do not translate. Do not add markdown logic or explanations."
        val userPrompt = customPrompt ?: "请提取图片中的所有文字"

        val payload = buildImagePayload(bitmap, model, userPrompt, systemPrompt)
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

                val json = runCatching { JSONObject(raw) }.getOrNull()
                val content = json
                    ?.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("message")
                    ?.optString("content")
                    ?.takeIf { it.isNotBlank() }
                
                if (content != null) {
                    val cleanText = stripThinkTags(content).trim()
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

    private fun stripThinkTags(text: String): String {
        return text.replace(Regex("(?is)<think>.*?</think>"), "").trim()
    }

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
            .put("max_tokens", 2048) // A bit larger for full page text
            .toString()
    }

    private fun bitmapToBase64Png(bitmap: Bitmap): String {
        val stream = ByteArrayOutputStream()
        // Use higher quality for OCR
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        val bytes = stream.toByteArray()
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
}
