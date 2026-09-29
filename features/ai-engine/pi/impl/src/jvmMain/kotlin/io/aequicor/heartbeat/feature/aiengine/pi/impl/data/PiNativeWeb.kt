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
 *
 * Every failure carries one-line [SearchException.details] in the stable format
 * `<category>[ <status or cause>] — url=<url>[ — <extra>]`, with a category from a fixed set:
 * `http_error` (4xx/5xx with the status line and a short sanitized text-body snippet), `network_error`,
 * `timeout`, `redirect_loop`, `invalid_redirect` (a 3xx without a usable Location), `unsupported_content_type`,
 * `too_large`, `empty_response` and `invalid_url` (a non-public address). Binary bodies are reported only by
 * type and size; secrets never appear, so the line is safe for logs and for the model context.
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
        if (!isPublicWebUrl(url)) {
            fail(SearchFailure.InvalidInput, "invalid_url — url=$url — not a public http(s) address")
        }
        var target = url
        var response = get(target)
        var redirects = 0
        while (!response.status.isSuccess()) {
            if (response.status.value !in REDIRECT_RANGE) failHttp(target, response)
            if (redirects >= MAX_REDIRECTS) {
                log.w { "Pi native page read stopped after $MAX_REDIRECTS redirects" }
                fail(
                    SearchFailure.Unavailable,
                    "redirect_loop — url=$url — stopped after $MAX_REDIRECTS redirects at $target " +
                        "(${statusLine(response.status)})",
                )
            }
            target = resolve(target, redirectLocation(target, response))
            response = get(target)
            redirects++
        }
        return content(target, response)
    }

    private suspend fun get(url: String): HttpResponse = networkResult { guarded.get(url) }
        .getOrElse { error ->
            if (error is CancellationException) throw error
            log.w(error) { "Pi native page read failed" }
            throw transportFailure(url, error)
        }

    /** One-line verdict for an HTTP error status: the status line, the URL and a short body snippet. */
    private suspend fun failHttp(url: String, response: HttpResponse): Nothing {
        val status = response.status
        log.w { "Pi native page read stopped at status ${status.value}" }
        fail(httpFailure(status), "http_error ${statusLine(status)} — url=$url${bodyPart(response)}")
    }

    private fun redirectLocation(url: String, response: HttpResponse): String =
        response.headers[HttpHeaders.Location]?.takeIf { it.isNotBlank() }
            ?: fail(
                SearchFailure.InvalidResponse,
                "invalid_redirect — url=$url — ${statusLine(response.status)} without a Location header",
            )

    /** Resolves one redirect hop against [base]; a hop leaving the public web fails the whole read. */
    private fun resolve(base: String, location: String): String {
        val next = try {
            URI(base).resolve(location)?.toString().orEmpty()
        } catch (e: URISyntaxException) {
            log.w(e) { "Pi native page redirect is malformed" }
            throw malformedRedirect(base, location)
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Pi native page redirect is malformed" }
            throw malformedRedirect(base, location)
        }
        if (!isPublicWebUrl(next)) {
            log.w { "Pi native page redirect left the public web" }
            fail(
                SearchFailure.InvalidInput,
                "invalid_url — url=$next — redirect target is not a public http(s) address",
            )
        }
        return next
    }

    private fun malformedRedirect(base: String, location: String): SearchException = SearchException(
        SearchFailure.InvalidResponse,
        "invalid_redirect — url=$base — malformed location \"${snippetOf(location)}\"",
    )

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
            fail(
                SearchFailure.InvalidResponse,
                "unsupported_content_type — url=$url — content-type=${type ?: "unknown"}${sizePart(response)}",
            )
        }
        val body = response.bodyAsText()
        if (body.length > MAX_RESPONSE_CHARS) {
            fail(
                SearchFailure.InvalidResponse,
                "too_large — url=$url — size: ${body.length} chars, limit: $MAX_RESPONSE_CHARS chars",
            )
        }
        val isHtmlPage = isHtml(type, body)
        val text = (if (isHtmlPage) htmlToText(body) else body).trim()
        if (text.isBlank()) {
            fail(SearchFailure.InvalidResponse, "empty_response — url=$url — status ${statusLine(response.status)}")
        }
        log.d { "Pi native page read succeeded" }
        return ResourceContent(url, if (isHtmlPage) htmlTitle(body) else null, text.take(MAX_TEXT_CHARS))
    }

    private fun isReadable(type: ContentType?): Boolean {
        if (type == null) return true
        if (type.match(ContentType.Text.Any)) return true
        if (type.match(ContentType.Application.Json)) return true
        if (type.match(ContentType.Application.Xml)) return true
        val hasReadableSubtype = READABLE_APPLICATION_SUBTYPES.any { type.contentSubtype.contains(it) }
        return type.contentType == "application" && hasReadableSubtype
    }

    private fun isHtml(type: ContentType?, body: String): Boolean {
        if (type != null) return type.contentSubtype.contains("html")
        val probe = body.take(HTML_PROBE_CHARS).lowercase()
        return probe.contains("<!doctype html") || probe.trimStart().startsWith("<html")
    }

    /** Body tail of an HTTP error line: a quoted text snippet, or only the type and size of a binary body. */
    private suspend fun bodyPart(response: HttpResponse): String {
        val type = contentType(response)
        if (type != null && !isReadable(type)) {
            val bytes = byteSize(response)
            val size = if (bytes == null) "" else ", $bytes bytes"
            return " — body omitted: binary content-type $type$size"
        }
        val body = try {
            response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.w(e) { "Pi native page read could not read the error body" }
            return ""
        }
        val snippet = snippetOf(body)
        return if (snippet.isEmpty()) "" else " — body: \"$snippet\""
    }

    /** `404 Not Found`-style status; Ktor's placeholder reason for unknown codes is dropped. */
    private fun statusLine(status: HttpStatusCode): String {
        val reason = status.description.takeUnless { it.isBlank() || it == UNKNOWN_STATUS_TEXT }
        return if (reason == null) "${status.value}" else "${status.value} $reason"
    }

    /** Declared byte size of one response, when the header is present and numeric. */
    private fun byteSize(response: HttpResponse): Long? = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()

    private fun sizePart(response: HttpResponse): String {
        val bytes = byteSize(response) ?: return ""
        return " — size: $bytes bytes"
    }

    /** Collapses whitespace and control characters, then truncates to a diagnostic snippet. */
    private fun snippetOf(text: String): String {
        val collapsed = text.replace(SNIPPET_NOISE, " ").trim()
        if (collapsed.isEmpty()) return ""
        val cut = collapsed.take(SNIPPET_CHARS)
        return if (collapsed.length > cut.length) "$cut…" else cut
    }

    private fun transportFailure(url: String, error: Throwable): SearchException = when (error) {
        is NetworkException.Http ->
            SearchException(httpFailure(error.status), "http_error ${statusLine(error.status)} — url=$url")

        is NetworkException.Timeout ->
            SearchException(SearchFailure.Timeout, "timeout — url=$url — cause: ${error.origin}")

        is NetworkException.Connectivity ->
            SearchException(SearchFailure.Connectivity, "network_error — url=$url — cause: ${error.origin}")

        is NetworkException.InvalidResponse -> SearchException(
            SearchFailure.InvalidResponse,
            "unsupported_content_type — url=$url — cause: undecodable body (${error.origin})",
        )

        else -> SearchException(
            SearchFailure.InvalidResponse,
            "network_error — url=$url — cause: ${error::class.simpleName ?: "unknown"}",
        )
    }

    private fun httpFailure(status: HttpStatusCode): SearchFailure = when (status.value) {
        HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value -> SearchFailure.Authentication
        HTTP_RATE_LIMITED -> SearchFailure.RateLimited
        else -> SearchFailure.Unavailable
    }

    private fun fail(failure: SearchFailure, details: String): Nothing = throw SearchException(failure, details)

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
        const val SNIPPET_CHARS = 200
        const val UNKNOWN_STATUS_TEXT = "Unknown Status"
    }
}

private val SNIPPET_NOISE = Regex("[\\x00-\\x1F\\x7F\\s]+")

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
internal fun htmlToText(html: String): String {
    val stripped = html
        .replace(HTML_COMMENT, " ")
        .replace(HTML_SKIP_BLOCK, " ")
        .replace(HTML_LINE_BREAK, "\n")
        .replace(HTML_TAG, " ")
    return decodeHtmlEntities(stripped)
        .lineSequence()
        .map { line -> line.replace(HTML_SPACES, " ").trim() }
        .joinToString("\n")
        .replace(HTML_BLANK_LINES, "\n\n")
        .trim()
}

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

private fun codePoint(value: Int?): String? {
    if (value == null || value !in PRINTABLE_CHAR_MIN..MAX_CODE_POINT) return null
    if (value in SURROGATE_MIN..SURROGATE_MAX) return null
    return String(Character.toChars(value))
}

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
