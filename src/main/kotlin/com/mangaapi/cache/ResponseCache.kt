package com.mangaapi.cache

import kotlinx.coroutines.CompletableDeferred
import java.io.Closeable
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Thread-safe bounded LRU in-memory cache with Request Coalescing (SingleFlight).
 *
 * Prevents:
 * 1. Out-of-memory errors on high concurrency (bounded size via LRU eviction policy).
 * 2. Thundering Herd / Cache Stampede: simultaneous identical requests coalesce into
 *    a single upstream scrape via [getOrCompute].
 * 3. Memory leaks: background cleanup purges expired entries periodically.
 *
 * Default TTLs (configurable via env):
 *   - Lists (popular/latest/search) : 5 minutes
 *   - Manga detail + chapters        : 10 minutes
 *   - Tags / filter options          : 60 minutes
 *   - Page URLs                      : 30 minutes
 */
class ResponseCache(
    val maxEntries: Int = System.getenv("CACHE_MAX_ENTRIES")?.toIntOrNull() ?: 5000,
    cleanupIntervalSec: Long = 60L,
) : Closeable {

    private data class Entry(val value: Any, val expiresAt: Long)

    // Bounded LRU store with access-order eviction
    private val store: MutableMap<String, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<String, Entry>(128, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean {
                return size > maxEntries
            }
        }
    )

    // In-flight requests map for Request Coalescing (SingleFlight)
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<Any>>()

    // TTLs in milliseconds (read from env, fallback to defaults)
    val listTtlMs:   Long = (System.getenv("CACHE_TTL_LIST_SEC")?.toLongOrNull()   ?: 300L)  * 1000
    val detailTtlMs: Long = (System.getenv("CACHE_TTL_DETAIL_SEC")?.toLongOrNull() ?: 600L)  * 1000
    val tagsTtlMs:   Long = (System.getenv("CACHE_TTL_TAGS_SEC")?.toLongOrNull()   ?: 3600L) * 1000
    val pagesTtlMs:  Long = (System.getenv("CACHE_TTL_PAGES_SEC")?.toLongOrNull()  ?: 1800L) * 1000

    private val cleaner: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "cache-cleaner").apply { isDaemon = true }
    }

    init {
        if (cleanupIntervalSec > 0) {
            cleaner.scheduleWithFixedDelay(
                { evictExpired() },
                cleanupIntervalSec,
                cleanupIntervalSec,
                TimeUnit.SECONDS,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: String): T? {
        synchronized(store) {
            val entry = store[key] ?: return null
            if (System.currentTimeMillis() > entry.expiresAt) {
                store.remove(key)
                return null
            }
            return entry.value as? T
        }
    }

    fun set(key: String, value: Any, ttlMs: Long) {
        val expiresAt = System.currentTimeMillis() + ttlMs
        synchronized(store) {
            store[key] = Entry(value, expiresAt)
        }
    }

    /**
     * Request Coalescing (SingleFlight).
     * If multiple coroutines request the same key concurrently while not yet cached:
     * - Only one executes [compute].
     * - The rest wait for the deferred result and receive the same value.
     */
    suspend fun <T : Any> getOrCompute(key: String, ttlMs: Long, compute: suspend () -> T): T {
        get<T>(key)?.let { return it }

        val myDeferred = CompletableDeferred<Any>()
        val existing = inFlight.putIfAbsent(key, myDeferred)

        if (existing != null) {
            @Suppress("UNCHECKED_CAST")
            return existing.await() as T
        }

        return try {
            val result = compute()
            set(key, result, ttlMs)
            myDeferred.complete(result)
            result
        } catch (t: Throwable) {
            myDeferred.completeExceptionally(t)
            throw t
        } finally {
            inFlight.remove(key)
        }
    }

    fun invalidate(key: String) {
        store.remove(key)
        inFlight.remove(key)
    }

    fun invalidatePrefix(prefix: String) {
        synchronized(store) {
            store.keys.removeIf { it.startsWith(prefix) }
        }
    }

    fun flush() {
        store.clear()
        inFlight.clear()
    }

    fun evictExpired() {
        val now = System.currentTimeMillis()
        synchronized(store) {
            store.entries.removeIf { it.value.expiresAt <= now }
        }
    }

    fun stats(): CacheStats {
        val now = System.currentTimeMillis()
        synchronized(store) {
            val total = store.size
            val active = store.values.count { it.expiresAt > now }
            return CacheStats(total = total, active = active, expired = total - active)
        }
    }

    override fun close() {
        cleaner.shutdown()
        flush()
    }
}

data class CacheStats(val total: Int, val active: Int, val expired: Int)
