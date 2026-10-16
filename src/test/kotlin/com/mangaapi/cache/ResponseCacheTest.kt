package com.mangaapi.cache

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ResponseCacheTest {

    private lateinit var cache: ResponseCache

    @BeforeTest
    fun setUp() {
        cache = ResponseCache(maxEntries = 5, cleanupIntervalSec = 0)
    }

    @AfterTest
    fun tearDown() {
        cache.close()
    }

    @Test
    fun `test basic get and set`() {
        cache.set("key1", "value1", 5000)
        assertEquals("value1", cache.get<String>("key1"))
        assertNull(cache.get<String>("key2"))
    }

    @Test
    fun `test expiration`() {
        cache.set("shortLived", "value", 1) // 1ms TTL
        Thread.sleep(10)
        assertNull(cache.get<String>("shortLived"))
    }

    @Test
    fun `test bounded LRU eviction`() {
        // Cache has maxEntries = 5
        for (i in 1..5) {
            cache.set("k$i", "v$i", 60_000)
        }

        // Access k1 so it becomes recently used
        assertEquals("v1", cache.get<String>("k1"))

        // Add 6th item, should evict k2 (oldest unaccessed)
        cache.set("k6", "v6", 60_000)

        assertEquals("v1", cache.get<String>("k1"))
        assertEquals("v6", cache.get<String>("k6"))
        assertNull(cache.get<String>("k2"))
        assertTrue(cache.stats().total <= 5)
    }

    @Test
    fun `test request coalescing single flight`() = runTest {
        val counter = AtomicInteger(0)

        // Launch 10 concurrent requests for the exact same key
        val deferreds = (1..10).map {
            async {
                cache.getOrCompute("heavy_query", 60_000) {
                    delay(50) // simulate slow scraping
                    counter.incrementAndGet()
                    "coalesced_result"
                }
            }
        }

        val results = deferreds.awaitAll()

        // All 10 requests should get the same result
        assertEquals(10, results.size)
        results.forEach { assertEquals("coalesced_result", it) }

        // The expensive compute function should only have executed ONCE!
        assertEquals(1, counter.get())
    }

    @Test
    fun `test invalidate and flush`() {
        cache.set("source:a", "1", 60_000)
        cache.set("source:b", "2", 60_000)
        cache.set("other:c", "3", 60_000)

        cache.invalidatePrefix("source:")
        assertNull(cache.get<String>("source:a"))
        assertNull(cache.get<String>("source:b"))
        assertEquals("3", cache.get<String>("other:c"))

        cache.flush()
        assertEquals(0, cache.stats().total)
    }
}
