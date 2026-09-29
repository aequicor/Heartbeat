package io.aequicor.heartbeat.feature.searchengine.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.searchengine.api.ResourceContent
import io.aequicor.heartbeat.feature.searchengine.api.SearchException
import io.aequicor.heartbeat.feature.searchengine.api.SearchFailure
import io.aequicor.heartbeat.feature.searchengine.api.SearchOperation
import io.aequicor.heartbeat.feature.searchengine.api.SearchResult
import io.aequicor.heartbeat.feature.searchengine.api.isPublicWebUrl
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** Querit wire adapter. Uses the app HTTP client, whose logging redacts authorization headers. */
@Inject
internal class QueritApi(private val client: HttpClient, private val preferences: SearchOptions) {
    private val log = Log.tag("SearchQuerit")
    private val json = Json { ignoreUnknownKeys = true }

    // Derived once: shares the app engine, only disables redirects so the bearer never follows another origin.
    private val noRedirects by lazy { client.config { followRedirects = false } }

    suspend fun search(query: String, count: Int): List<SearchResult> {
        log.d { "Searching Querit, count=$count" }
        if (query.isBlank() || count !in MIN_RESULTS..MAX_RESULTS) fail(SearchFailure.InvalidInput)
        val body = buildJsonObject {
            put("query", JsonPrimitive(query.trim()))
            put("count", JsonPrimitive(count))
        }
        val root = request(SearchOperation.Search, "search", body)
        val raw = (root["results"] as? JsonObject)?.get("result") as? JsonArray
            ?: root["results"] as? JsonArray
            ?: fail(SearchFailure.InvalidResponse)
        return raw.asSequence().mapNotNull { value ->
            val item = value as? JsonObject ?: return@mapNotNull null
            val url = item.string("url") ?: return@mapNotNull null
            if (!isPublicWebUrl(url)) return@mapNotNull null
            SearchResult(url, item.string("title").orEmpty(), item.string("snippet").orEmpty())
        }.distinctBy { it.url }.take(count).toList().also {
            if (it.isEmpty()) fail(SearchFailure.InvalidResponse)
        }
    }

    suspend fun fetch(url: String): ResourceContent {
        log.d { "Fetching Querit content" }
        if (!isPublicWebUrl(url)) fail(SearchFailure.InvalidInput)
        val body = buildJsonObject {
            put("urls", JsonArray(listOf(JsonPrimitive(url))))
            put("format", JsonPrimitive("markdown"))
        }
        val root = request(SearchOperation.Contents, "contents", body)
        val items = when (val results = root["results"]) {
            is JsonArray -> results
            is JsonObject -> results["result"] as? JsonArray
            else -> root["data"] as? JsonArray
        } ?: fail(SearchFailure.InvalidResponse)
        val item = items.firstOrNull() as? JsonObject ?: fail(SearchFailure.InvalidResponse)
        if (item.string("status")?.lowercase() == "failed") fail(SearchFailure.Unavailable)
        val content = item.string("content") ?: item.string("text")
            ?: fail(SearchFailure.InvalidResponse)
        if (content.isBlank()) fail(SearchFailure.InvalidResponse)
        val meta = item["meta"] as? JsonObject
        return ResourceContent(item.string("url") ?: url, meta?.string("title") ?: item.string("title"), content)
    }

    private suspend fun request(operation: SearchOperation, endpoint: String, body: JsonObject): JsonObject {
        val host = validHost(preferences.host(operation))
        val credential = preferences.credential(operation)
        val result = credential.use { secret ->
            secret.reveal { chars ->
                // The HTTP request retains the bearer value only for this call; no credential is persisted in a URL.
                // Trimmed for keys saved before save-time normalization:
                // surrounding whitespace breaks the header for good.
                chars.concatToString().trim()
            }.let { key ->
                networkResult {
                    noRedirects.post("$host/v1/$endpoint") {
                        bearerAuth(key)
                        contentType(ContentType.Application.Json)
                        setBody(body.toString())
                    }.body<String>()
                }
            }
        }
        val text = result.getOrElse { error ->
            val failure = networkFailure(error)
            log.w(error) { "Querit $endpoint failed: $failure" }
            fail(failure)
        }
        return parseResponse(text)
    }

    private fun parseResponse(text: String): JsonObject {
        if (text.length > MAX_RESPONSE_CHARS) fail(SearchFailure.InvalidResponse)
        val root = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: IllegalArgumentException) {
            log.w(e) { "Querit returned invalid JSON" }
            fail(SearchFailure.InvalidResponse)
        }
        val code = (root["error_code"] as? JsonPrimitive)?.intOrNull
        if (code != null && code != HTTP_OK) {
            val failure = if (code == HTTP_UNAUTHORIZED || code == HTTP_FORBIDDEN) {
                SearchFailure.Authentication
            } else {
                SearchFailure.Unavailable
            }
            fail(failure)
        }
        return root
    }

    private fun networkFailure(error: Throwable): SearchFailure = when (error) {
        is NetworkException.Http -> when (error.status.value) {
            HTTP_UNAUTHORIZED, HTTP_FORBIDDEN -> SearchFailure.Authentication
            HTTP_RATE_LIMITED -> SearchFailure.RateLimited
            else -> SearchFailure.Unavailable
        }

        is NetworkException.Timeout -> SearchFailure.Timeout

        is NetworkException.Connectivity -> SearchFailure.Connectivity

        else -> SearchFailure.InvalidResponse
    }

    private companion object {
        const val MAX_RESPONSE_CHARS = 2_000_000
        const val MIN_RESULTS = 1
        const val MAX_RESULTS = 20
        const val HTTP_OK = 200
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_FORBIDDEN = 403
        const val HTTP_RATE_LIMITED = 429
    }
}

private fun fail(failure: SearchFailure): Nothing = throw SearchException(failure)

private fun JsonObject.string(name: String): String? =
    (get(name) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
