package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.ktor.client.HttpClient
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.cookies.CookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.http.BadContentTypeFormatException
import io.ktor.http.ContentType
import io.ktor.http.Cookie
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.Url
import io.ktor.http.takeFrom
import io.ktor.util.AttributeKey
import java.net.URI
import java.net.URISyntaxException

internal val DocumentAssetUrl = AttributeKey<Url>("DocumentAssetUrl")

/** The final send hook removes inherited defaults after cookies and other request plugins have run. */
private val AnonymousDocumentAsset = createClientPlugin("AnonymousDocumentAsset") {
    on(SendingRequest) { request, _ ->
        request.headers.clear()
        request.url.takeFrom(request.attributes[DocumentAssetUrl])
        request.method = HttpMethod.Get
    }
}

internal fun HttpClient.documentAssetClient(): HttpClient = config {
    followRedirects = false
    expectSuccess = false
    // Replace, rather than extend, inherited initialization callbacks and cookie storage.
    installOrReplace(HttpCookies) { storage = NoDocumentCookies }
    install(AnonymousDocumentAsset)
}

private object NoDocumentCookies : CookiesStorage {
    override suspend fun get(requestUrl: Url): List<Cookie> = emptyList()
    override suspend fun addCookie(requestUrl: Url, cookie: Cookie) = Unit
    override fun close() = Unit
}

/** URI first: Ktor otherwise supplies a default host for malformed or relative input. */
internal fun documentAssetUrl(raw: String, base: Url? = null): Url? {
    if (raw.isBlank() || raw.length > MAX_URL_LENGTH) return null
    return try {
        val supplied = URI(raw)
        val uri = base?.let { URI(it.toString()).resolve(supplied) } ?: supplied
        if (!uri.isAssetOrigin() || uri.toASCIIString().length > MAX_URL_LENGTH) {
            null
        } else {
            // Fragments are local references, never part of the network request or its inherited defaults.
            Url(uri.toASCIIString().substringBefore('#'))
        }
    } catch (error: URISyntaxException) {
        log.w(error.redactedFailure()) { "Document asset URL rejected" }
        null
    }
}

private fun URI.isAssetOrigin(): Boolean = when {
    scheme?.lowercase() !in setOf("http", "https") -> false
    host.isNullOrEmpty() || rawUserInfo != null -> false
    port != -1 && port !in 1..MAX_PORT -> false
    else -> true
}

internal data class DocumentAssetMetadata(val mimeType: String, val headers: Map<String, String>) {
    override fun toString(): String = "DocumentAssetMetadata(redacted)"
}

/** Preserve actual CORS declarations; synthesizing wildcard access would grant an origin the server did not. */
internal fun documentAssetMetadata(headers: Headers): DocumentAssetMetadata? {
    // An undecoded entity must not be mislabeled as plain bytes when Chromium receives it.
    if (headers[HttpHeaders.ContentEncoding]?.equals("identity", ignoreCase = true) == false) return null
    val contentType = headers[HttpHeaders.ContentType] ?: "application/octet-stream"
    if (!contentType.isBoundedHeader()) return null
    val mime = try {
        ContentType.parse(contentType).withoutParameters().toString()
    } catch (error: BadContentTypeFormatException) {
        log.w(error.redactedFailure()) { "Document asset Content-Type rejected" }
        return null
    }
    val result = buildMap {
        put(HttpHeaders.ContentType, contentType)
        putAll(documentAssetCorsHeaders(headers))
    }
    return DocumentAssetMetadata(mime, result)
}

internal fun documentAssetCorsHeaders(headers: Headers): Map<String, String> = buildMap {
    CORS_HEADERS.forEach { name ->
        val values = headers.getAll(name)
        if (values?.size == 1) values.single().takeIf { it.isBoundedHeader() }?.let { put(name, it) }
    }
}

private fun String.isBoundedHeader(): Boolean = length <= MAX_HEADER_LENGTH && none { it < ' ' || it == '\u007f' }

private val log = Log.tag("DocumentAssetHttp")
private const val MAX_URL_LENGTH = 8192
private const val MAX_HEADER_LENGTH = 2048
private const val MAX_PORT = 65535
private val CORS_HEADERS = listOf(
    HttpHeaders.AccessControlAllowOrigin,
    HttpHeaders.AccessControlAllowCredentials,
    "Cross-Origin-Resource-Policy",
)
