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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.time.Instant

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
     * channel looks for trains among them. GitHub does not document the order of `GET /releases`, so it is not relied
     * on: the releases are sorted by when they were published (`published_at`, else `created_at`), newest first; a
     * release without a readable timestamp goes last, ties keep the listed order. The sorting applies only within the
     * single page GitHub returns: a release outside that page is not considered at all. Other failures keep their
     * app-specific category.
     */
    suspend fun recent(repository: GitHubRepository): List<GitHubRelease> {
        log.v { "list recent app releases" }
        val url = "https://api.github.com/repos/${repository.owner}/${repository.name}/releases?per_page=$RECENT_COUNT"
        val body = updateRequest { source.document(url, setOf(GITHUB_API_HOST)) }
        val releases = try {
            ReleaseListJson.decodeFromString<JsonArray>(body).map { element ->
                ReleaseListJson.decodeFromJsonElement<GitHubRelease>(element) to publishedAt(element.jsonObject)
            }
        } catch (e: SerializationException) {
            throw AppUpdateException(AppUpdateFailure.InvalidRelease, "release list is not valid JSON", e)
        } catch (e: IllegalArgumentException) {
            throw AppUpdateException(AppUpdateFailure.InvalidRelease, "release list is not valid JSON", e)
        }
        return releases.asSequence()
            .filterNot { (release, _) -> release.isDraft }
            .sortedWith(compareByDescending(nullsFirst()) { (_, published) -> published })
            .map { (release, _) -> release }
            .toList()
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

/** When [release] was published, else created; null when GitHub states neither as an ISO-8601 instant. */
private fun publishedAt(release: JsonObject): Instant? = listOf(PUBLISHED_AT, CREATED_AT).firstNotNullOfOrNull { key ->
    (release[key] as? JsonPrimitive)?.contentOrNull?.let(Instant::parseOrNull)
}

private const val PUBLISHED_AT = "published_at"
private const val CREATED_AT = "created_at"
private const val GITHUB_API_HOST = "api.github.com"
private const val RECENT_COUNT = 30
private val ReleaseListJson = Json { ignoreUnknownKeys = true }
private const val HTTP_NOT_FOUND = 404
private const val HTTP_FORBIDDEN = 403
private const val HTTP_RATE_LIMITED = 429
