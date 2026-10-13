package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubAsset
import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.common.release.GitHubRelease
import io.aequicor.heartbeat.core.common.release.isTrustedReleaseUrl
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppVersion
import io.aequicor.heartbeat.feature.appupdate.impl.domain.ReleasePlatform

/** Name of the checksum list the release workflow attaches to every release. */
internal const val CHECKSUMS_ASSET = "SHA256SUMS"

/** The version of this release when it is an app release newer than [installed]; other tags are never newer. */
internal fun GitHubRelease.versionNewerThan(installed: AppVersion): AppVersion? =
    AppVersion.fromTag(tag)?.takeIf { it > installed }

/** The asset of [version] for [platform], downloadable from GitHub; null when the release has none. */
internal fun GitHubRelease.buildFor(version: AppVersion, platform: ReleasePlatform): GitHubAsset? =
    assets.singleOrNull {
        it.name == platform.assetName(
            version,
        )
    }?.takeIf { isTrustedReleaseUrl(it.url, GitHubDownloadHosts) }

/** The release's checksum list, if it has one on GitHub. */
internal fun GitHubRelease.checksums(): GitHubAsset? =
    assets.singleOrNull { it.name == CHECKSUMS_ASSET }?.takeIf { isTrustedReleaseUrl(it.url, GitHubDownloadHosts) }

/** The installable release of [build] with its verified-on-download [sha256]. */
internal fun GitHubRelease.toAppRelease(version: AppVersion, build: GitHubAsset, sha256: String): AppRelease =
    AppRelease(
        version = version.toString(),
        assetName = build.name,
        url = build.url,
        sha256 = sha256,
        size = build.size.takeIf { it > 0 },
        pageUrl = pageUrl.takeIf { isTrustedReleaseUrl(it, GitHubDownloadHosts) }.orEmpty(),
    )
