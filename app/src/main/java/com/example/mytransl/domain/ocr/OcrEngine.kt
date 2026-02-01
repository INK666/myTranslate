package com.example.mytransl.domain.ocr

import android.graphics.Bitmap

interface OcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<TextBlock>
}

interface PreferredLanguageAwareOcrEngine : OcrEngine {
    var preferredLanguage: String?
}
