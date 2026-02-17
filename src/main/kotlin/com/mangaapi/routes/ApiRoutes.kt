package com.mangaapi.routes

import com.mangaapi.ApiKeyExtractorPlugin
import com.mangaapi.auth.ApiKeyManager
import com.mangaapi.cache.ResponseCache
import com.mangaapi.context.CloudFlareProtectedException
import com.mangaapi.context.MangaLoaderContextImpl
import com.mangaapi.models.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
    // Install the extractor so X-Api-Key and ?api_key= work alongside Bearer
    install(ApiKeyExtractorPlugin)

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
            route("/api") {

                // GET /api/sources
                get("/sources") {
                    val lang   = call.request.queryParameters["lang"]
                    val type   = call.request.queryParameters["type"]?.uppercase()
                    val broken = call.request.queryParameters["broken"]?.lowercase()

                    val cacheKey = "sources:$lang:$type:$broken"
                    val cached = cache.get<List<SourceDto>>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val sources = MangaParserSource.entries
                        .filter { s -> lang   == null || s.locale == lang }
                        .filter { s -> type   == null || s.contentType.name == type }
                        .filter { s -> broken == null || s.isBroken.toString() == broken }
                        .map    { s ->
                            val domain = runCatching { context.getParser(s).domain }.getOrNull()
                            s.toDto(domain)
                        }
                    cache.set(cacheKey, sources, cache.tagsTtlMs)
                    call.respond(sources)
                }

                // GET /api/home/{source} — popular + latest
                get("/home/{source}") {
                    val source = resolveSourceParam() ?: return@get
                    val parser = context.getParser(source)

                    val cacheKey = "${source.name}:home"
                    val cached = cache.get<Map<String, List<MangaListItemDto>>>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val popular = if (SortOrder.POPULARITY in parser.availableSortOrders) {
                        safeParserCall(source.name, "popular") {
                            parser.getList(0, SortOrder.POPULARITY, MangaListFilter.EMPTY)
                        } ?: emptyList()
                    } else emptyList()

                    val latest = if (SortOrder.UPDATED in parser.availableSortOrders) {
                        safeParserCall(source.name, "latest") {
                            parser.getList(0, SortOrder.UPDATED, MangaListFilter.EMPTY)
                        } ?: emptyList()
                    } else emptyList()

                    val result = mapOf(
                        "popular" to popular.map { it.toListItemDto() },
                        "latest"  to latest.map  { it.toListItemDto() },
                    )
                    cache.set(cacheKey, result, cache.listTtlMs)
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
                    val parser = context.getParser(source)

                    val cacheKey = "${source.name}:tags"
                    val cached = cache.get<FilterOptionsDto>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val opts = safeParserCall(source.name, "getFilterOptions") {
                        parser.getFilterOptions()
                    } ?: return@get

                    val dto = FilterOptionsDto(
                        tags                = opts.availableTags.map { it.toDto() },
                        availableStates     = opts.availableStates.map { it.name },
                        availableRatings    = opts.availableContentRating.map { it.name },
                        availableTypes      = opts.availableContentTypes.map { it.name },
                        availableSortOrders = parser.availableSortOrders.map { it.name },
                    )
                    cache.set(cacheKey, dto, cache.tagsTtlMs)
                    call.respond(dto)
                }

                // GET /api/filter/{source}?tag=action&state=ONGOING&sort=UPDATED&page=1
                get("/filter/{source}") {
                    val source = resolveSourceParam() ?: return@get
                    val parser = context.getParser(source)
                    val page   = pageParam()
                    val offset = (page - 1) * PAGE_SIZE

                    val tagKeys = call.request.queryParameters.getAll("tag") ?: emptyList()
                    val tags = if (tagKeys.isNotEmpty()) {
                        val allTags = safeParserCall(source.name, "tags") {
                            parser.getFilterOptions().availableTags
                        } ?: return@get
                        tagKeys.mapNotNull { k -> allTags.find { it.key == k } }.toSet()
                    } else emptySet()

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

                    val filter = MangaListFilter(
                        tags          = tags,
                        states        = if (state  != null) setOf(state)  else emptySet(),
                        contentRating = if (rating != null) setOf(rating) else emptySet(),
                    )

                    val cacheKey = "${source.name}:filter:${tagKeys.sorted()}:${state}:${rating}:${sort}:p$page"
                    val cached   = cache.get<List<MangaListItemDto>>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val results = safeParserCall(source.name, "filter") {
                        parser.getList(offset, sort, filter)
                    } ?: return@get

                    val dto = results.map { it.toListItemDto() }
                    cache.set(cacheKey, dto, cache.listTtlMs)
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
                    val cached   = cache.get<List<MangaListItemDto>>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val sort = when {
                        q != null && SortOrder.RELEVANCE  in parser.availableSortOrders -> SortOrder.RELEVANCE
                        SortOrder.POPULARITY in parser.availableSortOrders              -> SortOrder.POPULARITY
                        else -> parser.availableSortOrders.first()
                    }

                    val results = safeParserCall(source.name, "search") {
                        parser.getList(offset, sort, MangaListFilter(query = q))
                    } ?: return@get

                    val dto = results.map { it.toListItemDto() }
                    cache.set(cacheKey, dto, cache.listTtlMs)
                    call.respond(dto)
                }

                // GET /api/multi-search?sources=MGKOMIK,MANGADEX&q=naruto
                // Cari di beberapa source sekaligus (parallel)
                get("/multi-search") {
                    val sourcesParam = call.request.queryParameters["sources"]
                        ?: return@get call.respondError(HttpStatusCode.BadRequest,
                            "Missing parameter: sources (comma-separated, e.g. MGKOMIK,MANGADEX)")
                    val q = call.request.queryParameters["q"]?.takeIf { it.isNotBlank() }
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, "Missing parameter: q")

                    val sourceList = sourcesParam.split(",")
                        .map { it.trim().uppercase() }
                        .take(5) // max 5 source sekaligus
                        .mapNotNull { resolveSource(it) }

                    if (sourceList.isEmpty()) {
                        return@get call.respondError(HttpStatusCode.BadRequest, "Tidak ada source valid yang diberikan")
                    }

                    val results = coroutineScope {
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
                    call.respond(results)
                }

                // GET /api/manga/{source}/{id}?url=...
                get("/manga/{source}/{id}") {
                    val source = resolveSourceFromPath() ?: return@get
                    val id     = mangaIdParam() ?: return@get
                    val url    = urlParam() ?: return@get

                    val cacheKey = "${source.name}:detail:$id"
                    val cached   = cache.get<MangaDetailDto>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val detail = fetchMangaDetail(context, source, id, url) ?: return@get
                    val dto    = detail.toDetailDto()
                    cache.set(cacheKey, dto, cache.detailTtlMs)
                    call.respond(dto)
                }

                // GET /api/chapters/{source}/{mangaId}?url=...
                get("/chapters/{source}/{mangaId}") {
                    val source = resolveSourceFromPath() ?: return@get
                    val id     = call.parameters["mangaId"]?.toLongOrNull()
                        ?: return@get call.respondError(HttpStatusCode.BadRequest, "'mangaId' harus Long integer")
                    val url    = urlParam() ?: return@get

                    val cacheKey = "${source.name}:chapters:$id"
                    val cached   = cache.get<List<ChapterDto>>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val detail = fetchMangaDetail(context, source, id, url) ?: return@get
                    val dto    = detail.chapters.orEmpty().map { it.toDto() }
                    cache.set(cacheKey, dto, cache.detailTtlMs)
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
                        parser.getRelatedManga(buildStubManga(id, url, pubUrl, source))
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
                    val cached   = cache.get<List<PageDto>>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val chapter = MangaChapter(
                        id = chapterId, title = null, number = 0f, volume = 0,
                        url = url, scanlator = null, uploadDate = 0L, branch = null, source = source,
                    )
                    val pages = safeParserCall(source.name, "getPages") {
                        context.getParser(source).getPages(chapter)
                    } ?: return@get

                    val dto = pages.map { it.toDto() }
                    cache.set(cacheKey, dto, cache.pagesTtlMs)
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
                    val cached   = cache.get<Map<String, String>>(cacheKey)
                    if (cached != null) return@get call.respond(cached)

                    val direct = safeParserCall(source.name, "getPageUrl") {
                        context.getParser(source).getPageUrl(MangaPage(pageId, pageUrl, null, source))
                    } ?: return@get

                    val dto = mapOf("url" to direct)
                    cache.set(cacheKey, dto, cache.pagesTtlMs)
                    call.respond(dto)
                }
            }
        }

        // ── Proxy needs FULL tier ─────────────────────────────────────────────
        authenticate("apikey-full") {
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
                        context.httpClient.newCall(req).execute()
                    } ?: return@get

                    val contentType = res.header("Content-Type") ?: "image/jpeg"
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
    val cached   = cache.get<List<MangaListItemDto>>(cacheKey)
    if (cached != null) return call.respond(cached)

    val results = safeParserCall(source.name, "getList(${sort.name})") {
        parser.getList((page - 1) * PAGE_SIZE, sort, MangaListFilter.EMPTY)
    } ?: return

    val dto = results.map { it.toListItemDto() }
    cache.set(cacheKey, dto, cache.listTtlMs)
    call.respond(dto)
}

