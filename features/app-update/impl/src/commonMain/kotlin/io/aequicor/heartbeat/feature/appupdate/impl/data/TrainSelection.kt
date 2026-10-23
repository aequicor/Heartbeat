package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubAsset
import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.common.release.isTrustedReleaseUrl
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure

/** The manifest asset of a published (non-draft) release, if it is a train served from GitHub's download hosts. */
internal fun GitHubRelease.trainManifestAsset(): GitHubAsset? = takeUnless { isDraft }
    ?.assets?.singleOrNull { it.name == TRAIN_MANIFEST_ASSET }
    ?.takeIf { isTrustedReleaseUrl(it.url, GitHubDownloadHosts) }

/**
 * The newest train of [releases] (newest first, as GitHub lists them): its release and manifest asset. Pre-releases
 * count, that is what trains are. Older trains are never looked at: a train always lists every bundle.
 */
internal fun newestTrain(releases: List<GitHubRelease>): Pair<GitHubRelease, GitHubAsset>? =
    releases.firstNotNullOfOrNull { release -> release.trainManifestAsset()?.let { release to it } }

/** The offer of the train [manifest] published as [asset] of [release]. */
internal fun toTrainOffer(release: GitHubRelease, asset: GitHubAsset, manifest: TrainManifest): AppRelease = AppRelease(
    version = manifest.train,
    assetName = asset.name,
    url = asset.url,
    sha256 = asset.sha256.orEmpty(),
    size = null,
    pageUrl = release.pageUrl.takeIf { isTrustedReleaseUrl(it, GitHubDownloadHosts) }.orEmpty(),
    train = manifest.train,
)

/** A jar to download: [bundle] is published as [asset]. */
internal data class TrainDownload(val bundle: TrainBundle, val asset: GitHubAsset)

/**
 * The jars of this train that [isStored] (by SHA-256) does not have yet. A jar is the asset of that name of the first
 * of [releases] that has one on GitHub's download hosts; the train's own release comes first, then earlier ones,
 * because a train carries only the jars that changed. A missing jar makes the train [AppUpdateFailure.InvalidRelease]:
 * it could never be applied completely.
 */
internal fun TrainManifest.missingDownloads(
    releases: List<GitHubRelease>,
    isStored: (String) -> Boolean,
): List<TrainDownload> = bundles.filterNot { isStored(it.sha256) }.distinctBy { it.sha256 }.map { bundle ->
    val asset = releases.firstNotNullOfOrNull { release ->
        release.assets.firstOrNull { it.name == bundle.jar && isTrustedReleaseUrl(it.url, GitHubDownloadHosts) }
    } ?: updateFailure(AppUpdateFailure.InvalidRelease, "train $train has no asset for bundle ${bundle.id}")
    TrainDownload(bundle, asset)
}
