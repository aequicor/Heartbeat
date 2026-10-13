package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.NetworkException
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.URLProtocol
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Reads the app's GitHub releases over the app's HTTP client, on the IO dispatcher: https only, the final URL after
 * redirects stays on GitHub, and a document is at most 1 MiB. Requests are anonymous, so GitHub's rate limit for
 * one address applies; it surfaces as [AppUpdateFailure.RateLimited]. The transport logs every request; failures
 * travel as [AppUpdateException] to the effect handler, which logs them.
 */
@Inject
internal class GitHubReleaseClient(private val client: HttpClient, dispatchers: DispatcherProvider) {
    private val io = dispatchers.io

    private val log = Log.tag("GitHubReleaseClient")

    /** The latest published release of [repository], or null while it has none. */
    suspend fun latest(repository: GitHubRepository): GitHubRelease? = withContext(io) {
        val response = request("https://$GITHUB_API_HOST/repos/$repository/releases/latest", setOf(GITHUB_API_HOST))
        val error = response.exceptionOrNull()
        // A repository without a published release answers 404: no update rather than a failure.
        if ((error as? NetworkException.Http)?.status?.value == HTTP_NOT_FOUND) {
            log.i { "no published release repository=$repository" }
            return@withContext null
        }
        if (error != null) throw error.asUpdateFailure()
        val release = try {
            ReleaseJson.decodeFromString<GitHubRelease>(response.getOrThrow())
        } catch (e: SerializationException) {
            throw AppUpdateException(AppUpdateFailure.InvalidRelease, "latest release metadata is malformed", e)
        } catch (e: IllegalArgumentException) {
            throw AppUpdateException(AppUpdateFailure.InvalidRelease, "latest release metadata is malformed", e)
        }
        release.takeUnless { it.isDraft || it.isPrerelease }
    }

    /** A small text file of a release, such as its `SHA256SUMS`, at the https [url] on GitHub's download hosts. */
    suspend fun document(url: String): String = withContext(io) {
        log.d { "release document host=${hostOf(url)}" }
        val response = request(url, GitHubDownloadHosts)
        response.exceptionOrNull()?.let { error -> throw error.asUpdateFailure() }
        response.getOrThrow()
    }

    private suspend fun request(url: String, allowedHosts: Set<String>): Result<String> {
        if (!isTrustedUrl(url, allowedHosts)) {
            updateFailure(AppUpdateFailure.InvalidRelease, "release metadata outside GitHub host=${hostOf(url)}")
        }
        return networkResult {
            client.prepareGet(url) {
                header(HttpHeaders.Accept, "application/vnd.github+json, application/json, text/plain")
                timeout { requestTimeoutMillis = DOCUMENT_TIMEOUT_MILLIS }
            }.execute { response -> read(response, allowedHosts) }
        }
    }

    private suspend fun read(response: HttpResponse, allowedHosts: Set<String>): String {
        if (!response.isTrusted(allowedHosts)) {
            updateFailure(
                AppUpdateFailure.InvalidRelease,
                "release metadata redirected outside GitHub host=${response.call.request.url.host}",
            )
        }
        val channel = response.bodyAsChannel()
        val buffer = ByteArray(MAX_DOCUMENT_BYTES + 1)
        var size = 0
        while (size < buffer.size) {
            val read = channel.readAvailable(buffer, size, buffer.size - size)
            if (read < 0) break
            size += read
        }
        if (size > MAX_DOCUMENT_BYTES) {
            updateFailure(AppUpdateFailure.InvalidRelease, "release metadata exceeds the size limit")
        }
        return buffer.decodeToString(0, size)
    }

    private companion object {
        const val GITHUB_API_HOST = "api.github.com"
        const val MAX_DOCUMENT_BYTES = 1 shl 20
        const val DOCUMENT_TIMEOUT_MILLIS = 60_000L
        const val HTTP_NOT_FOUND = 404
        val ReleaseJson = Json { ignoreUnknownKeys = true }
    }
}

/** A GitHub release as `GET /repos/{owner}/{repo}/releases/latest` returns it. */
@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val pageUrl: String = "",
    @SerialName("draft") val isDraft: Boolean = false,
    @SerialName("prerelease") val isPrerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
)

/** A file of a GitHub release; [digest] is `sha256:<hex>` for assets uploaded since mid 2025. */
@Serializable
internal data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
    val digest: String? = null,
)

/** Hosts GitHub serves release pages and downloads from, including the redirects of a download. */
internal val GitHubDownloadHosts: Set<String> =
    setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

/**
 * Host of an https [url], lowercased; empty when there is none. An authority with user info, a backslash, an escape or
 * anything but a plain host name and port has no host, because clients disagree about where it points.
 */
internal fun hostOf(url: String): String {
    val authority = url.substringAfter("://", "").takeWhile { it != '/' && it != '?' && it != '#' }
    return Authority.matchEntire(authority)?.groupValues?.get(1)?.lowercase().orEmpty()
}

/** Whether [url] may be requested: https on one of [allowedHosts]. */
internal fun isTrustedUrl(url: String, allowedHosts: Set<String>): Boolean =
    url.startsWith("https://") && hostOf(url) in allowedHosts

/** Whether the final response (after redirects) came over https from one of [allowedHosts]. */
internal fun HttpResponse.isTrusted(allowedHosts: Set<String>): Boolean {
    val final = call.request.url
    return final.protocol == URLProtocol.HTTPS && final.host.lowercase() in allowedHosts
}

/** The failure the user sees for a failed request; update failures pass through. */
internal fun Throwable.asUpdateFailure(): AppUpdateException = when (this) {
    is AppUpdateException -> this

    is NetworkException.Http -> when (status.value) {
        HTTP_FORBIDDEN, HTTP_TOO_MANY_REQUESTS -> AppUpdateException(AppUpdateFailure.RateLimited, cause = this)
        else -> AppUpdateException(AppUpdateFailure.Network, "status=${status.value}", this)
    }

    else -> AppUpdateException(AppUpdateFailure.Network, cause = this)
}

private val Authority = Regex("([A-Za-z0-9.-]+)(?::[0-9]{1,5})?")
private const val HTTP_FORBIDDEN = 403
private const val HTTP_TOO_MANY_REQUESTS = 429
