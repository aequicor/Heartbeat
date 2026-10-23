package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.release.sha256FromSums
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppReleaseSource
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform
import io.aequicor.heartbeat.feature.appupdate.impl.domain.UpdateChannel
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure

/**
 * The newest release of the app newer than the installed one, with the build for this OS and architecture and the
 * SHA-256 its download must have: GitHub's digest of the asset, else the release's `SHA256SUMS`. A newer release
 * without such a build or checksum is [AppUpdateFailure.InvalidRelease]: it is never offered unverified.
 */
@Inject
internal class AppReleases(private val client: GitHubReleaseClient) : AppReleaseSource {
    private val log = Log.tag("AppReleases")

    /**
     * On the beta channel of a launch that knows its train: the newest train when it is not the installed one and the
     * installed platform API suffices. A train that needs a newer platform is skipped, not failed: the platform
     * cannot be updated by a train, so the check falls back to the full-installer release (which carries the new
     * platform), and offers nothing when there is none. Everything else is the stable policy.
     */
    override suspend fun newer(installation: AppInstallation): AppRelease? {
        if (installation.channel == UpdateChannel.Beta && installation.train != null) {
            newerTrain(installation.repository, installation.train)?.let { return it }
        }
        return newer(installation.repository, installation.version, installation.platform)
    }

    private suspend fun newerTrain(repository: GitHubRepository, installed: String): AppRelease? {
        val (release, asset) = newestTrain(client.recent(repository)) ?: return null
        val manifest = parseTrainManifest(client.document(asset.url))
        return when {
            manifest.train == installed -> {
                log.i { "installed train is the newest train=$installed" }
                null
            }

            manifest.platformApi > INSTALLED_PLATFORM_API -> {
                log.i { "train needs a newer platform train=${manifest.train} api=${manifest.platformApi}" }
                null
            }

            else -> {
                log.i { "newer train=${manifest.train} installed=$installed" }
                toTrainOffer(release, asset, manifest)
            }
        }
    }

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
            ?: updateFailure(AppUpdateFailure.InvalidRelease, "release $version has no build platform=$platform")
        val sha256 = build.sha256
            ?: latest.checksums()?.let { sums -> sha256FromSums(client.document(sums.url), build.name) }
            ?: updateFailure(AppUpdateFailure.InvalidRelease, "release $version states no checksum asset=${build.name}")
        log.i { "newer release version=$version asset=${build.name} installed=$installed" }
        return latest.toAppRelease(version, build, sha256)
    }
}
