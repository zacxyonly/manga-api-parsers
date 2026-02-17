package com.mangaapi.routes

import com.mangaapi.ApiKeyPrincipal
import com.mangaapi.auth.ApiKeyManager
import com.mangaapi.auth.KeyTier
import com.mangaapi.cache.ResponseCache
import com.mangaapi.models.*
import com.mangaapi.startTime
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.parsers.model.MangaParserSource

fun Application.configureAdminRoutes(keyManager: ApiKeyManager, cache: ResponseCache) {
    routing {
        route("/admin") {

            // ── GET /admin/status — server info (FULL+) ───────────────────────
            authenticate("apikey-full") {
                get("/status") {
                    val total  = MangaParserSource.entries.size
                    val active = MangaParserSource.entries.count { !it.isBroken }
                    call.respond(StatusDto(
                        status        = "ok",
                        version       = "2.0.0",
                        sourcesTotal  = total,
                        sourcesActive = active,
                        cache         = cache.stats().toDto(),
                        uptime        = (System.currentTimeMillis() - startTime) / 1000,
                    ))
                }
            }

            // ── GET /admin/me — info key sendiri ─────────────────────────────
            authenticate("apikey-read") {
                get("/me") {
                    val key = call.principal<ApiKeyPrincipal>()!!.apiKey
                    call.respond(key.toDto())
                }
            }

            // ── Semua route di bawah ini butuh ADMIN tier ─────────────────────
            authenticate("apikey-admin") {

                // GET /admin/keys — list semua key
                get("/keys") {
                    call.respond(keyManager.listAll().map { it.toDto() })
                }

                // POST /admin/keys — buat key baru
                // Body: { "name": "my-app", "tier": "FULL" }
                post("/keys") {
                    val req = runCatching { call.receive<CreateKeyRequest>() }.getOrElse {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorDto("Body tidak valid. Butuh: {\"name\": \"...\", \"tier\": \"READ|FULL|ADMIN\"}")
                        )
                    }
                    if (req.name.isBlank()) {
                        return@post call.respond(HttpStatusCode.BadRequest, ErrorDto("'name' tidak boleh kosong"))
                    }
                    val tier = runCatching { KeyTier.valueOf(req.tier.uppercase()) }.getOrElse {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            ErrorDto("Tier tidak valid: '${req.tier}'. Pilihan: READ, FULL, ADMIN")
                        )
                    }
                    val created = keyManager.generate(req.name, tier)
                    // Kembalikan full key hanya sekali saat pembuatan
                    call.respond(HttpStatusCode.Created, ApiKeyCreatedDto(
                        key  = created.key,
                        name = created.name,
                        tier = created.tier.name,
                    ))
                }

                // DELETE /admin/keys/{key} — revoke key
                delete("/keys/{key}") {
                    val rawKey = call.parameters["key"]
                        ?: return@delete call.respond(HttpStatusCode.BadRequest, ErrorDto("Missing key parameter"))
                    val revoked = keyManager.revoke(rawKey)
                    if (revoked) {
                        call.respond(mapOf("message" to "Key berhasil direvoke"))
                    } else {
                        call.respond(HttpStatusCode.NotFound, ErrorDto("Key tidak ditemukan"))
                    }
                }

                // POST /admin/cache/flush — bersihkan semua cache
                post("/cache/flush") {
                    cache.flush()
                    call.respond(mapOf("message" to "Cache berhasil dihapus"))
                }

                // POST /admin/cache/flush/{source} — flush cache satu source
                post("/cache/flush/{source}") {
                    val source = call.parameters["source"] ?: return@post call.respond(
                        HttpStatusCode.BadRequest, ErrorDto("Missing source parameter")
                    )
                    cache.invalidatePrefix("$source:")
                    call.respond(mapOf("message" to "Cache untuk source '$source' berhasil dihapus"))
                }

                // GET /admin/cache/stats — statistik cache
                get("/cache/stats") {
                    call.respond(cache.stats().toDto())
                }
            }
        }
    }
}

@Serializable
data class CreateKeyRequest(val name: String, val tier: String)
