package com.example.mytransl.data.translation

import com.example.mytransl.domain.translation.TranslationCache

class LruTranslationCache(
    capacity: Int
) : TranslationCache {
    @Volatile
    private var capacity: Int = capacity.coerceAtLeast(1)

    private val map = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > this@LruTranslationCache.capacity
        }
    }

    fun updateCapacity(newCapacity: Int) {
        capacity = newCapacity.coerceAtLeast(1)
        synchronized(this) {
            while (map.size > capacity) {
                val firstKey = map.entries.iterator().next().key
                map.remove(firstKey)
            }
        }
    }

    @Synchronized
    override fun get(key: String): String? = map[key]

    @Synchronized
    override fun put(key: String, value: String) {
        map[key] = value
    }
}

