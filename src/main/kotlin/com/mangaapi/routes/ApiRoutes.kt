package com.mangaapi.routes

import com.mangaapi.auth.ApiKeyManager
import com.mangaapi.cache.ResponseCache
import com.mangaapi.context.CloudFlareProtectedException
import com.mangaapi.context.MangaLoaderContextImpl
import com.mangaapi.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.ratelimit.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.*
import org.slf4j.LoggerFactory
import java.net.URI

private val logger = LoggerFactory.getLogger("com.mangaapi.routes")

private val BLOCKED_HOSTS      = setOf("localhost", "127.0.0.1", "0.0.0.0", "::1")
private val BLOCKED_IP_PREFIXES = listOf(
    "10.", "192.168.",
    "172.16.", "172.17.", "172.18.", "172.19.", "172.20.", "172.21.",
    "172.22.", "172.23.", "172.24.", "172.25.", "172.26.", "172.27.",
    "172.28.", "172.29.", "172.30.", "172.31.",
    "169.254.",
)

fun Application.configureRouting(
    context:      MangaLoaderContextImpl,
    cache:        ResponseCache,
    keyManager:   ApiKeyManager,
) {
    routing {

        // ── GET / — landing page (no auth) ───────────────────────────────────
        get("/") {
            val total  = MangaParserSource.entries.size
            val active = MangaParserSource.entries.count { !it.isBroken }
            call.respondText(ContentType.Text.Html, HttpStatusCode.OK) { buildLandingPage(total, active) }
        }

        // ── GET /health (no auth) ─────────────────────────────────────────────
        get("/health") {
            call.respond(mapOf("status" to "ok", "version" to "2.0.0"))
        }

        // ── All /api/* routes require at least READ key ───────────────────────
        authenticate("apikey-read") {
            rateLimit(RateLimitName("read")) {
                route("/api") {

                    // GET /api/sources
                    get("/sources") {
                        val lang   = call.request.queryParameters["lang"]
                        val type   = call.request.queryParameters["type"]?.uppercase()
                        val broken = call.request.queryParameters["broken"]?.lowercase()

                        val cacheKey = "sources:$lang:$type:$broken"
                        val sources = cache.getOrCompute(cacheKey, cache.tagsTtlMs) {
                            MangaParserSource.entries
                                .filter { s -> lang   == null || s.locale == lang }
                                .filter { s -> type   == null || s.contentType.name == type }
                                .filter { s -> broken == null || s.isBroken.toString() == broken }
                                .map    { s ->
                                    val domain = runCatching { context.getParser(s).domain }.getOrNull()
                                    s.toDto(domain)
                                }
                        }
                        call.respond(sources)
                    }

                    // GET /api/home/{source} — popular + latest with Request Coalescing
                    get("/home/{source}") {
                        val source   = resolveSourceParam() ?: return@get
                        val cacheKey = "${source.name}:home"

                        val result = safeParserCall(source.name, "home") {
                            cache.getOrCompute(cacheKey, cache.listTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val parser = context.getParser(source)
                                    coroutineScope {
                                        val popular = if (SortOrder.POPULARITY in parser.availableSortOrders) {
                                            async {
                                                runCatching { parser.getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY) }
                                                    .getOrDefault(emptyList())
                                                    .take(10)
                                                    .map { it.toListItemDto() }
                                            }
                                        } else async { emptyList() }

                                        val latest = if (SortOrder.UPDATED in parser.availableSortOrders) {
                                            async {
                                                runCatching { parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY) }
                                                    .getOrDefault(emptyList())
                                                    .take(10)
                                                    .map { it.toListItemDto() }
                                            }
                                        } else async { emptyList() }

                                        mapOf(
                                            "popular" to popular.await(),
                                            "latest"  to latest.await(),
                                        )
                                    }
                                }
                            }
                        } ?: return@get

                        call.respond(result)
                    }

                    // GET /api/popular/{source}?page=1
                    get("/popular/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val page   = pageParam()
                        browseWithSort(context, cache, source, SortOrder.POPULARITY, page)
                    }

                    // GET /api/latest/{source}?page=1
                    get("/latest/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val page   = pageParam()
                        browseWithSort(context, cache, source, SortOrder.UPDATED, page)
                    }

                    // GET /api/newest/{source}?page=1
                    get("/newest/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val page   = pageParam()
                        browseWithSort(context, cache, source, SortOrder.NEWEST, page)
                    }

                    // GET /api/trending/{source}?page=1
                    get("/trending/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val page   = pageParam()
                        val parser = context.getParser(source)
                        val sort   = listOf(
                            SortOrder.POPULARITY_TODAY, SortOrder.POPULARITY_WEEK,
                            SortOrder.POPULARITY_MONTH, SortOrder.POPULARITY,
                        ).firstOrNull { it in parser.availableSortOrders }
                            ?: return@get call.respondError(
                                HttpStatusCode.NotImplemented,
                                "Source '${source.name}' tidak mendukung trending. " +
                                "Sort tersedia: ${parser.availableSortOrders.joinToString { it.name }}"
                            )
                        browseWithSort(context, cache, source, sort, page)
                    }

                    // GET /api/top-rated/{source}?page=1
                    get("/top-rated/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val page   = pageParam()
                        browseWithSort(context, cache, source, SortOrder.RATING, page)
                    }

                    // GET /api/alphabetical/{source}?page=1
                    get("/alphabetical/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val page   = pageParam()
                        browseWithSort(context, cache, source, SortOrder.ALPHABETICAL, page)
                    }

                    // GET /api/tags/{source}
                    get("/tags/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val cacheKey = "${source.name}:tags"

                        val dto = safeParserCall(source.name, "getFilterOptions") {
                            cache.getOrCompute(cacheKey, cache.tagsTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val parser = context.getParser(source)
                                    val opts = parser.getFilterOptions()
                                    FilterOptionsDto(
                                        tags                = opts.availableTags.map { it.toDto() },
                                        availableStates     = opts.availableStates.map { it.name },
                                        availableRatings    = opts.availableContentRating.map { it.name },
                                        availableTypes      = opts.availableContentTypes.map { it.name },
                                        availableSortOrders = parser.availableSortOrders.map { it.name },
                                    )
                                }
                            }
                        } ?: return@get

                        call.respond(dto)
                    }

                    // GET /api/filter/{source}?tag=action&state=ONGOING&sort=UPDATED&page=1
                    get("/filter/{source}") {
                        val source = resolveSourceParam() ?: return@get
                        val parser = context.getParser(source)
                        val page   = pageParam()
                        val offset = (page - 1) * PAGE_SIZE

                        val tagKeys = call.request.queryParameters.getAll("tag") ?: emptyList()
                        val state = call.request.queryParameters["state"]?.uppercase()
                            ?.let { runCatching { MangaState.valueOf(it) }.getOrNull() }
                        val rating = call.request.queryParameters["contentRating"]?.uppercase()
                            ?.let { runCatching { ContentRating.valueOf(it) }.getOrNull() }
                        val sortParam = call.request.queryParameters["sort"]?.uppercase()
                        val sort = sortParam
                            ?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() }
                            ?.takeIf { it in parser.availableSortOrders }
                            ?: listOf(SortOrder.UPDATED, SortOrder.POPULARITY)
                                .firstOrNull { it in parser.availableSortOrders }
                            ?: parser.availableSortOrders.first()

                        val cacheKey = "${source.name}:filter:${tagKeys.sorted()}:${state}:${rating}:${sort}:p$page"
                        val dto = safeParserCall(source.name, "filter") {
                            cache.getOrCompute(cacheKey, cache.listTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val tags = if (tagKeys.isNotEmpty()) {
                                        val allTags = parser.getFilterOptions().availableTags
                                        tagKeys.mapNotNull { k -> allTags.find { it.key == k } }.toSet()
                                    } else emptySet()

                                    val filter = MangaListFilter(
                                        tags          = tags,
                                        states        = if (state  != null) setOf(state)  else emptySet(),
                                        contentRating = if (rating != null) setOf(rating) else emptySet(),
                                    )
                                    parser.getList(offset, sort, filter).map { it.toListItemDto() }
                                }
                            }
                        } ?: return@get

                        call.respond(dto)
                    }

                    // GET /api/search?source=X&q=naruto&page=1
                    get("/search") {
                        val srcName = call.request.queryParameters["source"]
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing parameter: source")
                        val source = resolveSource(srcName)
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Unknown source: '$srcName'")
                        val q      = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                        val page   = pageParam()
                        val offset = (page - 1) * PAGE_SIZE
                        val parser = context.getParser(source)

                        val cacheKey = "${source.name}:search:${q ?: ""}:p$page"
                        val dto = safeParserCall(source.name, "search") {
                            cache.getOrCompute(cacheKey, cache.listTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val sort = when {
                                        q != null && SortOrder.RELEVANCE in parser.availableSortOrders -> SortOrder.RELEVANCE
                                        SortOrder.POPULARITY in parser.availableSortOrders              -> SortOrder.POPULARITY
                                        else -> parser.availableSortOrders.first()
                                    }
                                    parser.getList(offset, sort, MangaListFilter(query = q)).map { it.toListItemDto() }
                                }
                            }
                        } ?: return@get

                        call.respond(dto)
                    }

                    // GET /api/multi-search?sources=MGKOMIK,MANGADEX&q=naruto
                    get("/multi-search") {
                        val sourcesParam = call.request.queryParameters["sources"]
                            ?: return@get call.respondError(HttpStatusCode.BadRequest,
                                "Missing parameter: sources (comma-separated, e.g. MGKOMIK,MANGADEX)")
                        val q = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing parameter: q")

                        val sourceList = sourcesParam.split(",")
                            .map { it.trim().uppercase() }
                            .take(5)
                            .mapNotNull { resolveSource(it) }

                        if (sourceList.isEmpty()) {
                            return@get call.respondError(HttpStatusCode.BadRequest, "Tidak ada source valid yang diberikan")
                        }

                        val results = withContext(Dispatchers.IO) {
                            coroutineScope {
                                sourceList.map { source ->
                                    async {
                                        try {
                                            val parser = context.getParser(source)
                                            val sort   = if (SortOrder.RELEVANCE in parser.availableSortOrders)
                                                SortOrder.RELEVANCE else parser.availableSortOrders.first()
                                            val list   = parser.getList(0, sort, MangaListFilter(query = q))
                                            MultiSearchResultDto(source = source.name, results = list.map { it.toListItemDto() })
                                        } catch (e: Exception) {
                                            MultiSearchResultDto(source = source.name, results = emptyList(), error = e.message)
                                        }
                                    }
                                }.awaitAll()
                            }
                        }
                        call.respond(results)
                    }

                    // GET /api/manga/{source}/{id}?url=...
                    get("/manga/{source}/{id}") {
                        val source = resolveSourceFromPath() ?: return@get
                        val id     = mangaIdParam() ?: return@get
                        val url    = urlParam() ?: return@get

                        val cacheKey = "${source.name}:detail:$id"
                        val dto = safeParserCall(source.name, "getDetails") {
                            cache.getOrCompute(cacheKey, cache.detailTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val detail = fetchMangaDetail(context, source, id, url)
                                    detail.toDetailDto()
                                }
                            }
                        } ?: return@get

                        call.respond(dto)
                    }

                    // GET /api/chapters/{source}/{mangaId}?url=...
                    get("/chapters/{source}/{mangaId}") {
                        val source = resolveSourceFromPath() ?: return@get
                        val id     = call.parameters["mangaId"]?.toLongOrNull()
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "'mangaId' harus Long integer")
                        val url    = urlParam() ?: return@get

                        val cacheKey = "${source.name}:chapters:$id"
                        val dto = safeParserCall(source.name, "getChapters") {
                            cache.getOrCompute(cacheKey, cache.detailTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val detail = fetchMangaDetail(context, source, id, url)
                                    detail.chapters.orEmpty().map { it.toDto() }
                                }
                            }
                        } ?: return@get

                        call.respond(dto)
                    }

                    // GET /api/related/{source}/{id}?url=...
                    get("/related/{source}/{id}") {
                        val source = resolveSourceFromPath() ?: return@get
                        val id     = mangaIdParam() ?: return@get
                        val url    = urlParam() ?: return@get
                        val parser = context.getParser(source)
                        val pubUrl = if (url.startsWith("http")) url else "https://${parser.domain}$url"

                        val related = safeParserCall(source.name, "getRelatedManga") {
                            withContext(Dispatchers.IO) {
                                parser.getRelatedManga(buildStubManga(id, url, pubUrl, source))
                            }
                        } ?: return@get
                        call.respond(related.map { it.toListItemDto() })
                    }

                    // GET /api/pages/{source}/{chapterId}?url=...
                    get("/pages/{source}/{chapterId}") {
                        val source    = resolveSourceFromPath() ?: return@get
                        val chapterId = call.parameters["chapterId"]?.toLongOrNull()
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "'chapterId' harus Long integer")
                        val url       = urlParam() ?: return@get

                        val cacheKey = "${source.name}:pages:$chapterId"
                        val dto = safeParserCall(source.name, "getPages") {
                            cache.getOrCompute(cacheKey, cache.pagesTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val chapter = MangaChapter(
                                        id = chapterId, title = null, number = 0f, volume = 0,
                                        url = url, scanlator = null, uploadDate = 0L, branch = null, source = source,
                                    )
                                    val pages = context.getParser(source).getPages(chapter)
                                    pages.map { it.toDto() }
                                }
                            }
                        } ?: return@get

                        call.respond(dto)
                    }

                    // GET /api/page-url/{source}?pageId=...&url=...
                    get("/page-url/{source}") {
                        val source  = resolveSourceFromPath() ?: return@get
                        val pageId  = call.request.queryParameters["pageId"]?.toLongOrNull()
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing/invalid: pageId")
                        val pageUrl = call.request.queryParameters["url"]
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing: url")

                        val cacheKey = "${source.name}:pageurl:$pageId"
                        val dto = safeParserCall(source.name, "getPageUrl") {
                            cache.getOrCompute(cacheKey, cache.pagesTtlMs) {
                                withContext(Dispatchers.IO) {
                                    val direct = context.getParser(source).getPageUrl(MangaPage(pageId, pageUrl, null, source))
                                    mapOf("url" to direct)
                                }
                            }
                        } ?: return@get

                        call.respond(dto)
                    }
                }
            }
        }

        // ── Proxy needs FULL tier ─────────────────────────────────────────────
        authenticate("apikey-full") {
            rateLimit(RateLimitName("full")) {
                route("/api") {
                    get("/proxy") {
                        val targetUrl = call.request.queryParameters["url"]
                            ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing: url")

                        val err = validateProxyUrl(targetUrl)
                        if (err != null) return@get call.respondError(HttpStatusCode.BadRequest, err)

                        val referer = call.request.queryParameters["referer"]
                            ?: call.request.queryParameters["source"]?.let { s ->
                                resolveSource(s)?.let { "https://${context.getParser(it).domain}/" }
                            }

                        val req = okhttp3.Request.Builder()
                            .url(targetUrl)
                            .header("User-Agent", context.getDefaultUserAgent())
                            .header("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                            .apply { if (referer != null) header("Referer", referer) }
                            .build()

                        val res = safeParserCall("proxy", "httpRequest") {
                            withContext(Dispatchers.IO) {
                                context.httpClient.newCall(req).execute()
                            }
                        } ?: return@get

                        val contentType = res.header("Content-Type") ?: "image/jpeg"
                        val etag = res.header("ETag")

                        // Cache headers: allow browser / CDN / reverse proxy to cache manga images
                        call.response.header(HttpHeaders.CacheControl, "public, max-age=86400, immutable")
                        if (etag != null) {
                            call.response.header(HttpHeaders.ETag, etag)
                        }

                        try {
                            val body = res.body?.byteStream()
                                ?: return@get call.respondError(HttpStatusCode.BadGateway, "Empty upstream response")
                            call.respondOutputStream(ContentType.parse(contentType), HttpStatusCode.OK) {
                                body.use { it.copyTo(this) }
                            }
                        } finally {
                            res.close()
                        }
                    }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

private const val PAGE_SIZE = 20

private fun resolveSource(name: String): MangaParserSource? =
    runCatching { MangaParserSource.valueOf(name.uppercase()) }.getOrNull()

private suspend fun RoutingContext.resolveSourceParam(): MangaParserSource? {
    val name = call.parameters["source"]
        ?: run { call.respondError(HttpStatusCode.BadRequest, "Missing: source"); return null }
    return resolveSource(name)
        ?: run { call.respondError(HttpStatusCode.BadRequest, "Unknown source: '$name'. Lihat /api/sources"); null }
}

private suspend fun RoutingContext.resolveSourceFromPath(): MangaParserSource? {
    val name = call.parameters["source"]
        ?: run { call.respondError(HttpStatusCode.BadRequest, "Missing path: source"); return null }
    return resolveSource(name)
        ?: run { call.respondError(HttpStatusCode.BadRequest, "Unknown source: '$name'"); null }
}

private fun RoutingContext.pageParam() =
    call.request.queryParameters["page"]?.toIntOrNull()?.coerceAtLeast(1) ?: 1

private suspend fun RoutingContext.mangaIdParam(): Long? {
    return call.parameters["id"]?.toLongOrNull()
        ?: run { call.respondError(HttpStatusCode.BadRequest, "'id' harus Long integer"); null }
}

private suspend fun RoutingContext.urlParam(): String? {
    return call.request.queryParameters["url"]
        ?: run { call.respondError(HttpStatusCode.BadRequest, "Missing query param: url"); null }
}

private suspend fun RoutingContext.browseWithSort(
    context: MangaLoaderContextImpl,
    cache:   ResponseCache,
    source:  MangaParserSource,
    pref:    SortOrder,
    page:    Int,
) {
    val parser = context.getParser(source)
    val sort   = pref.takeIf { it in parser.availableSortOrders }
        ?: return call.respondError(
            HttpStatusCode.NotImplemented,
            "Source '${source.name}' tidak mendukung '${pref.name}'. " +
            "Tersedia: ${parser.availableSortOrders.joinToString { it.name }}"
        )

    val cacheKey = "${source.name}:${sort.name}:p$page"
    val dto = safeParserCall(source.name, "getList(${sort.name})") {
        cache.getOrCompute(cacheKey, cache.listTtlMs) {
            withContext(Dispatchers.IO) {
                parser.getList((page - 1) * PAGE_SIZE, sort, MangaListFilter.EMPTY).map { it.toListItemDto() }
            }
        }
    } ?: return

    call.respond(dto)
}

private fun buildStubManga(id: Long, url: String, publicUrl: String, source: MangaParserSource) = Manga(
    id = id, title = "", altTitles = emptySet(), url = url, publicUrl = publicUrl,
    rating = RATING_UNKNOWN, contentRating = null, coverUrl = null,
    tags = emptySet(), state = null, authors = emptySet(), source = source,
)

private suspend fun fetchMangaDetail(
    context: MangaLoaderContextImpl,
    source:  MangaParserSource,
    id:      Long,
    url:     String,
): Manga {
    val parser    = context.getParser(source)
    val publicUrl = if (url.startsWith("http")) url else "https://${parser.domain}$url"
    return parser.getDetails(buildStubManga(id, url, publicUrl, source))
}

private fun validateProxyUrl(rawUrl: String): String? {
    return try {
        val uri  = URI(rawUrl)
        if (uri.scheme?.lowercase() != "https") return "Hanya HTTPS URL yang diizinkan"
        val host = uri.host?.lowercase() ?: return "URL tidak valid: host tidak bisa ditentukan"
        if (host in BLOCKED_HOSTS) return "Proxy ke localhost/loopback tidak diizinkan"
        if (BLOCKED_IP_PREFIXES.any { host.startsWith(it) }) return "Proxy ke IP private tidak diizinkan"
        null
    } catch (e: Exception) { "URL tidak valid: ${e.message}" }
}

private suspend inline fun <T> RoutingContext.safeParserCall(
    source:    String,
    operation: String,
    crossinline block: suspend () -> T,
): T? = try {
    block()
} catch (e: CloudFlareProtectedException) {
    logger.warn("[$source] CloudFlare on $operation: ${e.reason}")
    call.respondError(
        HttpStatusCode.ServiceUnavailable,
        "CloudFlare challenge untuk '$source' (${e.reason}). Butuh browser session.",
    )
    null
} catch (e: UnsupportedOperationException) {
    logger.warn("[$source] $operation not supported")
    call.respondError(HttpStatusCode.NotImplemented, "Operasi '$operation' tidak didukung oleh '$source'.")
    null
} catch (e: NoSuchElementException) {
    logger.warn("[$source] $operation not found: ${e.message}")
    call.respondError(HttpStatusCode.NotFound, e.message ?: "Resource tidak ditemukan")
    null
} catch (e: Exception) {
    logger.error("[$source] $operation failed: ${e.message}")
    call.respondError(HttpStatusCode.ServiceUnavailable,
        "Parser error: '$source' / '$operation'.", e.message ?: e.javaClass.simpleName)
    null
}

private suspend fun ApplicationCall.respondError(
    status: HttpStatusCode, error: String, detail: String? = null,
) = respond(status, ErrorDto(error = error, detail = detail))

// ── Landing Page ──────────────────────────────────────────────────────────────
private fun buildLandingPage(total: Int, active: Int) = """<!DOCTYPE html>
<html lang="id">
<head>
<meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>api-manga v2.0</title>
<style>
*{box-sizing:border-box}
body{font-family:'Segoe UI',system-ui,monospace;max-width:980px;margin:0 auto;padding:32px 20px;background:#0d1117;color:#c9d1d9}
h1{color:#58a6ff;margin:0 0 2px;font-size:28px}
.version{display:inline-block;background:#1f3a5f;color:#79c0ff;padding:2px 10px;border-radius:20px;font-size:12px;font-weight:700;margin-left:8px;vertical-align:middle}
h2{color:#79c0ff;border-bottom:1px solid #21262d;padding-bottom:8px;margin-top:32px}
p{color:#8b949e;margin:4px 0 12px}a{color:#58a6ff;text-decoration:none}a:hover{text-decoration:underline}
.stats{display:flex;gap:10px;flex-wrap:wrap;margin:18px 0}
.stat{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px 20px;text-align:center;min-width:100px}
.stat .n{font-size:24px;font-weight:700;display:block}.g{color:#3fb950}.y{color:#e3b341}.b{color:#58a6ff}
.section-label{color:#484f58;font-size:10px;font-weight:700;letter-spacing:1.2px;text-transform:uppercase;margin:20px 0 5px}
.ep{background:#161b22;border:1px solid #21262d;border-radius:6px;padding:9px 14px;margin:4px 0;display:flex;flex-wrap:wrap;align-items:baseline;gap:6px}
.ep:hover{border-color:#30363d}
.get{color:#3fb950;font-weight:700;font-size:11px;letter-spacing:.5px;font-family:monospace;white-space:nowrap}
.post{color:#e3b341;font-weight:700;font-size:11px;letter-spacing:.5px;font-family:monospace;white-space:nowrap}
.delete{color:#f85149;font-weight:700;font-size:11px;letter-spacing:.5px;font-family:monospace;white-space:nowrap}
.path{color:#e6edf3;font-size:12px;font-family:monospace}.pm{color:#d2a8ff}
.desc{color:#8b949e;font-size:11px;margin-top:3px;width:100%;line-height:1.5}
code{background:#21262d;border:1px solid #30363d;padding:1px 5px;border-radius:4px;color:#79c0ff;font-size:11px}
.badge{display:inline-block;padding:1px 7px;border-radius:10px;font-size:10px;font-weight:700;margin-left:4px}
.badge-read{background:#1a3a1a;color:#3fb950}
.badge-full{background:#3a2a1a;color:#e3b341}
.badge-admin{background:#3a1a1a;color:#f85149}
.table-wrap{overflow-x:auto;margin:16px 0}
table{width:100%;border-collapse:collapse;font-size:13px}
th{background:#161b22;color:#8b949e;text-align:left;padding:8px 12px;border:1px solid #21262d}
td{padding:8px 12px;border:1px solid #21262d}
tr:nth-child(even){background:#161b2280}
.warn{background:#2d1b00;border:1px solid #9e6a03;border-radius:6px;padding:12px 16px;color:#d29922;margin:16px 0;font-size:13px}
.curl{background:#161b22;border:1px solid #30363d;border-radius:6px;padding:12px;font-family:monospace;font-size:12px;color:#79c0ff;overflow-x:auto}
</style>
</head>
<body>
<h1>api-manga<span class="version">v2.0.0</span></h1>
<p>Universal Manga REST API &mdash; scrape dan baca manga dari 1200+ source tanpa Android runtime.</p>
<div class="stats">
<div class="stat"><span class="n b">$total</span><span class="desc">Total Source</span></div>
<div class="stat"><span class="n g">$active</span><span class="desc">Active</span></div>
<div class="stat"><span class="n y">${total - active}</span><span class="desc">Broken</span></div>
</div>
<div class="warn">
<strong>Autentikasi Diperlukan:</strong> Semua request ke <code>/api/*</code> wajib menyertakan API key.<br>
Header: <code>X-Api-Key: mapi_xxx</code> &bull; Query: <code>?api_key=mapi_xxx</code> &bull; Bearer: <code>Authorization: Bearer mapi_xxx</code>
</div>
<div class="curl"># Contoh pemanggilan:
curl -H "X-Api-Key: &lt;YOUR_KEY&gt;" http://localhost:8080/api/home/MANGADEX</div>
<h2>Daftar Endpoint</h2>
<div class="section-label">General</div>
<div class="ep"><span class="get">GET</span><span class="path">/api/sources</span><span class="badge badge-read">READ</span><span class="desc">Daftar semua parser source. Filter: <code>?lang=id</code>, <code>?type=MANGA</code>, <code>?broken=false</code></span></div>
<div class="section-label">Browse</div>
<div class="ep"><span class="get">GET</span><span class="path">/api/home/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Section popular + latest sekaligus (ringan untuk homepage)</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/popular/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Manga terpopuler. Query: <code>?page=1</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/latest/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Manga baru update chapter. Query: <code>?page=1</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/newest/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Judul manga yang baru ditambahkan. Query: <code>?page=1</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/trending/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Trending saat ini (fallback otomatis sesuai kapabilitas source)</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/top-rated/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Rating tertinggi. Query: <code>?page=1</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/alphabetical/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Urutan alfabetis A-Z. Query: <code>?page=1</code></span></div>
<div class="section-label">Search &amp; Filter</div>
<div class="ep"><span class="get">GET</span><span class="path">/api/search</span><span class="badge badge-read">READ</span><span class="desc">Cari manga. Query: <code>?source=MANGADEX&amp;q=solo+leveling&amp;page=1</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/multi-search</span><span class="badge badge-read">READ</span><span class="desc">Cari di maks 5 source paralel. Query: <code>?sources=MGKOMIK,MANGADEX&amp;q=naruto</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/tags/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Daftar genre/tag, status, dan sort order yang didukung source ini</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/filter/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Filter lanjutan. Query: <code>?tag=action&amp;tag=fantasy&amp;state=ONGOING&amp;sort=UPDATED&amp;page=1</code></span></div>
<div class="section-label">Detail &amp; Baca</div>
<div class="ep"><span class="get">GET</span><span class="path">/api/manga/<span class="pm">{source}</span>/<span class="pm">{id}</span></span><span class="badge badge-read">READ</span><span class="desc">Detail manga + daftar chapter lengkap. Query: <code>?url=&lt;url_manga&gt;</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/chapters/<span class="pm">{source}</span>/<span class="pm">{mangaId}</span></span><span class="badge badge-read">READ</span><span class="desc">Hanya daftar chapter. Query: <code>?url=&lt;url_manga&gt;</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/related/<span class="pm">{source}</span>/<span class="pm">{id}</span></span><span class="badge badge-read">READ</span><span class="desc">Manga terkait/rekomendasi. Query: <code>?url=&lt;url_manga&gt;</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/pages/<span class="pm">{source}</span>/<span class="pm">{chapterId}</span></span><span class="badge badge-read">READ</span><span class="desc">Daftar URL gambar semua halaman chapter. Query: <code>?url=&lt;url_chapter&gt;</code></span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/page-url/<span class="pm">{source}</span></span><span class="badge badge-read">READ</span><span class="desc">Resolve direct URL gambar untuk server yang butuh dynamic token. Query: <code>?pageId=1&amp;url=...</code></span></div>
<div class="section-label">Image Proxy (Bypass Hotlink / Referer)</div>
<div class="ep"><span class="get">GET</span><span class="path">/api/proxy</span><span class="badge badge-full">FULL</span><span class="desc">Streaming gambar upstream dengan User-Agent &amp; Referer yang benar. Query: <code>?url=&lt;encoded_url&gt;&amp;source=MANGADEX</code></span></div>
<div class="section-label">Admin &amp; Management</div>
<div class="ep"><span class="get">GET</span><span class="path">/admin/me</span><span class="badge badge-read">READ</span><span class="desc">Cek identitas dan statistik pemanggilan key yang sedang digunakan</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/admin/status</span><span class="badge badge-full">FULL</span><span class="desc">Server uptime, statistik cache, dan jumlah source aktif</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/admin/keys</span><span class="badge badge-admin">ADMIN</span><span class="desc">List semua API key beserta tier, tanggal buat, dan jumlah pemanggilan</span></div>
<div class="ep"><span class="post">POST</span><span class="path">/admin/keys</span><span class="badge badge-admin">ADMIN</span><span class="desc">Buat API key baru. Body: <code>{"name":"app-frontend","tier":"FULL"}</code></span></div>
<div class="ep"><span class="delete">DELETE</span><span class="path">/admin/keys/<span class="pm">{key}</span></span><span class="badge badge-admin">ADMIN</span><span class="desc">Revoke/nonaktifkan API key</span></div>
<div class="ep"><span class="post">POST</span><span class="path">/admin/cache/flush</span><span class="badge badge-admin">ADMIN</span><span class="desc">Bersihkan seluruh in-memory cache</span></div>
<div class="ep"><span class="post">POST</span><span class="path">/admin/cache/flush/<span class="pm">{source}</span></span><span class="badge badge-admin">ADMIN</span><span class="desc">Bersihkan cache untuk satu source tertentu</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/admin/cache/stats</span><span class="badge badge-admin">ADMIN</span><span class="desc">Statistik detail: total cache entries, active, dan expired</span></div>
</body>
</html>"""
