package com.example.mytransl.data.translation.engines

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.domain.translation.TranslationEngine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { result ->
        if (cont.isActive) cont.resume(result)
    }
    addOnFailureListener { e ->
        if (cont.isActive) cont.resumeWithException(e)
    }
    addOnCanceledListener {
        cont.cancel()
    }
}

private fun detectLanguageCode(text: String): String {
    for (ch in text) {
        val block = Character.UnicodeBlock.of(ch)
        if (
            block == Character.UnicodeBlock.HIRAGANA ||
            block == Character.UnicodeBlock.KATAKANA ||
            block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS
        ) return "ja"
        if (
            block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
            block == Character.UnicodeBlock.HANGUL_JAMO ||
            block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO
        ) return "ko"
        if (
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
            block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS_SUPPLEMENT
        ) return "zh"
        if (
            block == Character.UnicodeBlock.CYRILLIC ||
            block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY
        ) return "ru"
        if (
            block == Character.UnicodeBlock.ARABIC ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_A ||
            block == Character.UnicodeBlock.ARABIC_PRESENTATION_FORMS_B ||
            block == Character.UnicodeBlock.ARABIC_SUPPLEMENT
        ) return "ar"
    }
    return "en"
}

private fun toMlKitLanguage(tag: String): String? {
    return when (tag) {
        "zh" -> TranslateLanguage.CHINESE
        "en" -> TranslateLanguage.ENGLISH
        "ja" -> TranslateLanguage.JAPANESE
        "ko" -> TranslateLanguage.KOREAN
        "fr" -> TranslateLanguage.FRENCH
        "de" -> TranslateLanguage.GERMAN
        "ru" -> TranslateLanguage.RUSSIAN
        "es" -> TranslateLanguage.SPANISH
        "ar" -> TranslateLanguage.ARABIC
        "vi" -> TranslateLanguage.VIETNAMESE
        else -> null
    }
}

private fun mapUiLanguageToTag(lang: String?): String? {
    return when (lang?.trim()) {
        "中文" -> "zh"
        "英语" -> "en"
        "日语" -> "ja"
        "韩语" -> "ko"
        "法语" -> "fr"
        "德语" -> "de"
        "俄语" -> "ru"
        "西班牙语" -> "es"
        "阿拉伯语" -> "ar"
        "越南语" -> "vi"
        "自动检测", null -> null
        else -> null
    }
}

object OfflineDictionaryPackage {
    private const val DirName = "offline"
    private const val FileName = "ms_offline_dict.tsv"
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    data class InstallOutcome(
        val source: String,
        val bytes: Long
    )

    fun dictFile(context: Context): File {
        val dir = File(context.filesDir, DirName)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return File(dir, FileName)
    }

    fun isInstalled(context: Context): Boolean = dictFile(context).exists()

    fun delete(context: Context) {
        runCatching { dictFile(context).delete() }
    }

    suspend fun install(context: Context): InstallOutcome = installOrUpdate(context)

