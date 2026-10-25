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
 * The trains of [releases] (newest first, as GitHub lists them): each release with its manifest asset. Pre-releases
 * count, that is what trains are.
 */
internal fun trains(releases: List<GitHubRelease>): List<Pair<GitHubRelease, GitHubAsset>> =
    releases.mapNotNull { release -> release.trainManifestAsset()?.let { release to it } }

/** The train id a `train-<id>` tag names (as `prerelease.yml` publishes trains), or null for any other tag. */
internal fun trainIdOfTag(tag: String): String? =
    tag.removePrefix(TRAIN_TAG_PREFIX).takeIf { tag.startsWith(TRAIN_TAG_PREFIX) && isTrainId(it) }

private const val TRAIN_TAG_PREFIX = "train-"

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
 * The jars of this train that [isStored] (by SHA-256) does not have yet. A jar comes from the bundle's own
 * [TrainBundle.url] when it is HTTPS on GitHub's download hosts: it names the release that first published the jar,
 * however old (with the asset's size when that release is among [releases]). Otherwise, or without a url, it is the
 * asset of that name of the first of [releases] that has one on those hosts; the train's own release comes first, then
 * earlier ones, because a train carries only the jars that changed. A missing jar makes the train
 * [AppUpdateFailure.InvalidRelease]: it could never be applied completely. Either way the download must have the
 * manifest's SHA-256.
 */
internal fun TrainManifest.missingDownloads(
    releases: List<GitHubRelease>,
    isStored: (String) -> Boolean,
): List<TrainDownload> = bundles.asSequence().filterNot { isStored(it.sha256) }.distinctBy { it.sha256 }.map { bundle ->
    val asset = bundle.publishedAsset(releases) ?: releases.firstNotNullOfOrNull { release ->
        release.assets.firstOrNull { it.name == bundle.jar && isTrustedReleaseUrl(it.url, GitHubDownloadHosts) }
    } ?: updateFailure(AppUpdateFailure.InvalidRelease, "train $train has no asset for bundle ${bundle.id}")
    TrainDownload(bundle, asset)
}.toList()

/** The asset at this bundle's trusted [TrainBundle.url]: a listed asset there, else one of unknown size; else null. */
private fun TrainBundle.publishedAsset(releases: List<GitHubRelease>): GitHubAsset? {
    val url = url?.takeIf { isTrustedReleaseUrl(it, GitHubDownloadHosts) } ?: return null
    return releases.firstNotNullOfOrNull { release -> release.assets.firstOrNull { it.url == url } }
        ?: GitHubAsset(name = jar, url = url)
}
