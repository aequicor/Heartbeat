package io.aequicor.heartbeat.core.network.impl.release

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.common.release.isTrustedReleaseUrl
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.util.AttributeKey
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Shared metadata transport. The consumer logs expected failures once, after mapping its own failure type. */
@Inject
@ContributesBinding(AppScope::class)
internal class HttpReleaseSource(private val client: HttpClient, private val dispatchers: DispatcherProvider) :
    ReleaseSource {
    private val log = Log.tag("ReleaseSource")

    // Parser exceptions contain untrusted response fragments; expose only the safe failure category.
    @Suppress("SwallowedException")
    override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease =
        withContext(dispatchers.io) {
            require(RepositorySegment.matches(owner) && RepositorySegment.matches(repository)) { "Invalid repository" }
            val body = document(
                "https://api.github.com/repos/$owner/$repository/releases/latest",
                setOf("api.github.com"),
            )
            try {
                FeedJson.decodeFromString<GitHubRelease>(body)
            } catch (e: SerializationException) {
                throw ReleaseException(ReleaseFailure.InvalidMetadata, origin = e::class.simpleName)
            } catch (e: IllegalArgumentException) {
                throw ReleaseException(ReleaseFailure.InvalidMetadata, origin = e::class.simpleName)
            }
        }

    override suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int): String =
        withContext(dispatchers.io) {
            require(maxBytes in 1 until Int.MAX_VALUE) { "Invalid document limit" }
            requireReleaseUrl(url, allowedHosts)
            log.i { "read release metadata" }
            releaseRequest {
                client.prepareGet(url) {
                    attributes.put(ReleaseRequest, Unit)
                    expectSuccess = false
                    header(HttpHeaders.Accept, "application/vnd.github+json, application/json, text/plain")
                    timeout { requestTimeoutMillis = DOCUMENT_TIMEOUT_MILLIS }
                }.execute { response -> read(response, allowedHosts, maxBytes) }
            }.also { log.i { "release metadata read" } }
        }

    private suspend fun read(response: HttpResponse, allowedHosts: Set<String>, maxBytes: Int): String {
        requireTrustedRelease(response, allowedHosts)
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(maxBytes + 1)
        var size = 0
        while (size < buffer.size) {
            val read = channel.readAvailable(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        if (size > maxBytes) throw ReleaseException(ReleaseFailure.TooLarge)
        return buffer.decodeToString(0, size)
    }
}

/** Release requests have operation logs here and one failure log at the feature boundary, without URL paths. */
internal val ReleaseRequest: AttributeKey<Unit> = AttributeKey("ReleaseRequest")

internal fun requireReleaseUrl(url: String, allowedHosts: Set<String>) {
    if (!isTrustedReleaseUrl(url, allowedHosts)) throw ReleaseException(ReleaseFailure.UntrustedSource)
}

internal fun requireTrustedRelease(response: HttpResponse, allowedHosts: Set<String>) {
    val final = response.call.request.url
    if (final.protocol != URLProtocol.HTTPS || final.host.lowercase() !in allowedHosts) {
        throw ReleaseException(ReleaseFailure.UntrustedSource)
    }
    if (response.status.value !in HTTP_SUCCESS) throw ReleaseException(ReleaseFailure.Http, response.status.value)
}

internal suspend fun <T> releaseRequest(block: suspend () -> T): T = networkResult(block).getOrElse { error ->
    throw when (error) {
        is NetworkException.Http -> ReleaseException(ReleaseFailure.Http, error.status.value)
        else -> ReleaseException(ReleaseFailure.Network)
    }
}

private const val DOCUMENT_TIMEOUT_MILLIS = 60_000L
private val RepositorySegment = Regex("[A-Za-z0-9_.-]+")
private val FeedJson = Json { ignoreUnknownKeys = true }
private val HTTP_SUCCESS = 200..299