private fun buildStubManga(id: Long, url: String, publicUrl: String, source: MangaParserSource) = Manga(
    id = id, title = "", altTitles = emptySet(), url = url, publicUrl = publicUrl,
    rating = RATING_UNKNOWN, contentRating = null, coverUrl = null,
    tags = emptySet(), state = null, authors = emptySet(), source = source,
)

private suspend fun RoutingContext.fetchMangaDetail(
    context: MangaLoaderContextImpl,
    source:  MangaParserSource,
    id:      Long,
    url:     String,
): Manga? {
    val parser    = context.getParser(source)
    val publicUrl = if (url.startsWith("http")) url else "https://${parser.domain}$url"
    return safeParserCall(source.name, "getDetails") {
        parser.getDetails(buildStubManga(id, url, publicUrl, source))
    }
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
    block:     () -> T,
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
.auth-info{background:#161b22;border:1px solid #30363d;border-left:3px solid #58a6ff;border-radius:6px;padding:14px 18px;margin:14px 0;font-size:12px;line-height:1.9}
.auth-info code{font-size:12px}
footer{color:#484f58;font-size:11px;margin-top:40px;border-top:1px solid #21262d;padding-top:14px}
</style>
</head>
<body>
<h1>&#x1F4DA; api-manga <span class="version">v2.0.0</span></h1>
<p>Universal manga REST API · <a href="https://github.com/YakaTeam/kotatsu-parsers" target="_blank">kotatsu-parsers</a></p>
<div class="stats">
  <div class="stat"><span class="n b">$total</span><span style="font-size:11px;color:#8b949e">Total Sources</span></div>
  <div class="stat"><span class="n g">$active</span><span style="font-size:11px;color:#8b949e">Active</span></div>
  <div class="stat"><span class="n y">${total - active}</span><span style="font-size:11px;color:#8b949e">Broken</span></div>
</div>

<div class="auth-info">
  <strong style="color:#e6edf3">&#x1F511; Autentikasi</strong> — Semua endpoint <code>/api/*</code> butuh API key.<br>
  <strong>Header:</strong> <code>X-Api-Key: mapi_xxx</code><br>
  <strong>Bearer:</strong> <code>Authorization: Bearer mapi_xxx</code><br>
  <strong>Query:</strong> <code>?api_key=mapi_xxx</code><br>
  Tier: <span class="badge badge-read">READ</span> browse/search/detail &nbsp;
        <span class="badge badge-full">FULL</span> + proxy &nbsp;
        <span class="badge badge-admin">ADMIN</span> + manajemen key
</div>

<h2>Endpoints</h2>

<div class="section-label">Public</div>
<div class="ep"><span class="get">GET</span><span class="path">/health</span><div class="desc">Health check · no auth</div></div>

<div class="section-label">Browse <span class="badge badge-read">READ</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/sources?<span class="pm">lang</span>=id&amp;<span class="pm">broken</span>=false</span><div class="desc">Daftar semua source · <a href="/api/sources?lang=id&broken=false">coba (butuh key)</a></div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/home/<span class="pm">{source}</span></span><div class="desc">Popular + Latest sekaligus → <code>{"popular":[...],"latest":[...]}</code></div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/popular/<span class="pm">{source}</span>?<span class="pm">page</span>=1</span><div class="desc">Manga paling populer</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/latest/<span class="pm">{source}</span>?<span class="pm">page</span>=1</span><div class="desc">Baru diupdate</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/newest/<span class="pm">{source}</span>?<span class="pm">page</span>=1</span><div class="desc">Baru ditambahkan ke source</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/trending/<span class="pm">{source}</span>?<span class="pm">page</span>=1</span><div class="desc">Trending (otomatis pilih today→week→month)</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/top-rated/<span class="pm">{source}</span>?<span class="pm">page</span>=1</span><div class="desc">Rating tertinggi</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/alphabetical/<span class="pm">{source}</span>?<span class="pm">page</span>=1</span><div class="desc">Urutan A–Z</div></div>

<div class="section-label">Filter &amp; Search <span class="badge badge-read">READ</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/tags/<span class="pm">{source}</span></span><div class="desc">Semua genre + filter tersedia</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/filter/<span class="pm">{source}</span>?<span class="pm">tag</span>=action&amp;<span class="pm">state</span>=ONGOING&amp;<span class="pm">sort</span>=UPDATED&amp;<span class="pm">page</span>=1</span><div class="desc">Filter kombinasi. State: <code>ONGOING</code> <code>FINISHED</code> <code>ABANDONED</code> <code>PAUSED</code></div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/search?<span class="pm">source</span>=MGKOMIK&amp;<span class="pm">q</span>=naruto&amp;<span class="pm">page</span>=1</span><div class="desc">Cari manga di satu source</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/multi-search?<span class="pm">sources</span>=MGKOMIK,MANGADEX&amp;<span class="pm">q</span>=naruto</span><div class="desc">Cari di beberapa source sekaligus (max 5, parallel)</div></div>

<div class="section-label">Detail &amp; Reading <span class="badge badge-read">READ</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/manga/<span class="pm">{source}</span>/<span class="pm">{id}</span>?<span class="pm">url</span>=...</span><div class="desc">Detail manga + semua chapter</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/chapters/<span class="pm">{source}</span>/<span class="pm">{mangaId}</span>?<span class="pm">url</span>=...</span><div class="desc">Hanya daftar chapter</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/related/<span class="pm">{source}</span>/<span class="pm">{id}</span>?<span class="pm">url</span>=...</span><div class="desc">Manga terkait / rekomendasi</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/pages/<span class="pm">{source}</span>/<span class="pm">{chapterId}</span>?<span class="pm">url</span>=...</span><div class="desc">Halaman dalam chapter</div></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/page-url/<span class="pm">{source}</span>?<span class="pm">pageId</span>=...&amp;<span class="pm">url</span>=...</span><div class="desc">Resolve URL CDN final</div></div>

<div class="section-label">Proxy <span class="badge badge-full">FULL</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/api/proxy?<span class="pm">url</span>={encodedUrl}&amp;<span class="pm">referer</span>=...</span><div class="desc">Image proxy (SSRF-protected, hanya HTTPS ke host publik)</div></div>

<div class="section-label">Admin <span class="badge badge-admin">ADMIN</span> / <span class="badge badge-full">FULL</span></div>
<div class="ep"><span class="get">GET</span><span class="path">/admin/me</span><div class="desc">Info API key yang sedang dipakai <span class="badge badge-read">READ</span></div></div>
<div class="ep"><span class="get">GET</span><span class="path">/admin/status</span><div class="desc">Server status, uptime, cache stats <span class="badge badge-full">FULL</span></div></div>
<div class="ep"><span class="get">GET</span><span class="path">/admin/keys</span><div class="desc">List semua API key <span class="badge badge-admin">ADMIN</span></div></div>
<div class="ep"><span class="post">POST</span><span class="path">/admin/keys</span><div class="desc">Buat key baru · body: <code>{"name":"app-ku","tier":"FULL"}</code> <span class="badge badge-admin">ADMIN</span></div></div>
<div class="ep"><span class="delete">DELETE</span><span class="path">/admin/keys/<span class="pm">{key}</span></span><div class="desc">Revoke key <span class="badge badge-admin">ADMIN</span></div></div>
<div class="ep"><span class="post">POST</span><span class="path">/admin/cache/flush</span><div class="desc">Hapus semua cache <span class="badge badge-admin">ADMIN</span></div></div>
<div class="ep"><span class="post">POST</span><span class="path">/admin/cache/flush/<span class="pm">{source}</span></span><div class="desc">Flush cache satu source <span class="badge badge-admin">ADMIN</span></div></div>

<h2>Quick Start</h2>
<div style="font-family:monospace;font-size:12px;color:#8b949e;line-height:2.2">
  # Pertama, cari key ADMIN di log server saat pertama kali jalan<br>
  <span style="color:#79c0ff">curl</span> -H "X-Api-Key: mapi_xxx" http://localhost:8080/api/sources?lang=id&amp;broken=false<br>
  <span style="color:#79c0ff">curl</span> -H "X-Api-Key: mapi_xxx" http://localhost:8080/api/home/MGKOMIK<br>
  <span style="color:#79c0ff">curl</span> -H "X-Api-Key: mapi_xxx" http://localhost:8080/api/search?source=MGKOMIK&amp;q=naruto<br>
  # Buat key baru (butuh ADMIN key)<br>
  <span style="color:#79c0ff">curl</span> -X POST -H "X-Api-Key: mapi_xxx" -H "Content-Type: application/json" \<br>
    &nbsp;&nbsp;&nbsp;&nbsp;-d '{"name":"app-saya","tier":"FULL"}' http://localhost:8080/admin/keys
</div>

<footer>Ktor 3.1.3 · Kotlin 2.2.10 · kotatsu-parsers master-SNAPSHOT</footer>
</body>
</html>"""
