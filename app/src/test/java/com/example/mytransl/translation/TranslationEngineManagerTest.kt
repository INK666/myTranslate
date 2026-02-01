package com.example.mytransl.translation

import com.example.mytransl.data.settings.SettingsState
import com.example.mytransl.domain.translation.TranslationCache
import com.example.mytransl.domain.translation.TranslationEngine
import com.example.mytransl.domain.translation.TranslationEngineManager
import org.junit.Assert.assertEquals
import org.junit.Test

class TranslationEngineManagerTest {
    @Test
    fun returnsOriginalTextWhenEngineFails() {
        val cache = NoopCache()
        val engines = mapOf(
            "E1" to FailingEngine("E1"),
            "E2" to EchoEngine("E2")
        )
        val manager = TranslationEngineManager(engines, cache)
        val settings = SettingsState(
            defaultEngine = "E1"
        )

        val result = kotlinx.coroutines.runBlocking {
            manager.translate("hello", null, "中文", settings)
        }
        assertEquals("hello", result)
    }

    @Test
    fun usesCacheWhenEnabled() {
        val cache = MapCache()
        val engines = mapOf(
            "E1" to EchoEngine("E1")
        )
        val manager = TranslationEngineManager(engines, cache)
        val settings = SettingsState(
            defaultEngine = "E1",
            cacheEnabled = true
        )

        val first = kotlinx.coroutines.runBlocking {
            manager.translate("hello", null, "中文", settings)
        }
        val second = kotlinx.coroutines.runBlocking {
            manager.translate("hello", null, "中文", settings)
        }

        assertEquals(first, second)
        assertEquals(1, cache.putCount)
    }

    private class NoopCache : TranslationCache {
        override fun get(key: String): String? = null
        override fun put(key: String, value: String) = Unit
    }

    private class MapCache : TranslationCache {
        private val map = LinkedHashMap<String, String>()
        var putCount: Int = 0
            private set

        override fun get(key: String): String? = map[key]

        override fun put(key: String, value: String) {
            putCount += 1
            map[key] = value
        }
    }

    private class EchoEngine(
        override val id: String
    ) : TranslationEngine {
        override suspend fun translate(
            text: String,
            sourceLanguage: String?,
            targetLanguage: String,
            settings: SettingsState
        ): String = "$id:$text"
    }

    private class FailingEngine(
        override val id: String
    ) : TranslationEngine {
        override suspend fun translate(
            text: String,
            sourceLanguage: String?,
            targetLanguage: String,
            settings: SettingsState
        ): String {
            throw IllegalStateException("fail")
        }
    }

    private class InvalidOutputEngine(
        override val id: String
    ) : TranslationEngine {
        override suspend fun translate(
            text: String,
            sourceLanguage: String?,
            targetLanguage: String,
            settings: SettingsState
        ): String = text // Returns original text
    }
}
