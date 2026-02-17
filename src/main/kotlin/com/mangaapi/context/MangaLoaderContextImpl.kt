package com.mangaapi.context

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.internal.closeQuietly
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.bitmap.Rect
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper
import org.koitharu.kotatsu.parsers.network.UserAgents
import org.slf4j.LoggerFactory
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

/**
 * JVM/server-side implementation of [MangaLoaderContext].
 *
 * Replaces the Android-specific context used in the Kotatsu app with a pure-JVM
 * version that works on any server (Debian, Ubuntu, Docker, etc.).
 *
 * Features:
 * - In-memory cookie jar (thread-safe, no persistence)
 * - Referer + User-Agent headers added automatically per parser domain
 * - CloudFlare detection — throws [CloudFlareProtectedException] instead of silently failing
 * - Image descrambling via javax.imageio (MangaPlus, ExHentai, MangaFire, etc.)
 * - Parser instance cache — one per source, reused across requests
 */
class MangaLoaderContextImpl : MangaLoaderContext() {

    private val logger = LoggerFactory.getLogger(MangaLoaderContextImpl::class.java)

    override val cookieJar: CookieJar = InMemoryCookieJar()

    // httpClient is public so the proxy endpoint can reuse it
    override val httpClient: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .addInterceptor(CommonHeadersInterceptor(this))
        .addInterceptor(CloudFlareInterceptor())
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    // Very few parsers actually need JS. Those that do will throw, which the
    // route handler catches and returns as 503 with a clear message.
    @Deprecated("Provide a base url")
    override suspend fun evaluateJs(script: String): String? {
        logger.warn("evaluateJs called without baseUrl — JS evaluation not supported server-side")
        return null
    }

    override suspend fun evaluateJs(baseUrl: String, script: String): String? {
        logger.warn("evaluateJs called for [$baseUrl] — JS evaluation not supported server-side")
        return null
    }

    override fun getConfig(source: MangaSource): MangaSourceConfig = DefaultSourceConfig

    override fun getDefaultUserAgent(): String = UserAgents.CHROME_DESKTOP

    // Image descrambling support (MangaPlus, ExHentai, MangaFire, etc.)
    override fun redrawImageResponse(response: Response, redraw: (image: Bitmap) -> Bitmap): Response {
        val bodyBytes = response.body?.bytes() ?: return response
        return try {
            val srcImage = bodyBytes.inputStream().use { ImageIO.read(it) } ?: return response
            val result   = redraw(JvmBitmap(srcImage)) as? JvmBitmap ?: return response
            val baos     = ByteArrayOutputStream()
            ImageIO.write(result.image, "png", baos)
            response.newBuilder()
                .body(baos.toByteArray().toResponseBody("image/png".toMediaTypeOrNull()))
                .build()
        } catch (e: Exception) {
            logger.warn("redrawImageResponse failed, returning original: ${e.message}")
            response.newBuilder()
                .body(bodyBytes.toResponseBody(response.body?.contentType()))
                .build()
        }
    }

    override fun createBitmap(width: Int, height: Int): Bitmap =
        JvmBitmap(BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB))

    // One parser instance per source, reused across all requests
    private val parserCache = ConcurrentHashMap<MangaParserSource, MangaParser>()

    fun getParser(source: MangaParserSource): MangaParser =
        parserCache.getOrPut(source) { newParserInstance(source) }
}

// Always returns the key's built-in default — parsers use their default domain, UA, etc.
private object DefaultSourceConfig : MangaSourceConfig {
    override fun <T> get(key: ConfigKey<T>): T = key.defaultValue
}

// Thread-safe in-memory cookie storage. Expired cookies are filtered on read.
class InMemoryCookieJar : CookieJar {

    private data class CookieKey(val host: String, val name: String)
    private val cache = ConcurrentHashMap<CookieKey, Cookie>()

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return cache.values.filter { it.matches(url) && it.expiresAt >= now }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { cache[CookieKey(url.host, it.name)] = it }
    }
}

// Adds Referer + User-Agent headers, then delegates to the parser's own intercept()
// (many parsers use intercept() to sign requests with auth tokens, etc.)
internal class CommonHeadersInterceptor(private val context: MangaLoaderContextImpl) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val source  = request.tag(MangaSource::class.java) as? MangaParserSource
        val parser  = source?.let { context.getParser(it) }

        val headers = request.headers.newBuilder().apply {
            if (get("Referer") == null && parser != null)
                set("Referer", "https://${parser.domain}/")
            if (get("User-Agent") == null)
                set("User-Agent", context.getDefaultUserAgent())
        }.build()

        val newRequest = request.newBuilder().headers(headers).build()

        return if (parser is Interceptor)
            parser.intercept(ProxyChain(chain, newRequest))
        else
            chain.proceed(newRequest)
    }

    private class ProxyChain(
        private val delegate: Interceptor.Chain,
        private val request: Request,
    ) : Interceptor.Chain by delegate {
        override fun request(): Request = request
    }
}

// Detects CloudFlare challenges and throws instead of silently passing through
private class CloudFlareInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response   = chain.proceed(chain.request())
        val protection = CloudFlareHelper.checkResponseForProtection(response)
        if (protection != CloudFlareHelper.PROTECTION_NOT_DETECTED) {
            response.closeQuietly()
            val reason = if (protection == CloudFlareHelper.PROTECTION_CAPTCHA) "CAPTCHA" else "BLOCKED"
            throw CloudFlareProtectedException(url = response.request.url.toString(), reason = reason)
        }
        return response
    }
}

/** Thrown when a CloudFlare challenge is detected on a source. */
class CloudFlareProtectedException(val url: String, val reason: String) :
    Exception("CloudFlare $reason detected at $url — a browser session with cf_clearance cookie is required")

// Wraps BufferedImage as a kotatsu Bitmap for tile-descrambling parsers
class JvmBitmap(val image: BufferedImage) : Bitmap {

    override val width:  Int get() = image.width
    override val height: Int get() = image.height

    override fun drawBitmap(sourceBitmap: Bitmap, src: Rect, dst: Rect) {
        require(sourceBitmap is JvmBitmap) { "Expected JvmBitmap, got ${sourceBitmap::class}" }
        val g = image.createGraphics()
        try {
            val tile = sourceBitmap.image.getSubimage(src.left, src.top, src.width, src.height)
            g.drawImage(tile, dst.left, dst.top, dst.width, dst.height, null)
        } finally {
            g.dispose()
        }
    }
}
