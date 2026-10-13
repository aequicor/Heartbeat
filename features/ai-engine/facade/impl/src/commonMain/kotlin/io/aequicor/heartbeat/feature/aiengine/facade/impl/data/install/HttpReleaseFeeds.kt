package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementException
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementFailure
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubRelease
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ReleaseFeeds

/** Engine policy over shared release transport; the management boundary logs failures once. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class HttpReleaseFeeds(private val source: ReleaseSource) : ReleaseFeeds {
    override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease = installRequest {
        source.latestGitHubRelease(owner, repository).also {
            if (it.isDraft || it.isPrerelease) throw installFailure(InstallFailureReason.NoRelease)
        }
    }

    override suspend fun document(url: String, allowedHosts: Set<String>): String =
        installRequest { source.document(url, allowedHosts) }
}

/**
 * This failure without its message: file system and parser messages name the user's paths, which are never logged.
 * Only the kind of failure is kept.
 */
internal fun Throwable.withoutDetails(): Throwable = IllegalStateException(this::class.simpleName ?: "Failure")

/** A management failure of an installation step. */
internal fun installFailure(reason: InstallFailureReason, cause: Throwable? = null): ManagementException =
    ManagementException(ManagementFailure.Install(reason), cause)

/** Maps safe shared failures at the facade boundary without logging twice. Cancellation passes through. */
internal suspend fun <T> installRequest(block: suspend () -> T): T = try {
    block()
} catch (e: ReleaseException) {
    throw installFailure(e.installReason(), e)
}

private fun ReleaseException.installReason(): InstallFailureReason = when (failure) {
    ReleaseFailure.Http -> when (status) {
        HTTP_FORBIDDEN, HTTP_RATE_LIMITED -> InstallFailureReason.RateLimited
        HTTP_NOT_FOUND, HTTP_GONE -> InstallFailureReason.NoRelease
        else -> InstallFailureReason.Network
    }

    ReleaseFailure.Network -> InstallFailureReason.Network

    ReleaseFailure.InvalidMetadata -> InstallFailureReason.NoRelease

    ReleaseFailure.UntrustedSource -> InstallFailureReason.UntrustedSource

    ReleaseFailure.TooLarge -> InstallFailureReason.TooLarge

    ReleaseFailure.SizeMismatch -> InstallFailureReason.SizeMismatch

    ReleaseFailure.ChecksumMismatch -> InstallFailureReason.ChecksumMismatch

    ReleaseFailure.NotEnoughSpace -> InstallFailureReason.NotEnoughSpace

    ReleaseFailure.Storage -> InstallFailureReason.Storage
}

private const val HTTP_FORBIDDEN = 403
private const val HTTP_RATE_LIMITED = 429
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
