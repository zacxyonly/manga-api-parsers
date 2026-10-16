package com.mangaapi

import com.mangaapi.auth.KeyTier
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlin.test.*

class ApplicationTest {

    @Test
    fun `test health endpoint returns ok`() = testApplication {
        application {
            module()
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains(""""status":"ok""""))
    }

    @Test
    fun `test landing page returns html`() = testApplication {
        application {
            module()
        }

        val response = client.get("/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("api-manga"))
    }

    @Test
    fun `test api without key returns 401`() = testApplication {
        application {
            module()
        }

        val response = client.get("/api/sources")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun `test api with valid key succeeds`() = testApplication {
        application {
            module()
        }

        val key = keyManager.generate("test-read", KeyTier.READ)

        // Test with X-Api-Key header
        val headerResponse = client.get("/api/sources") {
            header("X-Api-Key", key.key)
        }
        assertEquals(HttpStatusCode.OK, headerResponse.status)

        // Test with ?api_key query parameter
        val queryResponse = client.get("/api/sources?api_key=${key.key}")
        assertEquals(HttpStatusCode.OK, queryResponse.status)

        // Test with Authorization: Bearer
        val bearerResponse = client.get("/api/sources") {
            header(HttpHeaders.Authorization, "Bearer ${key.key}")
        }
        assertEquals(HttpStatusCode.OK, bearerResponse.status)
    }

    @Test
    fun `test admin route forbidden for read key`() = testApplication {
        application {
            module()
        }

        val readKey = keyManager.generate("read-only", KeyTier.READ)
        val response = client.get("/admin/keys") {
            header("X-Api-Key", readKey.key)
        }
        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `test admin route allowed for admin key`() = testApplication {
        application {
            module()
        }

        val adminKey = keyManager.generate("super-admin", KeyTier.ADMIN)
        val response = client.get("/admin/keys") {
            header("X-Api-Key", adminKey.key)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("super-admin"))
    }
}
