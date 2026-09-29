package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.searchengine.api.NativeWebFetch
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import java.net.URI
import java.net.URISyntaxException

/**
 * Pi's own page reader. Pi has no vendor web tool, so the runtime publishes this in-process reader as its
 * [NativeWebFetch]: the profile router prefers it over the Querit provider and falls back only on failure.
 * Like the bridge, it never reads local or private addresses — every redirect hop is revalidated.
 */
@Inject
internal class PiNativeWeb(private val client: HttpClient) : NativeWebFetch {
    private val log = Log.tag("PiNativeWeb")

    // Derived once: statuses are handled here, and redirects are followed manually through the URL guard.
    private val guarded by lazy {
        client.config {
            expectSuccess = false
            followRedirects = false
        }
    }

    override suspend fun fetch(url: String): ResourceContent {
        if (!isPublicWebUrl(url)) fail(SearchFailure.InvalidInput)
        var target = url
        var response = get(target)
        var redirects = 0
        while (!response.status.isSuccess()) {
            target = resolve(target, redirectLocation(response, redirects++))
            response = get(target)
        }
        return content(target, response)
    }

    private suspend fun get(url: String): HttpResponse = networkResult { guarded.get(url) }
        .getOrElse { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Pi native page read failed" }
            fail(networkFailure(error))
        }

    private fun redirectLocation(response: HttpResponse, redirects: Int): String {
        if (response.status.value !in REDIRECT_RANGE || redirects >= MAX_REDIRECTS) {
            log.w { "Pi native page read stopped at status ${response.status.value}" }
            fail(httpFailure(response.status))
        }
        return response.headers[HttpHeaders.Location]?.takeIf { it.isNotBlank() }
            ?: fail(SearchFailure.InvalidResponse)
    }

    /** Resolves one redirect hop against [base]; a hop leaving the public web fails the whole read. */
    private fun resolve(base: String, location: String): String {
        val next = try {
            URI(base).resolve(location)?.toString().orEmpty()
        } catch (e: URISyntaxException) {
            log.w(e) { "Pi native page redirect is malformed" }
            fail(SearchFailure.InvalidResponse)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Pi native page redirect is malformed" }
            fail(SearchFailure.InvalidResponse)
        }
        if (!isPublicWebUrl(next)) {
            log.w { "Pi native page redirect left the public web" }
            fail(SearchFailure.InvalidInput)
        }
        return next
    }

    /** Parsed `Content-Type` of one response; a malformed header degrades to an unknown type, not a failure. */
    private fun contentType(response: HttpResponse): ContentType? {
        val header = response.headers[HttpHeaders.ContentType]?.takeIf { it.isNotBlank() } ?: return null
        return try {
            ContentType.parse(header)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Pi native page read got a malformed content type" }
            null
        }
    }

    private suspend fun content(url: String, response: HttpResponse): ResourceContent {
        val type = contentType(response)
        if (!isReadable(type)) {
            log.w { "Pi native page read got unreadable content type" }
            fail(SearchFailure.InvalidResponse)
        }
        val body = response.bodyAsText()
        if (body.length > MAX_RESPONSE_CHARS) fail(SearchFailure.InvalidResponse)
        val html = isHtml(type, body)
        val text = (if (html) htmlToText(body) else body).trim()
        if (text.isBlank()) fail(SearchFailure.InvalidResponse)
        log.d { "Pi native page read succeeded" }
        return ResourceContent(url, if (html) htmlTitle(body) else null, text.take(MAX_TEXT_CHARS))
    }

    private fun isReadable(type: ContentType?): Boolean {
        if (type == null) return true
        if (type.match(ContentType.Text.Any)) return true
        if (type.match(ContentType.Application.Json)) return true
        if (type.match(ContentType.Application.Xml)) return true
        val readableApplication = READABLE_APPLICATION_SUBTYPES.any { type.contentSubtype.contains(it) }
        return type.contentType == "application" && readableApplication
    }

    private fun isHtml(type: ContentType?, body: String): Boolean {
        if (type != null) return type.contentSubtype.contains("html")
        val probe = body.take(HTML_PROBE_CHARS).lowercase()
        return probe.contains("<!doctype html") || probe.trimStart().startsWith("<html")
    }

    private fun networkFailure(error: Throwable): SearchFailure = when (error) {
        is NetworkException.Http -> httpFailure(error.status)
        is NetworkException.Timeout -> SearchFailure.Timeout
        is NetworkException.Connectivity -> SearchFailure.Connectivity
        else -> SearchFailure.InvalidResponse
    }

