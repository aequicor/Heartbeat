package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubAsset
import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.common.release.isTrustedReleaseUrl
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.GitHubRepository
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
 * The jars of this train that [isStored] (by SHA-256) does not have yet, each from the first of, in this order:
 * - the asset of [releases] at the bundle's own [TrainBundle.url], when that url is a release download of [repository]
 *   ([isRepositoryDownload]): it names the release that first published the jar;
 * - the asset named [TrainBundle.jar] of the first of [releases] that has one on GitHub's download hosts; the train's
 *   own release comes first, then earlier ones, because a train carries only the jars that changed;
 * - the bundle's url alone, of unknown size: its release is older than [releases], so nothing else can serve the jar.
 *
 * A listed asset thus wins over a url nothing lists, which may no longer exist. A url of another repository or host is
 * ignored. A missing jar makes the train [AppUpdateFailure.InvalidRelease]: it could never be applied completely.
 * Either way the download must have the manifest's SHA-256.
 */
internal fun TrainManifest.missingDownloads(
    repository: GitHubRepository,
    releases: List<GitHubRelease>,
    isStored: (String) -> Boolean,
): List<TrainDownload> = bundles.asSequence().filterNot { isStored(it.sha256) }.distinctBy { it.sha256 }.map { bundle ->
    val url = bundle.url?.takeIf { isRepositoryDownload(it, repository) }
    val asset = url?.let { releases.assetAt(it) }
        ?: releases.firstNotNullOfOrNull { release ->
            release.assets.firstOrNull { it.name == bundle.jar && isTrustedReleaseUrl(it.url, GitHubDownloadHosts) }
        }
        ?: url?.let { GitHubAsset(name = bundle.jar, url = it) }
        ?: updateFailure(AppUpdateFailure.InvalidRelease, "train $train has no asset for bundle ${bundle.id}")
    TrainDownload(bundle, asset)
}.toList()

private fun List<GitHubRelease>.assetAt(url: String): GitHubAsset? =
    firstNotNullOfOrNull { release -> release.assets.firstOrNull { it.url == url } }

/**
 * Whether [url] is an HTTPS release download of [repository]:
 * `https://github.com/<owner>/<name>/releases/download/<tag>/<file>` with plain segments (no `..`, escapes or query).
 * Owner and name compare without case, as GitHub resolves them.
 */
internal fun isRepositoryDownload(url: String, repository: GitHubRepository): Boolean {
    val prefix = "https://github.com/${repository.owner}/${repository.name}/releases/download/"
    if (!url.startsWith(prefix, ignoreCase = true) || !isTrustedReleaseUrl(url, GitHubDownloadHosts)) return false
    val segments = url.substring(prefix.length).split('/')
    return segments.size == 2 && segments.all { DownloadSegment.matches(it) && it != "." && it != ".." }
}

private val DownloadSegment = Regex("[A-Za-z0-9_.+-]+")
