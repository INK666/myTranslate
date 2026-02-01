package com.example.mytransl.domain.translation

interface TranslationCache {
    fun get(key: String): String?
    fun put(key: String, value: String)
}

