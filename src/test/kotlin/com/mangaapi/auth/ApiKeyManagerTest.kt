package com.mangaapi.auth

import java.io.File
import kotlin.test.*

class ApiKeyManagerTest {

    private lateinit var tempFile: File
    private lateinit var keyManager: ApiKeyManager

    @BeforeTest
    fun setUp() {
        tempFile = File.createTempFile("api_keys_test", ".json")
        tempFile.delete()
        keyManager = ApiKeyManager(dataFile = tempFile, autoBootstrap = false, flushIntervalSec = 0)
    }

    @AfterTest
    fun tearDown() {
        keyManager.close()
        tempFile.delete()
    }

    @Test
    fun `test generate and validate`() {
        val created = keyManager.generate("test-client", KeyTier.FULL)
        assertTrue(created.key.startsWith("mapi_"))
        assertEquals("test-client", created.name)
        assertEquals(KeyTier.FULL, created.tier)

        // Validate
        val validated = keyManager.validate(created.key)
        assertNotNull(validated)
        assertEquals(1L, validated.totalCalls)
        assertNotNull(validated.lastUsed)

        // Validate again updates totalCalls
        val validated2 = keyManager.validate(created.key)
        assertNotNull(validated2)
        assertEquals(2L, validated2.totalCalls)
    }

    @Test
    fun `test invalid or revoked key`() {
        assertNull(keyManager.validate("invalid_key"))

        val created = keyManager.generate("revokable", KeyTier.READ)
        assertNotNull(keyManager.validate(created.key))

        val revoked = keyManager.revoke(created.key)
        assertTrue(revoked)
        assertNull(keyManager.validate(created.key))
    }

    @Test
    fun `test flush persists to disk and reloads`() {
        val created = keyManager.generate("persistent-client", KeyTier.ADMIN)
        keyManager.validate(created.key)
        keyManager.flush()

        assertTrue(tempFile.exists())
        assertTrue(tempFile.length() > 0)

        // Create new manager instance with same file
        val manager2 = ApiKeyManager(dataFile = tempFile, autoBootstrap = false, flushIntervalSec = 0)
        val loaded = manager2.validate(created.key)
        assertNotNull(loaded)
        assertEquals("persistent-client", loaded.name)
        assertEquals(KeyTier.ADMIN, loaded.tier)
        manager2.close()
    }
}
