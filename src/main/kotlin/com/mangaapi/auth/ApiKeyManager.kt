package com.mangaapi.auth

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * API Key manager — file-backed, thread-safe.
 *
 * Keys are stored in JSON at API_KEYS_FILE (default: ./data/api_keys.json).
 * On startup, keys are loaded from disk. Any mutation (add/revoke) is
 * immediately persisted.
 *
 * Key format: "mapi_" + 32 random bytes base64url → "mapi_<43 chars>"
 *
 * Tiers:
 *  - ADMIN  : manage keys, see all stats
 *  - FULL   : access all endpoints
 *  - READ   : access browse/search/detail (no proxy)
 */
class ApiKeyManager {

    private val logger   = LoggerFactory.getLogger(ApiKeyManager::class.java)
    private val random   = SecureRandom()
    private val json     = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val dataFile = File(System.getenv("API_KEYS_FILE") ?: "data/api_keys.json")
    private val keys     = ConcurrentHashMap<String, ApiKey>()

    init {
        dataFile.parentFile?.mkdirs()
        load()

        // Bootstrap: if no keys exist, generate one ADMIN key and print it
        if (keys.isEmpty()) {
            val adminKey = generate("default-admin", KeyTier.ADMIN)
            logger.warn("═══════════════════════════════════════════════════════")
            logger.warn("  NO API KEYS FOUND — generated initial ADMIN key:")
            logger.warn("  ${adminKey.key}")
            logger.warn("  Save this key! It will NOT be shown again.")
            logger.warn("  Set API_KEYS_FILE env to persist across restarts.")
            logger.warn("═══════════════════════════════════════════════════════")
        }
    }

    /** Validate a key and return it (null = invalid/revoked) */
    fun validate(rawKey: String): ApiKey? {
        val key = keys[rawKey] ?: return null
        if (!key.active) return null
        // Update usage stats (fire-and-forget, minor race ok)
        keys[rawKey] = key.copy(
            lastUsed   = System.currentTimeMillis(),
            totalCalls = key.totalCalls + 1,
        )
        persist()
        return keys[rawKey]
    }

    /** Generate a new API key */
    fun generate(name: String, tier: KeyTier): ApiKey {
        val raw = buildString {
            append("mapi_")
            val bytes = ByteArray(32).also { random.nextBytes(it) }
            append(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes))
        }
        val key = ApiKey(
            key       = raw,
            name      = name,
            tier      = tier,
            createdAt = System.currentTimeMillis(),
            lastUsed  = null,
            totalCalls = 0L,
            active    = true,
        )
        keys[raw] = key
        persist()
        logger.info("API key created: name='$name' tier=${tier.name}")
        return key
    }

    /** Revoke a key */
    fun revoke(rawKey: String): Boolean {
        val key = keys[rawKey] ?: return false
        keys[rawKey] = key.copy(active = false)
        persist()
        logger.info("API key revoked: name='${key.name}'")
        return true
    }

    /** List all keys (admin only) */
    fun listAll(): List<ApiKey> = keys.values.toList().sortedBy { it.createdAt }

    // ── Persistence ──────────────────────────────────────────────────────────

    private fun load() {
        if (!dataFile.exists()) return
        try {
            val stored = json.decodeFromString<List<ApiKey>>(dataFile.readText())
            stored.forEach { keys[it.key] = it }
            logger.info("Loaded ${keys.size} API key(s) from ${dataFile.path}")
        } catch (e: Exception) {
            logger.error("Failed to load API keys from ${dataFile.path}: ${e.message}")
        }
    }

    private fun persist() {
        try {
            dataFile.writeText(json.encodeToString(keys.values.toList()))
        } catch (e: Exception) {
            logger.error("Failed to persist API keys: ${e.message}")
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
