package com.mangaapi.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * API Key manager — in-memory fast lookups with asynchronous debounced persistence.
 *
 * Keys are stored in JSON at API_KEYS_FILE (default: ./data/api_keys.json).
 * On startup, keys are loaded from disk into memory.
 * - Validation & stats counter updates are strictly in-memory (no disk I/O on request path).
 * - Stats are debounced and flushed to disk periodically (every 30 seconds) or on JVM shutdown.
 * - Administrative mutations (add/revoke) persist immediately.
 * - Writes use atomic replace (write to temp file then atomic move) to prevent file corruption.
 *
 * Key format: "mapi_" + 32 random bytes base64url → "mapi_<43 chars>"
 *
 * Tiers:
 *  - ADMIN  : manage keys, see all stats, unlimited rate limit
 *  - FULL   : access all endpoints including proxy
 *  - READ   : access browse/search/detail (no proxy)
 */
class ApiKeyManager(
    private val dataFile: File = File(System.getenv("API_KEYS_FILE") ?: "data/api_keys.json"),
    autoBootstrap: Boolean = true,
    private val flushIntervalSec: Long = 30L,
) : Closeable {

    private val logger   = LoggerFactory.getLogger(ApiKeyManager::class.java)
    private val random   = SecureRandom()
    private val json     = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val keys     = ConcurrentHashMap<String, ApiKey>()
    private val dirty    = AtomicBoolean(false)

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "api-key-flusher").apply { isDaemon = true }
    }

    private val shutdownHook = Thread {
        flush()
    }

    init {
        dataFile.parentFile?.mkdirs()
        load()

        if (autoBootstrap && keys.isEmpty()) {
            val adminKey = generate("default-admin", KeyTier.ADMIN)
            logger.warn("═══════════════════════════════════════════════════════")
            logger.warn("  NO API KEYS FOUND — generated initial ADMIN key:")
            logger.warn("  ${adminKey.key}")
            logger.warn("  Save this key! It will NOT be shown again.")
            logger.warn("  Set API_KEYS_FILE env to persist across restarts.")
            logger.warn("═══════════════════════════════════════════════════════")
        }

        if (flushIntervalSec > 0) {
            scheduler.scheduleWithFixedDelay(
                { flush() },
                flushIntervalSec,
                flushIntervalSec,
                TimeUnit.SECONDS,
            )
        }

        Runtime.getRuntime().addShutdownHook(shutdownHook)
    }

    /**
     * Validate a key and return it (null = invalid/revoked).
     * Strictly in-memory: updates usage stats without blocking on disk I/O.
     */
    fun validate(rawKey: String): ApiKey? {
        val current = keys[rawKey] ?: return null
        if (!current.active) return null

        val updated = current.copy(
            lastUsed   = System.currentTimeMillis(),
            totalCalls = current.totalCalls + 1,
        )
        keys[rawKey] = updated
        dirty.set(true)
        return updated
    }

    /** Generate a new API key and persist immediately */
    fun generate(name: String, tier: KeyTier): ApiKey {
        val raw = buildString {
            append("mapi_")
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            append(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
        val key = ApiKey(
            key        = raw,
            name       = name,
            tier       = tier,
            createdAt  = System.currentTimeMillis(),
            lastUsed   = null,
            totalCalls = 0L,
            active     = true,
        )
        keys[raw] = key
        persist()
        logger.info("API key created: name='$name' tier=${tier.name}")
        return key
    }

    /** Revoke a key and persist immediately */
    fun revoke(rawKey: String): Boolean {
        val key = keys[rawKey] ?: return false
        keys[rawKey] = key.copy(active = false)
        persist()
        logger.info("API key revoked: name='${key.name}'")
        return true
    }

    /** List all keys (admin only) */
    fun listAll(): List<ApiKey> = keys.values.toList().sortedBy { it.createdAt }

    /** Flush in-memory updates to disk if dirty */
    fun flush() {
        if (dirty.compareAndSet(true, false)) {
            persist()
        }
    }

    override fun close() {
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook)
        } catch (_: IllegalStateException) {
            // Already shutting down
        }
        scheduler.shutdown()
        flush()
    }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun load() {
        if (!dataFile.exists() || dataFile.length() == 0L) return
        try {
            val stored = json.decodeFromString<List<ApiKey>>(dataFile.readText())
            stored.forEach { keys[it.key] = it }
            logger.info("Loaded ${keys.size} API key(s) from ${dataFile.path}")
        } catch (e: Exception) {
            logger.error("Failed to load API keys from ${dataFile.path}: ${e.message}")
        }
    }

    @Synchronized
    private fun persist() {
        val parent = dataFile.canonicalFile.parentFile ?: File(".")
        parent.mkdirs()
        val tempFile = File(parent, "${dataFile.name}.${System.nanoTime()}.tmp")
        try {
            tempFile.writeText(json.encodeToString(keys.values.toList()))
            try {
                Files.move(
                    tempFile.toPath(),
                    dataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (_: Exception) {
                Files.move(
                    tempFile.toPath(),
                    dataFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (e: Exception) {
            logger.error("Failed to persist API keys to ${dataFile.path}: ${e.message}")
        } finally {
            if (tempFile.exists()) {
                tempFile.delete()
            }
        }
    }
}

enum class KeyTier {
    READ,   // browse, search, detail — no proxy, no admin
    FULL,   // semua endpoint termasuk proxy
    ADMIN,  // FULL + manajemen key
}

@Serializable
data class ApiKey(
    val key:        String,
    val name:       String,
    val tier:       KeyTier,
    val createdAt:  Long,
    val lastUsed:   Long?,
    val totalCalls: Long,
    val active:     Boolean,
)
