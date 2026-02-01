package com.example.mytransl.data.ocr

import android.graphics.Bitmap
import android.graphics.RectF
import com.example.mytransl.domain.ocr.PreferredLanguageAwareOcrEngine
import com.example.mytransl.domain.ocr.TextBlock
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MlKitOcrEngine : PreferredLanguageAwareOcrEngine {
    @Volatile
    override var preferredLanguage: String? = null

    private val latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val chineseRecognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val koreanRecognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    override suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val pref = preferredLanguage?.trim().orEmpty()
        val primary = when (pref) {
            "中文" -> chineseRecognizer
            "日语" -> japaneseRecognizer
            "韩语" -> koreanRecognizer
            "英语", "法语", "德语", "西班牙语", "俄语" -> latinRecognizer
            else -> null
        }

        if (primary != null) {
            val result = primary.process(image).await()
            return toBlocks(result.textBlocks)
        }

        val chinese = runCatching { chineseRecognizer.process(image).await() }.getOrNull()
        val latin = runCatching { latinRecognizer.process(image).await() }.getOrNull()

        val chineseBlocks = chinese?.textBlocks.orEmpty()
        val latinBlocks = latin?.textBlocks.orEmpty()
        val chineseScore = score(chineseBlocks)
        val latinScore = score(latinBlocks)

        var bestBlocks = if (chineseScore >= latinScore) chineseBlocks else latinBlocks
        var bestScore = maxOf(chineseScore, latinScore)

        if (bestBlocks.isEmpty() || bestScore < 20) {
            val japanese = runCatching { japaneseRecognizer.process(image).await() }.getOrNull()
            val korean = runCatching { koreanRecognizer.process(image).await() }.getOrNull()

            val japaneseBlocks = japanese?.textBlocks.orEmpty()
            val koreanBlocks = korean?.textBlocks.orEmpty()

            val japaneseScore = score(japaneseBlocks)
            if (japaneseScore > bestScore) {
                bestScore = japaneseScore
                bestBlocks = japaneseBlocks
            }

            val koreanScore = score(koreanBlocks)
            if (koreanScore > bestScore) {
                bestBlocks = koreanBlocks
            }
        }

        return toBlocks(bestBlocks)
    }

    private fun toBlocks(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): List<TextBlock> {
        return blocks.flatMap { block ->
            block.lines.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                TextBlock(
                    text = line.text,
                    bounds = RectF(box)
                )
            }
        }
    }

    private fun score(blocks: List<com.google.mlkit.vision.text.Text.TextBlock>): Int {
        var total = 0
        for (b in blocks) {
            for (l in b.lines) {
                val t = l.text
                val (cjk, latin) = countCjkAndLatin(t)
                total += t.length + cjk * 2 + latin
            }
        }
        return total
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
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
