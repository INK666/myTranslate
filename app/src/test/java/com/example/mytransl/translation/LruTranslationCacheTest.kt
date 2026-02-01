package com.example.mytransl.translation

import com.example.mytransl.data.translation.LruTranslationCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LruTranslationCacheTest {
    @Test
    fun evictsLeastRecentlyUsed() {
        val cache = LruTranslationCache(2)
        cache.put("a", "1")
        cache.put("b", "2")
        cache.get("a")
        cache.put("c", "3")

        assertEquals("1", cache.get("a"))
        assertNull(cache.get("b"))
        assertEquals("3", cache.get("c"))
    }
}

