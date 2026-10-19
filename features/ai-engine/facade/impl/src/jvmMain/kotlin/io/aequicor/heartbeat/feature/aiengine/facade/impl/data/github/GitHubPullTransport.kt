package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.github

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.logging.redactedFailure
import io.aequicor.heartbeat.core.network.SensitiveNetworkRequest
import io.aequicor.heartbeat.core.network.networkResult
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.retry
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

internal fun interface GitHubPullTransport {
    suspend fun call(
        configuration: GitHubPullConfiguration,
        method: String,
        path: String,
        body: JsonObject?,
    ): JsonObject
}

/**
 * REST over the shared Ktor network infrastructure; ready uses one fixed GraphQL mutation.
 * The derived client preserves NET redaction and the shared engine, but never follows redirects.
 * No request is retried. Cancellation/timeout after a write is an unknown outcome, requiring a new read.
 */
@Inject
@ContributesBinding(ProfileScope::class)
internal class HttpGitHubPullTransport(private val client: HttpClient, private val dispatchers: DispatcherProvider) :
    GitHubPullTransport {
    private val log = Log.tag("GitHubPullTransport")

    override suspend fun call(
        configuration: GitHubPullConfiguration,
        method: String,
        path: String,
        body: JsonObject?,
    ): JsonObject = withContext(dispatchers.io) {
        require(method in setOf("GET", "POST", "PATCH"))
        require(
            (method == "GET" && path == "/user") || path == "/graphql" || path.startsWith(
                "/repos/${configuration.repository}/",
            ) ||
                path == "/repos/${configuration.repository}",
        )
        require('?' !in path && '#' !in path && '%' !in path && path.split('/').none { it == "." || it == ".." })
        val text = networkResult {
            client.config { followRedirects = false }.use { bounded ->
                bounded.prepareRequest("https://api.github.com$path") {
                    this.method = HttpMethod.parse(method)
                    attributes.put(SensitiveNetworkRequest, Unit)
                    expectSuccess = false
                    retry { maxRetries = 0 }
                    timeout { requestTimeoutMillis = 30_000 }
                    header(HttpHeaders.Authorization, configuration.authorization())
                    header(HttpHeaders.Accept, "application/vnd.github+json")
                    header("X-GitHub-Api-Version", "2026-03-10")
                    if (body != null) {
                        header(HttpHeaders.ContentType, "application/json")
                        setBody(body.toString())
                    }
                }.execute { response -> readBounded(response) }
            }
        }.getOrElse { throw IllegalStateException("GitHub transport failed; reconcile before retry", it) }
        try {
            Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid GitHub response")
        } catch (error: SerializationException) {
            log.w(error.redactedFailure()) { "Invalid GitHub JSON" }
            error("Invalid GitHub JSON; reconcile before retry")
        }
    }
}

private suspend fun readBounded(response: HttpResponse): String {
    check(response.status.value in HTTP_SUCCESS) { "GitHub HTTP ${response.status.value}; reconcile before retry" }
    val bytes = ByteArray(MAX_RESPONSE_BYTES + 1)
    val channel = response.bodyAsChannel()
    var size = 0
    while (size < bytes.size) {
        val count = channel.readAvailable(bytes, size, bytes.size - size)
        if (count < 0) break
        size += count
    }
    check(size <= MAX_RESPONSE_BYTES) { "GitHub response too large; reconcile before retry" }
    return bytes.decodeToString(0, size, throwOnInvalidSequence = true)
}

private const val MAX_RESPONSE_BYTES = 1024 * 1024
private val HTTP_SUCCESS = 200..299
