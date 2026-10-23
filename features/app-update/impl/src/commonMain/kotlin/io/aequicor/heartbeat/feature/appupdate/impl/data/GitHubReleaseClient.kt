package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** App-update policy over shared anonymous transport. The effect handler logs failures once. */
@Inject
internal class GitHubReleaseClient(private val source: ReleaseSource) {
    private val log = Log.tag("GitHubReleaseClient")

    /** 404, drafts and prereleases mean no update; other failures retain their app-specific category. */
    suspend fun latest(repository: GitHubRepository): GitHubRelease? {
        log.v { "apply app release publication policy" }
        val response = latestResult(repository)
        val error = response.exceptionOrNull()
        if (error is ReleaseException && error.failure == ReleaseFailure.Http && error.status == HTTP_NOT_FOUND) {
            return null
        }
        if (error is ReleaseException) throw error.asUpdateFailure()
        return response.getOrThrow().takeUnless { it.isDraft || it.isPrerelease }
    }

    private suspend fun latestResult(repository: GitHubRepository): Result<GitHubRelease> = try {
        Result.success(source.latestGitHubRelease(repository.owner, repository.name))
    } catch (e: ReleaseException) {
        Result.failure(e)
    }

    /**
     * The newest published releases of [repository], newest first, drafts excluded and pre-releases kept: the beta
     * channel looks for trains among them. Other failures keep their app-specific category.
     */
    suspend fun recent(repository: GitHubRepository): List<GitHubRelease> {
        log.v { "list recent app releases" }
        val url = "https://api.github.com/repos/${repository.owner}/${repository.name}/releases?per_page=$RECENT_COUNT"
        val body = updateRequest { source.document(url, setOf(GITHUB_API_HOST)) }
        val releases = try {
            ReleaseListJson.decodeFromString<List<GitHubRelease>>(body)
        } catch (e: SerializationException) {
            throw AppUpdateException(AppUpdateFailure.InvalidRelease, "release list is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw AppUpdateException(AppUpdateFailure.InvalidRelease, "release list is not valid JSON", e)
        }
        return releases.filterNot { it.isDraft }
    }

    /** A bounded text asset on GitHub's download hosts. */
    suspend fun document(url: String): String {
        log.v { "read app release checksums" }
        return updateRequest { source.document(url, GitHubDownloadHosts) }
    }
}

/** Maps shared errors at the app-update boundary. Cancellation propagates unchanged. */
internal suspend fun <T> updateRequest(block: suspend () -> T): T = try {
    block()
} catch (e: ReleaseException) {
    throw e.asUpdateFailure()
}

private fun ReleaseException.asUpdateFailure(): AppUpdateException = AppUpdateException(
    when (failure) {
        ReleaseFailure.Http -> if (status == HTTP_FORBIDDEN || status == HTTP_RATE_LIMITED) {
            AppUpdateFailure.RateLimited
        } else {
            AppUpdateFailure.Network
        }

        ReleaseFailure.Network -> AppUpdateFailure.Network

        ReleaseFailure.InvalidMetadata, ReleaseFailure.UntrustedSource, ReleaseFailure.TooLarge ->
            AppUpdateFailure.InvalidRelease

        ReleaseFailure.SizeMismatch, ReleaseFailure.ChecksumMismatch -> AppUpdateFailure.Corrupted

        ReleaseFailure.NotEnoughSpace -> AppUpdateFailure.NotEnoughSpace

        ReleaseFailure.Storage -> AppUpdateFailure.Storage
    },
    cause = this,
)

private const val GITHUB_API_HOST = "api.github.com"
private const val RECENT_COUNT = 30
private val ReleaseListJson = Json { ignoreUnknownKeys = true }
private const val HTTP_NOT_FOUND = 404
private const val HTTP_FORBIDDEN = 403
private const val HTTP_RATE_LIMITED = 429
