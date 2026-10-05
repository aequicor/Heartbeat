package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.hostOf
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.utils.io.readAvailable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * [ReleaseFeeds] over the app's HTTP client: https only, the final URL after redirects must stay on the allowed
 * hosts, and a document is at most [maxBytes]. Requests carry no credentials, so GitHub's anonymous rate limit
 * applies; it surfaces as [InstallFailureReason.RateLimited].
 */
class HttpReleaseFeeds(private val client: HttpClient, private val maxBytes: Int = MAX_DOCUMENT_BYTES) : ReleaseFeeds {
    private val log = Log.tag("ReleaseFeeds")

    override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease {
        require(RepositorySegment.matches(owner) && RepositorySegment.matches(repository)) { "Invalid repository" }
        log.i { "latest GitHub release repository=$owner/$repository" }
        val body = fetch("https://$GITHUB_API_HOST/repos/$owner/$repository/releases/latest", setOf(GITHUB_API_HOST))
        val release = decodeRelease(body)
        if (release == null || release.isDraft || release.isPrerelease) {
            log.w { "no published GitHub release repository=$owner/$repository" }
            throw installFailure(InstallFailureReason.NoRelease)
        }
        return release
    }

    private fun decodeRelease(body: String): GitHubRelease? = try {
        FeedJson.decodeFromString(GitHubRelease.serializer(), body)
    } catch (e: SerializationException) {
        log.w(e) { "GitHub release metadata is malformed" }
        null
    } catch (e: IllegalArgumentException) {
        log.w(e) { "GitHub release metadata is malformed" }
        null
    }

    override suspend fun document(url: String, allowedHosts: Set<String>): String {
        log.i { "release document host=${hostOf(url)}" }
        return fetch(url, allowedHosts)
    }

    private suspend fun fetch(url: String, allowedHosts: Set<String>): String {
        if (!url.startsWith("https://") || hostOf(url) !in allowedHosts) {
            log.w { "release metadata outside the trusted hosts host=${hostOf(url)}" }
            throw installFailure(InstallFailureReason.UntrustedSource)
        }
        return networkResult {
            client.prepareGet(url) {
                header(HttpHeaders.Accept, "application/vnd.github+json, application/json, text/plain")
                timeout { requestTimeoutMillis = DOCUMENT_TIMEOUT_MILLIS }
            }.execute { response -> read(response, allowedHosts) }
        }.getOrElse { error ->
            log.w(error) { "release metadata request failed host=${hostOf(url)}" }
            throw error.asInstallFailure()
        }
    }

    private suspend fun read(response: HttpResponse, allowedHosts: Set<String>): String {
        requireTrusted(response, allowedHosts)
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(maxBytes + 1)
        var size = 0
        while (size < buffer.size) {
            val read = channel.readAvailable(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        if (size > maxBytes) {
            log.w { "release metadata exceeds the size limit" }
            throw installFailure(InstallFailureReason.TooLarge)
        }
        return buffer.decodeToString(0, size)
    }

    private companion object {
        const val GITHUB_API_HOST = "api.github.com"
        const val MAX_DOCUMENT_BYTES = 1 shl 20
        const val DOCUMENT_TIMEOUT_MILLIS = 60_000L
        val RepositorySegment = Regex("[A-Za-z0-9_.-]+")
        val FeedJson = Json { ignoreUnknownKeys = true }
    }
}

/**
 * This failure without its message: file system and parser messages name the user's paths, which are never logged.
 * Only the kind of failure is kept.
 */
internal fun Throwable.withoutDetails(): Throwable = IllegalStateException(this::class.simpleName ?: "Failure")

/** A management failure of an installation step. */
internal fun installFailure(reason: InstallFailureReason, cause: Throwable? = null): ManagementException =
    ManagementException(ManagementFailure.Install(reason), cause)

/** Maps a failed request to the installation failure the user sees; management failures pass through. */
internal fun Throwable.asInstallFailure(): ManagementException = when (this) {
    is ManagementException -> this

    is NetworkException.Http -> when (status.value) {
        HTTP_FORBIDDEN, HTTP_TOO_MANY_REQUESTS -> installFailure(InstallFailureReason.RateLimited, this)
        HTTP_NOT_FOUND, HTTP_GONE -> installFailure(InstallFailureReason.NoRelease, this)
        else -> installFailure(InstallFailureReason.Network, this)
    }

    else -> installFailure(InstallFailureReason.Network, this)
}

/** The final response (after redirects) is https on one of [allowedHosts], or the download is untrusted. */
internal fun requireTrusted(response: HttpResponse, allowedHosts: Set<String>) {
    val final = response.call.request.url
    if (final.protocol != URLProtocol.HTTPS || final.host.lowercase() !in allowedHosts) {
        throw installFailure(InstallFailureReason.UntrustedSource)
    }
}

private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private const val HTTP_TOO_MANY_REQUESTS = 429
