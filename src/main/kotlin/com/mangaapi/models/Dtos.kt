package com.mangaapi.models

import com.mangaapi.auth.ApiKey
import com.mangaapi.auth.KeyTier
import com.mangaapi.cache.CacheStats
import kotlinx.serialization.Serializable
import org.koitharu.kotatsu.parsers.model.*

// DTOs — we map kotatsu library types → our own shapes.
// Gives full control over API shape and avoids reflection issues.

@Serializable
data class SourceDto(
    val id:          String,    // enum name, e.g. "MANGADEX"
    val displayName: String,
    val lang:        String,    // ISO 639-1
    val contentType: String,    // MANGA | MANHWA | MANHUA | HENTAI | COMICS | OTHER
    val isBroken:    Boolean,
    val domain:      String?,
)

@Serializable
data class MangaListItemDto(
    val id:            Long,
    val title:         String,
    val altTitles:     List<String>,
    val url:           String,
    val publicUrl:     String,
    val coverUrl:      String?,
    val rating:        Float,
    val contentRating: String?,
    val state:         String?,
    val authors:       List<String>,
    val tags:          List<TagDto>,
    val source:        String,
)

@Serializable
data class MangaDetailDto(
    val id:            Long,
    val title:         String,
    val altTitles:     List<String>,
    val url:           String,
    val publicUrl:     String,
    val coverUrl:      String?,
    val largeCoverUrl: String?,
    val rating:        Float,
    val contentRating: String?,
    val state:         String?,
    val authors:       List<String>,
    val tags:          List<TagDto>,
    val description:   String?,
    val chapters:      List<ChapterDto>?,
    val source:        String,
)

@Serializable
data class ChapterDto(
    val id:         Long,
    val title:      String?,
    val number:     Float,
    val volume:     Int,
    val url:        String,
    val scanlator:  String?,
    val uploadDate: Long,
    val branch:     String?,
    val source:     String,
)

@Serializable
data class TagDto(
    val key:    String,
    val title:  String,
    val source: String,
)

@Serializable
data class PageDto(
    val id:      Long,
    val url:     String,
    val preview: String?,
    val source:  String,
)

@Serializable
data class FilterOptionsDto(
    val tags:                List<TagDto>,
    val availableStates:     List<String>,
    val availableRatings:    List<String>,
    val availableTypes:      List<String>,
    val availableSortOrders: List<String>,
)

@Serializable
data class ErrorDto(
    val error:  String,
    val detail: String? = null,
)

// ── v2.0 DTOs ────────────────────────────────────────────────────────────────

/** Paginated response wrapper */
@Serializable
data class PagedResponse<T>(
    val data:    List<T>,
    val page:    Int,
    val hasMore: Boolean,   // hint: true if result count == pageSize
    val cached:  Boolean = false,
)

/** API key info returned to caller (key hidden after creation) */
@Serializable
data class ApiKeyDto(
    val name:       String,
    val tier:       String,
    val createdAt:  Long,
    val lastUsed:   Long?,
    val totalCalls: Long,
    val active:     Boolean,
    val keyPreview: String,     // e.g. "mapi_aBcD...XyZ"
)

/** Full key — only returned once at creation time */
@Serializable
data class ApiKeyCreatedDto(
    val key:  String,
    val name: String,
    val tier: String,
)

/** Server status */
@Serializable
data class StatusDto(
    val status:         String,
    val version:        String,
    val sourcesTotal:   Int,
    val sourcesActive:  Int,
    val cache:          CacheStatsDto,
    val uptime:         Long,           // seconds since start
)

@Serializable
data class CacheStatsDto(
    val total:   Int,
    val active:  Int,
    val expired: Int,
)

/** Multi-source search result */
@Serializable
data class MultiSearchResultDto(
    val source:  String,
    val results: List<MangaListItemDto>,
    val error:   String? = null,
)

// ── Mappers ───────────────────────────────────────────────────────────────────

fun MangaParserSource.toDto(domain: String?) = SourceDto(
    id          = name,
    displayName = title,
    lang        = locale,
    contentType = contentType.name,
    isBroken    = isBroken,
    domain      = domain,
)

fun Manga.toListItemDto() = MangaListItemDto(
    id            = id,
    title         = title,
    altTitles     = altTitles.toList(),
    url           = url,
    publicUrl     = publicUrl,
    coverUrl      = coverUrl,
    rating        = rating,
    contentRating = contentRating?.name,
    state         = state?.name,
    authors       = authors.toList(),
    tags          = tags.map { it.toDto() },
    source        = source.name,
)

fun Manga.toDetailDto() = MangaDetailDto(
    id            = id,
    title         = title,
    altTitles     = altTitles.toList(),
    url           = url,
    publicUrl     = publicUrl,
    coverUrl      = coverUrl,
    largeCoverUrl = largeCoverUrl,
    rating        = rating,
    contentRating = contentRating?.name,
    state         = state?.name,
    authors       = authors.toList(),
    tags          = tags.map { it.toDto() },
    description   = description,
    chapters      = chapters?.map { it.toDto() },
    source        = source.name,
)

fun MangaChapter.toDto() = ChapterDto(
    id         = id,
    title      = title,
    number     = number,
    volume     = volume,
    url        = url,
    scanlator  = scanlator,
    uploadDate = uploadDate,
    branch     = branch,
    source     = source.name,
)

fun MangaTag.toDto() = TagDto(
    key    = key,
    title  = title,
    source = source.name,
)

fun MangaPage.toDto() = PageDto(
    id      = id,
    url     = url,
    preview = preview,
    source  = source.name,
)

fun ApiKey.toDto() = ApiKeyDto(
    name       = name,
    tier       = tier.name,
    createdAt  = createdAt,
    lastUsed   = lastUsed,
    totalCalls = totalCalls,
    active     = active,
    keyPreview = key.take(9) + "..." + key.takeLast(4),
)

fun CacheStats.toDto() = CacheStatsDto(total = total, active = active, expired = expired)
