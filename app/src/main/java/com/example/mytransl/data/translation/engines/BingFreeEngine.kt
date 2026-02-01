package com.example.mytransl.data.translation.engines

import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.domain.translation.TranslationEngine
import java.io.EOFException
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

class BingFreeEngine(
    client: OkHttpClient = OkHttpClient()
) : TranslationEngine {
    override val id: String = "Bing翻译（免费）"

    private val http: OkHttpClient = client.newBuilder()
        .cookieJar(if (client.cookieJar == CookieJar.NO_COOKIES) MemoryCookieJar() else client.cookieJar)
        .protocols(listOf(Protocol.HTTP_1_1))
        .retryOnConnectionFailure(true)
        .build()

    // Cache for tokens
    private var baseHost: String? = null
    private var key: String? = null
    private var token: String? = null
    private var ig: String? = null
    private var iid: String? = null
    private var tokenTimestamp: Long = 0
    private val TOKEN_VALIDITY_MS = 10 * 60 * 1000 // 10 minutes

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String = withContext(Dispatchers.IO) {
        var lastError: Throwable? = null
        for (attempt in 0 until 3) {
            val result = runCatching {
                translateOnce(text, sourceLanguage, targetLanguage)
            }.getOrElse { e ->
                lastError = e
                ""
            }

            if (result.isNotBlank()) return@withContext result

            val e = lastError
            val retriable = e is EOFException || e is IOException
            if (!retriable) break
            clearTokenCache()
            delay(200L * (attempt + 1))
        }
        val e = lastError
        val detail = when {
            e == null -> "未知错误"
            e.message.isNullOrBlank() -> e.javaClass.simpleName
            else -> "${e.javaClass.simpleName}: ${e.message}"
        }
        throw IllegalStateException("Bing翻译失败：$detail")
    }

    private fun translateOnce(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String
    ): String {
        ensureToken()

        val sl = mapLanguage(sourceLanguage)
        val tl = mapLanguage(targetLanguage)

        val host = baseHost ?: "https://www.bing.com"
        val currentIG = ig ?: throw IllegalStateException("无法获取 Bing IG 参数")
        val currentIID = iid ?: "translator.5028"
        val url = "$host/ttranslatev3?isVertical=1&IG=$currentIG&IID=$currentIID"

        val formBody = FormBody.Builder()
            .add("fromLang", sl)
            .add("text", text)
            .add("to", tl)
            .add("token", token ?: "")
            .add("key", key ?: "")
            .build()

        val request = Request.Builder()
            .url(url)
            .post(formBody)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )
            .header("Referer", "$host/translator")
            .header("Origin", host)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .header("Connection", "close")
            .header("X-Requested-With", "XMLHttpRequest")
            .build()

        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IllegalStateException("Bing请求失败: ${resp.code}")
            }
            val raw = resp.body?.string().orEmpty().trim()
            if (raw.isBlank()) {
                val meta = buildString {
                    append("HTTP ").append(resp.code)
                    val ct = resp.header("Content-Type")?.trim().orEmpty()
                    val ce = resp.header("Content-Encoding")?.trim().orEmpty()
                    val cl = resp.header("Content-Length")?.trim().orEmpty()
                    if (ct.isNotEmpty()) append(", Content-Type=").append(ct)
                    if (ce.isNotEmpty()) append(", Content-Encoding=").append(ce)
                    if (cl.isNotEmpty()) append(", Content-Length=").append(cl)
                }
                throw IllegalStateException("响应为空（$meta）")
            }

            if (raw.startsWith("<")) {
                val snippet = raw.replace("\n", " ").replace("\r", " ").take(240)
                throw IllegalStateException("返回HTML（可能触发风控/人机验证）：$snippet")
            }

            val normalizedRaw = raw
                .removePrefix(")]}'")
                .trimStart()

            val jsonArray = runCatching { JSONArray(normalizedRaw) }.getOrNull()
            val jsonObject = if (jsonArray == null) runCatching { JSONObject(normalizedRaw) }.getOrNull() else null
            if (jsonArray == null && jsonObject == null) {
                val contentType = resp.header("Content-Type").orEmpty()
                val snippet = normalizedRaw.replace("\n", " ").replace("\r", " ").take(240)
                throw IllegalStateException("无效的响应格式：$contentType $snippet")
            }

            val translations = when {
                jsonArray != null -> jsonArray.optJSONObject(0)?.optJSONArray("translations")
                else -> jsonObject?.optJSONArray("translations")
            } ?: return ""

            val firstTrans = translations.optJSONObject(0) ?: return ""
            return firstTrans.optString("text", "").trim()
        }
    }

    private fun ensureToken() {
        val now = System.currentTimeMillis()
        if (key != null && token != null && ig != null && (now - tokenTimestamp < TOKEN_VALIDITY_MS)) {
            return
        }

        val hosts = listOf("https://www.bing.com", "https://cn.bing.com")
        var lastError: Throwable? = null
        for (host in hosts) {
            val request = Request.Builder()
                .url("$host/translator")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Encoding", "identity")
                .header("Connection", "close")
                .build()

            val ok = runCatching {
                http.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        throw IllegalStateException("无法连接 Bing: ${resp.code}")
                    }
                    val html = resp.body?.string().orEmpty()
                    parseAndUpdateTokenFromHtml(html)

                    iid = Regex("""data-iid="(translator\.[^"]+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex("data-iid=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                        ?: Regex("""\bIID:"([^"]+)"""").find(html)?.groupValues?.get(1)
                        ?: Regex(""""IID"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)
                        ?: "translator.5028"

                    if (key == null || token == null || ig == null) {
                        throw IllegalStateException("无法解析 Bing 令牌参数")
                    }
                    val finalUrl = resp.request.url
                    baseHost = "${finalUrl.scheme}://${finalUrl.host}"
                }
                true
            }.getOrElse {
                lastError = it
                false
            }

            if (ok) return
        }
        val e = lastError
        val detail = when {
            e == null -> "未知错误"
            e.message.isNullOrBlank() -> e.javaClass.simpleName
            else -> "${e.javaClass.simpleName}: ${e.message}"
        }
        throw IllegalStateException("无法初始化 Bing：$detail")
    }

    // 简易解析：从 Bing Translator HTML 中提取 IG / key / token
    private fun parseAndUpdateTokenFromHtml(html: String) {
        ig = Regex("""\bIG:"([^"]+)"""").find(html)?.groupValues?.get(1)
            ?: Regex(""""IG"\s*:\s*"([^"]+)"""").find(html)?.groupValues?.get(1)

        val helperStrong = Regex(
            """params_AbusePreventionHelper\s*=\s*\[\s*([0-9]+)\s*,\s*["']([^"']+)["']\s*,\s*([0-9]+)\s*\]""",
            setOf(RegexOption.IGNORE_CASE)
        ).find(html)
        if (helperStrong != null) {
            key = helperStrong.groupValues[1].trim()
            token = helperStrong.groupValues[2].trim()
            tokenTimestamp = System.currentTimeMillis()
            return
        }

        val helperLoose = Regex(
            """params_AbusePreventionHelper\s*=\s*\[([^\]]+)\]""",
            setOf(RegexOption.IGNORE_CASE)
        ).find(html)?.groupValues?.get(1)

        if (helperLoose != null) {
            val parts = helperLoose.split(",").map { it.trim() }
            if (parts.size >= 2) {
                val k = parts[0].trim().trim('"')
                val t = parts[1].trim().trim('"')
                if (k.isNotBlank() && t.isNotBlank()) {
                    key = k
                    token = t
                    tokenTimestamp = System.currentTimeMillis()
                }
            }
        }
    }

    private fun clearTokenCache() {
        baseHost = null
        key = null
        token = null
        ig = null
        iid = null
        tokenTimestamp = 0
    }

    private class MemoryCookieJar : CookieJar {
        private val store: MutableMap<String, MutableList<Cookie>> = LinkedHashMap()

        override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            if (cookies.isEmpty()) return
            val host = url.host
            synchronized(store) {
                val existing = store[host].orEmpty()
                val merged = (existing + cookies)
                    .distinctBy { it.name + "@" + it.domain + ":" + it.path }
                    .toMutableList()
                store[host] = merged
            }
        }

        override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val host = url.host
            val now = System.currentTimeMillis()
            synchronized(store) {
                val list = store[host] ?: return emptyList()
                val valid = list.filter { it.expiresAt > now && it.matches(url) }
                store[host] = valid.toMutableList()
                return valid
            }
        }
    }

    private fun mapLanguage(lang: String?): String {
        return when (lang) {
            "中文" -> "zh-Hans"
            "英语" -> "en"
            "日语" -> "ja"
            "韩语" -> "ko"
            "法语" -> "fr"
            "德语" -> "de"
            "西班牙语" -> "es"
            "俄语" -> "ru"
            "阿拉伯语" -> "ar"
            "越南语" -> "vi"
            "自动检测", null -> "auto-detect"
            else -> "auto-detect"
        }
    }
}
