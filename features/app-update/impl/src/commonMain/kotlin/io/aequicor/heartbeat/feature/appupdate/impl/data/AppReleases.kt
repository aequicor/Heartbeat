package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppReleaseSource
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure

/**
 * The newest release of the app newer than the installed one, with the build for this OS and architecture and the
 * SHA-256 its download must have: GitHub's digest of the asset, else the release's `SHA256SUMS`. A newer release
 * without such a build or checksum is [AppUpdateFailure.InvalidRelease]: it is never offered unverified.
 */
internal class AppReleases(private val client: GitHubReleaseClient) : AppReleaseSource {
    private val log = Log.tag("AppUpdateReleases")

    override suspend fun newer(
        repository: GitHubRepository,
        installed: AppVersion,
        platform: ReleasePlatform,
    ): AppRelease? {
        val latest = client.latest(repository) ?: return null
        val version = latest.versionNewerThan(installed)
        if (version == null) {
            log.i { "no newer release installed=$installed latest=${latest.tag}" }
            return null
        }
        val build = latest.buildFor(version, platform)
        if (build == null) {
            log.w { "release $version has no build platform=$platform" }
            updateFailure(AppUpdateFailure.InvalidRelease)
        }
        val sha256 = build.sha256
            ?: latest.checksums()?.let { sums -> sha256FromSums(client.document(sums.url), build.name) }
        if (sha256 == null) {
            log.w { "release $version states no checksum asset=${build.name}" }
            updateFailure(AppUpdateFailure.InvalidRelease)
        }
        log.i { "newer release version=$version asset=${build.name} installed=$installed" }
        return latest.toAppRelease(version, build, sha256)
    }
}
