package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.release.GitHubAsset
import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.common.release.sha256FromSums
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppInstallation
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppReleaseSource
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
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
     * On the beta channel of a launch that knows its train: the newest train newer than the installed one (see
     * [newerTrain]). A train that needs a newer platform is skipped, not failed: the platform cannot be updated by a
     * train, so the check falls back to the full-installer release (which carries the new platform), and offers nothing
     * when there is none. A train check that fails falls back the same way: the stable path must keep working.
     * Everything else is the stable policy.
     */
    override suspend fun newer(installation: AppInstallation): AppRelease? {
        val installed = installation.train
        if (installation.channel == UpdateChannel.Beta && installed != null) {
            try {
                newerTrain(installation, installed)?.let { return it }
            } catch (e: AppUpdateException) {
                log.w(e) { "train check failed, checking full releases failure=${e.failure}" }
            }
        }
        return newer(installation.repository, installation.version, installation.platform)
    }

    /**
     * The newest train published after the [installed] one. Age is publication time: [GitHubReleaseClient.recent]
     * sorts the releases by their publish timestamp, newest first, rather than trusting GitHub's undocumented list
     * order, so only trains above the installed train (or above a train already queued for the launcher) are newer:
     * the walk stops there and never offers an older train. This assumes trains are published in the order they are
     * built, as `prerelease.yml` does. Trains the launcher rolled back ([AppInstallation.badTrains]) are skipped. When
     * the installed train is not among the recent releases its age is unknown and no train is offered: the full
     * release path still updates the app. A train id is read from a `train-<id>` tag, otherwise from its manifest; an
     * untagged release whose manifest is invalid is skipped, it could never be offered. The offered train's manifest
     * is always read and must name the same train.
     */
    private suspend fun newerTrain(installation: AppInstallation, installed: String): AppRelease? {
        val manifests = ManifestCache(client)
        val walk = walkTrains(installation, installed, manifests)
        val newest = walk.newest
        return when {
            walk.stop == null -> {
                log.i { "installed train is not among recent releases installed=$installed" }
                null
            }

            newest == null -> {
                log.i { "no train newer than train=${walk.stop} installed=$installed" }
                null
            }

            else -> offer(newest, manifests.of(newest.asset), installed)
        }
    }

    /**
     * Recent trains newest first down to the installed or a queued train, skipping the rolled back ones and untagged
     * releases with an invalid manifest. Any other failure (network, rate limit) ends the check: a train whose id is
     * unknown might be the installed one.
     */
    private suspend fun walkTrains(
        installation: AppInstallation,
        installed: String,
        manifests: ManifestCache,
    ): TrainWalk {
        val stops = installation.pendingTrains + installed
        var newest: TrainCandidate? = null
        for ((release, asset) in trains(client.recent(installation.repository))) {
            val id = trainIdOfTag(release.tag) ?: manifests.trainOrNull(asset) ?: continue
            when {
                id in stops -> return TrainWalk(newest, stop = id)
                id in installation.badTrains -> log.i { "skip rolled back train=$id" }
                newest == null -> newest = TrainCandidate(release, asset, id)
            }
        }
        return TrainWalk(newest, stop = null)
    }

    private fun offer(candidate: TrainCandidate, manifest: TrainManifest, installed: String): AppRelease? = when {
        manifest.train != candidate.id ->
            updateFailure(AppUpdateFailure.InvalidRelease, "train manifest differs from its tag")

        manifest.platformApi > INSTALLED_PLATFORM_API -> {
            log.i { "train needs a newer platform train=${manifest.train} api=${manifest.platformApi}" }
            null
        }

        else -> {
            log.i { "newer train=${manifest.train} installed=$installed" }
            toTrainOffer(candidate.release, candidate.asset, manifest)
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

/** A train of a release whose id is [id]. */
private data class TrainCandidate(val release: GitHubRelease, val asset: GitHubAsset, val id: String)

/** The newest acceptable train above [stop], the installed or queued train; [stop] is null when it was not found. */
private data class TrainWalk(val newest: TrainCandidate?, val stop: String?)

/** Each manifest of one check is read once. */
private class ManifestCache(private val client: GitHubReleaseClient) {
    private val log = Log.tag("AppReleases")
    private val manifests = mutableMapOf<String, TrainManifest>()

    suspend fun of(asset: GitHubAsset): TrainManifest =
        manifests[asset.url] ?: parseTrainManifest(client.document(asset.url)).also { manifests[asset.url] = it }

    /** The train of [asset], or null when its manifest is invalid; other failures propagate. */
    suspend fun trainOrNull(asset: GitHubAsset): String? = try {
        of(asset).train
    } catch (e: AppUpdateException) {
        if (e.failure != AppUpdateFailure.InvalidRelease) throw e
        log.w(e) { "skip a release with an invalid train manifest asset=${asset.name}" }
        null
    }
}
