package com.example.mytransl.data.ocr

import android.graphics.Bitmap
import com.example.mytransl.domain.ocr.PreferredLanguageAwareOcrEngine
import com.example.mytransl.domain.ocr.TextBlock

class PaddleOcrEngine : PreferredLanguageAwareOcrEngine {
    private val delegate = MlKitOcrEngine()

    override var preferredLanguage: String?
        get() = delegate.preferredLanguage
        set(value) {
            delegate.preferredLanguage = value
        }

    override suspend fun recognize(bitmap: Bitmap): List<TextBlock> {
        return delegate.recognize(bitmap)
    }
}
