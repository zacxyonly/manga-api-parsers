package com.mangaapi

import com.mangaapi.auth.ApiKeyManager
import com.mangaapi.auth.KeyTier
import com.mangaapi.cache.ResponseCache
import com.mangaapi.context.MangaLoaderContextImpl
import com.mangaapi.models.ErrorDto
import com.mangaapi.routes.configureAdminRoutes
import com.mangaapi.routes.configureRouting
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.calllogging.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import kotlinx.serialization.json.Json
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes

// Shared instances — accessible across the whole app
lateinit var keyManager: ApiKeyManager
lateinit var responseCache: ResponseCache
val startTime = System.currentTimeMillis()

fun main() {
    embeddedServer(
        factory = Netty,
        port    = System.getenv("PORT")?.toIntOrNull() ?: 8080,
        host    = "0.0.0.0",
        module  = Application::module,
    ).start(wait = true)
}

fun Application.module() {
    // Init shared singletons
    keyManager    = ApiKeyManager()
    responseCache = ResponseCache()
    val mangaContext = MangaLoaderContextImpl()

    configureSerialization()
    configureCors()
    configureAuth()
    configureStatusPages()
    configureRateLimiting()
    configureCallLogging()
    configureRouting(mangaContext, responseCache, keyManager)
    configureAdminRoutes(keyManager, responseCache)

    val active = MangaParserSource.entries.count { !it.isBroken }
    log.info("api-manga v2.0 started — ${MangaParserSource.entries.size} sources ($active active)")
}

// ── JSON ──────────────────────────────────────────────────────────────────────
private fun Application.configureSerialization() {
    install(ContentNegotiation) {
        json(Json {
            prettyPrint      = false
            isLenient        = true
            ignoreUnknownKeys = true
            encodeDefaults   = true
            explicitNulls    = true
        })
    }
}

// ── CORS ──────────────────────────────────────────────────────────────────────
private fun Application.configureCors() {
    val origins = System.getenv("ALLOWED_ORIGINS")?.split(",")?.map { it.trim() }
    install(CORS) {
        if (origins.isNullOrEmpty()) {
            anyHost()
        } else {
            origins.forEach { allowHost(it) }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Delete)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader("X-Api-Key")
        allowCredentials = false
    }
}

// ── API Key Authentication ────────────────────────────────────────────────────
// Key bisa dikirim lewat tiga cara:
//   Header:  X-Api-Key: mapi_xxxx
//   Query:   ?api_key=mapi_xxxx
//   Bearer:  Authorization: Bearer mapi_xxxx
//
// The ApiKeyExtractorPlugin normalizes all three into a single call attribute
// (extractedApiKeyAttr) before Ktor's bearer authentication runs.
// The bearer providers then read from that attribute as their token source.

/** Call attribute that holds the raw API key string extracted from any input method. */
val extractedApiKeyAttr = io.ktor.util.AttributeKey<String>("ExtractedApiKey")

fun Application.configureAuth() {
    install(Authentication) {

        // auth name "apikey-read" : tier READ, FULL, atau ADMIN
        bearer("apikey-read") {
            // authHeader overrides how Ktor extracts the Bearer token from a request.
            // This lets us accept keys from X-Api-Key header and ?api_key= query param
            // in addition to the standard Authorization: Bearer header.
            // The ApiKeyExtractorPlugin normalizes all three into a call attribute;
            // we return it here as an HttpAuthHeader so Ktor passes it to authenticate{}.
            authHeader { call ->
                call.attributes.getOrNull(extractedApiKeyAttr)
                    ?.let { io.ktor.http.auth.HttpAuthHeader.Single("Bearer", it) }
            }
            authenticate { cred ->
                resolveKey(cred.token, minTier = KeyTier.READ)
            }
        }

        // auth name "apikey-full" : tier FULL atau ADMIN
        bearer("apikey-full") {
            authHeader { call ->
                call.attributes.getOrNull(extractedApiKeyAttr)
                    ?.let { io.ktor.http.auth.HttpAuthHeader.Single("Bearer", it) }
            }
            authenticate { cred ->
                resolveKey(cred.token, minTier = KeyTier.FULL)
            }
        }

        // auth name "apikey-admin" : hanya tier ADMIN
        bearer("apikey-admin") {
            authHeader { call ->
                call.attributes.getOrNull(extractedApiKeyAttr)
                    ?.let { io.ktor.http.auth.HttpAuthHeader.Single("Bearer", it) }
            }
            authenticate { cred ->
                resolveKey(cred.token, minTier = KeyTier.ADMIN)
            }
        }
    }
}