    private fun httpFailure(status: HttpStatusCode): SearchFailure = when (status.value) {
        HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value -> SearchFailure.Authentication
        HTTP_RATE_LIMITED -> SearchFailure.RateLimited
        else -> SearchFailure.Unavailable
    }

    private fun fail(failure: SearchFailure): Nothing = throw SearchException(failure)

    private companion object {
        val REDIRECT_RANGE = REDIRECT_STATUS_MIN..REDIRECT_STATUS_MAX
        val READABLE_APPLICATION_SUBTYPES = listOf("json", "xml", "yaml", "markdown")
        const val REDIRECT_STATUS_MIN = 300
        const val REDIRECT_STATUS_MAX = 399
        const val MAX_REDIRECTS = 5
        const val MAX_RESPONSE_CHARS = 2_000_000
        const val MAX_TEXT_CHARS = 1_000_000
        const val HTML_PROBE_CHARS = 512
        const val HTTP_RATE_LIMITED = 429
    }
}

private val HTML_COMMENT = Regex("<!--[\\s\\S]*?-->")
private val HTML_SKIP_BLOCK = Regex("(?is)<(script|style|noscript|template|svg|iframe)\\b[\\s\\S]*?</\\1\\s*>")
private val HTML_LINE_BREAK = Regex(
    "(?i)<br\\s*/?>" +
        "|</(p|div|li|ul|ol|h[1-6]|tr|table|section|article|header|footer|nav|aside|main|blockquote|pre" +
        "|figure|figcaption|form|fieldset|details|dd|dt|address)\\s*>|<hr\\b[^>]*>",
)
private val HTML_TAG = Regex("<[^>]*>")
private val HTML_SPACES = Regex("[\\t\\x0B\\f\\r ]+")
private val HTML_BLANK_LINES = Regex("\\n{3,}")
private val HTML_TITLE = Regex("(?is)<title\\b[^>]*>([\\s\\S]*?)</title>")
private val HTML_ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});")

/** Reduces one HTML document to readable plain text: markup noise is dropped, entities are decoded. */
internal fun htmlToText(html: String): String = html
    .replace(HTML_COMMENT, " ")
    .replace(HTML_SKIP_BLOCK, " ")
    .replace(HTML_LINE_BREAK, "\n")
    .replace(HTML_TAG, " ")
    .let(::decodeHtmlEntities)
    .lineSequence()
    .map { line -> line.replace(HTML_SPACES, " ").trim() }
    .joinToString("\n")
    .replace(HTML_BLANK_LINES, "\n\n")
    .trim()

/** Decoded `<title>` of one HTML document, or null when it has none. */
internal fun htmlTitle(html: String): String? = HTML_TITLE.find(html)
    ?.groupValues?.get(1)
    ?.let(::decodeHtmlEntities)
    ?.replace(HTML_SPACES, " ")
    ?.trim()
    ?.takeIf { it.isNotEmpty() }
    ?.take(HTML_TITLE_CHARS)

/** Single pass, so an entity's decoded text is never re-decoded; unknown entities stay as written. */
internal fun decodeHtmlEntities(text: String): String = HTML_ENTITY.replace(text) { match ->
    val body = match.groupValues[1]
    when {
        body.startsWith("#x", ignoreCase = true) -> codePoint(body.substring(2).toIntOrNull(HEX_RADIX))
        body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull())
        else -> NAMED_HTML_ENTITIES[body.lowercase()]
    } ?: match.value
}

private fun codePoint(value: Int?): String? = value
    ?.takeIf { it in PRINTABLE_CHAR_MIN..MAX_CODE_POINT && it !in SURROGATE_MIN..SURROGATE_MAX }
    ?.let { String(Character.toChars(it)) }

private val NAMED_HTML_ENTITIES = mapOf(
    "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
    "nbsp" to " ", "mdash" to "\u2014", "ndash" to "\u2013", "hellip" to "\u2026",
    "laquo" to "\u00AB", "raquo" to "\u00BB", "copy" to "\u00A9", "reg" to "\u00AE", "trade" to "\u2122",
    "lsquo" to "\u2018", "rsquo" to "\u2019", "ldquo" to "\u201C", "rdquo" to "\u201D",
    "bull" to "\u2022", "middot" to "\u00B7", "deg" to "\u00B0", "plusmn" to "\u00B1",
    "times" to "\u00D7", "divide" to "\u00F7", "euro" to "\u20AC", "pound" to "\u00A3", "yen" to "\u00A5",
)

private const val HTML_TITLE_CHARS = 200
private const val HEX_RADIX = 16
private const val PRINTABLE_CHAR_MIN = 0x20
private const val MAX_CODE_POINT = 0x10FFFF
private const val SURROGATE_MIN = 0xD800
private const val SURROGATE_MAX = 0xDFFF
