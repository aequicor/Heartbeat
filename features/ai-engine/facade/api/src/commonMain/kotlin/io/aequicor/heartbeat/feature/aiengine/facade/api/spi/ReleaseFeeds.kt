package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

/**
 * Release metadata an adapter reads to resolve its newest release. The facade fetches it over the app's HTTP client,
 * https only and from the hosts named, with a small size limit; failures are [ManagementException]s
 * (`Network`, `RateLimited`, `NoRelease`, `UntrustedSource`).
 */
public interface ReleaseFeeds {
    /** The latest published, non-draft, non-pre-release release of `github.com/[owner]/[repository]`. */
    public suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease

    /** A small text document (a version, a manifest, a checksum list) at the https [url] on one of [allowedHosts]. */
    public suspend fun document(url: String, allowedHosts: Set<String>): String
}

/**
 * One release build to install: the [url] of a file on [allowedHosts] whose SHA-256 the publisher states as
 * [sha256], unpacked as [archive]; [executable] is the program's path inside the unpacked copy.
 */
public data class InstallPlan(
    val version: String,
    val url: String,
    val sha256: String,
    val size: Long?,
    val archive: ArchiveKind,
    val executable: String,
    val allowedHosts: Set<String>,
    /** Keep this immutable copy for adapters restored by a bundle rollback, until explicit uninstall. */
    val isRetainedForRollback: Boolean = false,
) {
    init {
        require(version.isNotBlank() && version.none { it == '/' || it == '\\' }) { "Invalid release version" }
        require(isTrustedReleaseUrl(url, allowedHosts)) { "Releases are downloaded over https from allowed hosts" }
        require(Sha256.matches(sha256)) { "SHA-256 must be 64 lowercase hex characters" }
        require(size == null || size > 0) { "Invalid release size" }
        require(isSafeRelativePath(executable)) { "Executable must be a relative path inside the release" }
    }
}

/** How a release file unpacks. */
public sealed interface ArchiveKind {
    /** A gzip-compressed tar archive; the first [stripComponents] path segments are dropped. */
    public data class TarGz(val stripComponents: Int = 0) : ArchiveKind

    /** A zip archive; the first [stripComponents] path segments are dropped. */
    public data class Zip(val stripComponents: Int = 0) : ArchiveKind

    /** The file itself is the program, saved as [fileName]. */
    public data class Raw(val fileName: String) : ArchiveKind {
        init {
            require(isSafeRelativePath(fileName) && '/' !in fileName) { "Invalid file name" }
        }
    }
}

/** Shared release metadata, kept here as an SPI source-compatibility alias. */
public typealias GitHubRelease = io.aequicor.heartbeat.core.common.release.GitHubRelease

/** Shared release asset, kept here as an SPI source-compatibility alias. */
public typealias GitHubAsset = io.aequicor.heartbeat.core.common.release.GitHubAsset

/** Hosts GitHub serves release downloads from. */
public val GitHubDownloadHosts: Set<String> = io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts

/** SHA-256 for the uniquely named asset in a checksum list. */
public fun sha256FromSums(sums: String, asset: String): String? =
    io.aequicor.heartbeat.core.common.release.sha256FromSums(sums, asset)

/** Conservative host parsing shared with the release transport. */
public fun hostOf(url: String): String = io.aequicor.heartbeat.core.common.release.hostOf(url)

/** Whether [url] is HTTPS on [allowedHosts]. */
public fun isTrustedReleaseUrl(url: String, allowedHosts: Set<String>): Boolean =
    io.aequicor.heartbeat.core.common.release.isTrustedReleaseUrl(url, allowedHosts)

private fun isSafeRelativePath(path: String): Boolean =
    path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
        path.none { it == '\u0000' || it == '\n' } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

private val Sha256 = Regex("[0-9a-f]{64}")