/**
 * Resolve API key from Bearer token.
 * Ktor's BearerTokenAuth reads Authorization: Bearer <token>.
 * We also check X-Api-Key header and ?api_key= query param via a custom plugin below.
 */
private fun resolveKey(token: String, minTier: KeyTier): Principal? {
    val apiKey = keyManager.validate(token) ?: return null
    if (!apiKey.active) return null
    // Check tier level (ADMIN >= FULL >= READ)
    if (apiKey.tier.level < minTier.level) return null
    return ApiKeyPrincipal(apiKey)
}

/** Custom principal that carries the validated ApiKey */
class ApiKeyPrincipal(val apiKey: com.mangaapi.auth.ApiKey) : Principal

// Tier level helper — higher = more permissions
val KeyTier.level: Int get() = when (this) {
    KeyTier.READ  -> 1
    KeyTier.FULL  -> 2
    KeyTier.ADMIN -> 3
}

// ── Auth Extractor Plugin ─────────────────────────────────────────────────────
// In Ktor 3.x request.headers is immutable — we cannot call .append() on it.
// Instead we store the resolved raw key in a call attribute (extractedApiKeyAttr)
// and override the bearer token provider via bearerTokenProvider so Ktor's
// built-in authentication picks up keys sent in X-Api-Key or ?api_key= as well.
val ApiKeyExtractorPlugin = createApplicationPlugin("ApiKeyExtractor") {
    onCall { call ->
        // Determine the raw key from all three possible sources
        val key = call.request.headers["Authorization"]
            ?.removePrefix("Bearer ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: call.request.headers["X-Api-Key"]
            ?: call.request.queryParameters["api_key"]
            ?: return@onCall

        // Store in a call attribute; Ktor's bearer token provider reads this
        // via the custom bearerTokenProvider installed on each bearer() block.
        call.attributes.put(extractedApiKeyAttr, key)
    }
}

// ── Status Pages ──────────────────────────────────────────────────────────────
private fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            call.application.log.error("Unhandled: ${cause.message}", cause)
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorDto("Internal server error", cause.message ?: cause.javaClass.simpleName),
            )
        }
        status(HttpStatusCode.NotFound) { call, _ ->
            call.respond(HttpStatusCode.NotFound, ErrorDto("Route not found. Lihat GET / untuk dokumentasi."))
        }
        status(HttpStatusCode.Unauthorized) { call, _ ->
            call.respond(
                HttpStatusCode.Unauthorized,
                ErrorDto(
                    "API key diperlukan.",
                    "Kirim via header: X-Api-Key: mapi_xxx  atau  Authorization: Bearer mapi_xxx  atau  ?api_key=mapi_xxx"
                ),
            )
        }
        status(HttpStatusCode.Forbidden) { call, _ ->
            call.respond(HttpStatusCode.Forbidden, ErrorDto("Tier API key tidak mencukupi untuk endpoint ini."))
        }
        status(HttpStatusCode.TooManyRequests) { call, _ ->
            call.respond(HttpStatusCode.TooManyRequests, ErrorDto("Rate limit tercapai. Coba lagi nanti."))
        }
    }
}

// ── Rate Limiting ─────────────────────────────────────────────────────────────
// Per-key rate limiting: ADMIN=unlimited, FULL=120/min, READ=60/min
private fun Application.configureRateLimiting() {
    install(RateLimit) {
        register(RateLimitName("read")) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Api-Key"]
                    ?: call.request.queryParameters["api_key"]
                    ?: call.request.headers["Authorization"]?.removePrefix("Bearer ")
                    ?: call.request.headers["X-Forwarded-For"]?.split(",")?.first()?.trim()
                    ?: call.request.local.remoteHost
            }
        }
        register(RateLimitName("full")) {
            rateLimiter(limit = 120, refillPeriod = 1.minutes)
            requestKey { call ->
                call.request.headers["X-Api-Key"]
                    ?: call.request.queryParameters["api_key"]
                    ?: call.request.local.remoteHost
            }
        }
    }
}

// ── Call Logging ──────────────────────────────────────────────────────────────
private fun Application.configureCallLogging() {
    install(CallLogging) {
        level = Level.INFO
        format { call ->
            val status  = call.response.status()?.value ?: 0
            val method  = call.request.httpMethod.value
            val path    = call.request.uri
            val keyName = call.principal<ApiKeyPrincipal>()?.apiKey?.name ?: "anon"
            "$method $path -> $status [$keyName]"
        }
    }
}