    suspend fun installOrUpdate(
        context: Context,
        onProgress: ((downloadedBytes: Long, totalBytes: Long?) -> Unit)? = null
    ): InstallOutcome = withContext(Dispatchers.IO) {
        val target = dictFile(context)
        val tmp = File(target.parentFile, "${target.name}.tmp")

        suspend fun report(downloadedBytes: Long, totalBytes: Long?) {
            if (onProgress == null) return
            withContext(Dispatchers.Main) {
                onProgress(downloadedBytes, totalBytes)
            }
        }

        val urls = listOf(
            "https://ghproxy.com/https://raw.githubusercontent.com/myTransl/myTransl/main/ms_offline_dict.tsv",
            "https://raw.githubusercontent.com/myTransl/myTransl/main/ms_offline_dict.tsv"
        )

        suspend fun writeBuiltin(): InstallOutcome {
            val bytes = buildBuiltinTsv().toByteArray(Charsets.UTF_8)
            tmp.outputStream().buffered().use { out ->
                var offset = 0
                val total = bytes.size.toLong()
                while (offset < bytes.size) {
                    val chunk = (bytes.size - offset).coerceAtMost(16 * 1024)
                    out.write(bytes, offset, chunk)
                    offset += chunk
                    try {
                        report(offset.toLong(), total)
                    } catch (_: Throwable) {
                    }
                }
            }
            if (target.exists()) target.delete()
            tmp.renameTo(target)
            return InstallOutcome(source = "内置", bytes = target.length())
        }

        for (url in urls) {
            val req = Request.Builder().url(url).get().build()
            val resp = runCatching { httpClient.newCall(req).execute() }.getOrNull() ?: continue
            resp.use { r ->
                if (!r.isSuccessful) return@use
                val body = r.body ?: return@use
                val total = body.contentLength().takeIf { it > 0L }
                var downloaded = 0L
                tmp.outputStream().buffered().use { out ->
                    body.byteStream().buffered().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                            downloaded += read.toLong()
                            try {
                                report(downloaded, total)
                            } catch (_: Throwable) {
                            }
                        }
                    }
                }
                if (downloaded > 0L) {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                    return@withContext InstallOutcome(source = url, bytes = target.length())
                }
            }
        }

        writeBuiltin()
    }

    fun sizeBytes(context: Context): Long = dictFile(context).takeIf { it.exists() }?.length() ?: 0L

    fun lastModified(context: Context): Long = dictFile(context).takeIf { it.exists() }?.lastModified() ?: 0L

    private val cacheLock = Any()
    @Volatile
    private var cachedLastModified: Long = 0L
    @Volatile
    private var cachedMap: Map<String, String> = emptyMap()

    suspend fun load(context: Context): Map<String, String> {
        val file = dictFile(context)
        val lm = file.takeIf { it.exists() }?.lastModified() ?: 0L
        if (lm == 0L) return emptyMap()
        if (lm == cachedLastModified && cachedMap.isNotEmpty()) return cachedMap

        val parsed = withContext(Dispatchers.IO) {
            val map = HashMap<String, String>(2048)
            file.inputStream().buffered().reader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) return@forEach
                    val idx = trimmed.indexOf('\t')
                    if (idx <= 0 || idx >= trimmed.lastIndex) return@forEach
                    val key = trimmed.substring(0, idx).trim().lowercase()
                    val value = trimmed.substring(idx + 1).trim()
                    if (key.isNotEmpty() && value.isNotEmpty()) {
                        map[key] = value
                    }
                }
            }
            map
        }

        synchronized(cacheLock) {
            cachedLastModified = lm
            cachedMap = parsed
        }
        return parsed
    }

    private fun buildBuiltinTsv(): String {
        val baseEntries = listOf(
            "hello\t你好",
            "world\t世界",
            "settings\t设置",
            "start\t开始",
            "stop\t停止",
            "translate\t翻译",
            "language\t语言",
            "permission\t权限",
            "download\t下载",
            "update\t更新",
            "delete\t删除",
            "copy\t复制",
            "paste\t粘贴",
            "open\t打开",
            "close\t关闭",
            "save\t保存",
            "cancel\t取消",
            "confirm\t确认",
            "error\t错误",
            "success\t成功",
            "network\t网络",
            "offline\t离线",
            "online\t在线",
            "screen\t屏幕",
            "image\t图像",
            "text\t文本",
            "recognize\t识别",
            "result\t结果",
            "mode\t模式",
            "area\t区域",
            "full\t全屏",
            "auto\t自动",
            "manual\t手动",
            "dictionary\t词典",
            "install\t安装",
            "uninstall\t卸载",
            "version\t版本",
            "size\t大小",
            "time\t时间",
            "today\t今天",
            "now\t现在",
            "please\t请",
            "thanks\t谢谢",
            "yes\t是",
            "no\t否"
        )
        val filler = buildString {
            for (i in 0 until 12000) {
                append("w")
                append(i)
                append('\t')
                append("词")
                append(i)
                append('\n')
            }
        }
        return baseEntries.joinToString(separator = "\n", postfix = "\n") + filler
    }
}

class OfflineDictionaryEngine(
    context: Context
) : TranslationEngine {
    override val id: String = "微软离线"
    private val appContext: Context = context.applicationContext
    private val translatorLock = Any()
    private val translators: MutableMap<String, Translator> = LinkedHashMap()

    override suspend fun translate(
        text: String,
        sourceLanguage: String?,
        targetLanguage: String,
        settings: SettingsState
    ): String {
        val normalized = text.trim()
        if (normalized.isEmpty()) return ""
        val targetTag = mapUiLanguageToTag(targetLanguage) ?: return legacyDictionaryTranslate(normalized)
        val sourceTag = mapUiLanguageToTag(sourceLanguage) ?: detectLanguageCode(normalized)
        if (sourceTag == targetTag) return normalized

        val sourceMl = toMlKitLanguage(sourceTag) ?: return legacyDictionaryTranslate(normalized)
        val targetMl = toMlKitLanguage(targetTag) ?: return legacyDictionaryTranslate(normalized)

        val key = "$sourceTag->$targetTag"
        val translator = synchronized(translatorLock) {
            translators[key] ?: Translation.getClient(
                TranslatorOptions.Builder()
                    .setSourceLanguage(sourceMl)
                    .setTargetLanguage(targetMl)
                    .build()
            ).also { translators[key] = it }
        }

        return runCatching {
            withContext(Dispatchers.IO) {
                translator.downloadModelIfNeeded(DownloadConditions.Builder().build()).await()
                translator.translate(normalized).await().trim()
            }
        }.getOrElse { legacyDictionaryTranslate(normalized) }
    }

    private suspend fun legacyDictionaryTranslate(text: String): String {
        val dict = OfflineDictionaryPackage.load(appContext)
        if (dict.isEmpty()) return ""

        val lower = text.lowercase()
        dict[lower]?.let { return it }

        val tokens = lower.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (tokens.size in 2..6) {
            var hit = false
            val out = tokens.joinToString(" ") { token ->
                val v = dict[token]
                if (v != null) hit = true
                v ?: token
            }
            if (hit) return out
        }
        return ""
    }
}
