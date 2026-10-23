package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.ReleaseSource

/** A source that must not be asked. */
internal object NoReleases : ReleaseSource {
    override suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease =
        throw ReleaseException(ReleaseFailure.Network)

    override suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int): String =
        throw ReleaseException(ReleaseFailure.Network)
}
