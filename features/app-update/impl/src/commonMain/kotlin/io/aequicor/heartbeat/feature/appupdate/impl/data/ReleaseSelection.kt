package io.aequicor.heartbeat.feature.appupdate.impl.data

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
    assets.singleOrNull { it.name == platform.assetName(version) }?.takeIf { isTrustedUrl(it.url, GitHubDownloadHosts) }

/** The release's checksum list, if it has one on GitHub. */
internal fun GitHubRelease.checksums(): GitHubAsset? =
    assets.singleOrNull { it.name == CHECKSUMS_ASSET }?.takeIf { isTrustedUrl(it.url, GitHubDownloadHosts) }

/** The SHA-256 GitHub states for this asset (`digest`), or null when it states none. */
internal val GitHubAsset.sha256: String?
    get() = digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase()
        ?.takeIf { Sha256.matches(it) }

/**
 * The SHA-256 of [asset] in a `sha256sum` list (`<hex>  <name>` or `<hex> *<name>` per line), or null when the list
 * names it not exactly once.
 */
internal fun sha256FromSums(sums: String, asset: String): String? = sums.lineSequence().mapNotNull { line ->
    val parts = line.trim().split(Whitespace, limit = 2)
    if (parts.size != 2) return@mapNotNull null
    val name = parts[1].removePrefix("*").removePrefix("./")
    parts[0].lowercase().takeIf { name == asset && Sha256.matches(it) }
}.toList().singleOrNull()

/** The installable release of [build] with its verified-on-download [sha256]. */
internal fun GitHubRelease.toAppRelease(version: AppVersion, build: GitHubAsset, sha256: String): AppRelease =
    AppRelease(
        version = version.toString(),
        assetName = build.name,
        url = build.url,
        sha256 = sha256,
        size = build.size.takeIf { it > 0 },
        pageUrl = pageUrl.takeIf { isTrustedUrl(it, GitHubDownloadHosts) }.orEmpty(),
    )

private const val SHA256_PREFIX = "sha256:"
private val Sha256 = Regex("[0-9a-f]{64}")
private val Whitespace = Regex("\\s+")
