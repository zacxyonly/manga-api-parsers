package com.mangaapi.cache

import java.util.concurrent.ConcurrentHashMap

/**
 * Simple in-memory TTL cache.
 *
 * Prevents hammering the upstream manga sites on repeated identical requests.
 * Uses a ConcurrentHashMap — no external dependencies needed.
 *
 * Default TTLs (configurable via env):
 *   - Lists (popular/latest/search) : 5 minutes
 *   - Manga detail + chapters        : 10 minutes
 *   - Tags / filter options          : 60 minutes  (very rarely changes)
 *   - Page URLs                      : 30 minutes
 */
class ResponseCache {

    private data class Entry(val value: Any, val expiresAt: Long)

    private val store = ConcurrentHashMap<String, Entry>()

    // TTLs in milliseconds (read from env, fallback to defaults)
    val listTtlMs:    Long = (System.getenv("CACHE_TTL_LIST_SEC")?.toLongOrNull()   ?: 300L)  * 1000
    val detailTtlMs:  Long = (System.getenv("CACHE_TTL_DETAIL_SEC")?.toLongOrNull() ?: 600L)  * 1000
    val tagsTtlMs:    Long = (System.getenv("CACHE_TTL_TAGS_SEC")?.toLongOrNull()   ?: 3600L) * 1000
    val pagesTtlMs:   Long = (System.getenv("CACHE_TTL_PAGES_SEC")?.toLongOrNull()  ?: 1800L) * 1000

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        val entry = store[key] ?: return null
        if (System.currentTimeMillis() > entry.expiresAt) {
            store.remove(key)
            return null
        }
        return entry.value as? T
    }

    fun set(key: String, value: Any, ttlMs: Long) {
        store[key] = Entry(value, System.currentTimeMillis() + ttlMs)
    }

    fun invalidate(key: String) { store.remove(key) }

    fun invalidatePrefix(prefix: String) {
        store.keys.filter { it.startsWith(prefix) }.forEach { store.remove(it) }
    }

    fun flush() { store.clear() }

    fun stats(): CacheStats {
        val now    = System.currentTimeMillis()
        val total  = store.size
        val active = store.values.count { it.expiresAt > now }
        return CacheStats(total = total, active = active, expired = total - active)
    }
}

data class CacheStats(val total: Int, val active: Int, val expired: Int)
