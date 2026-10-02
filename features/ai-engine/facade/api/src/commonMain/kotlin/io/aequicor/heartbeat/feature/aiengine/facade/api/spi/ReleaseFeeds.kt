package io.aequicor.heartbeat.feature.aiengine.facade.api.spi

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
) {
    init {
        require(version.isNotBlank() && version.none { it == '/' || it == '\\' }) { "Invalid release version" }
        require(url.startsWith("https://")) { "Releases are downloaded over https only" }
        require(hostOf(url) in allowedHosts) { "Release URL outside the allowed hosts" }
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

/** A GitHub release as `GET /repos/{owner}/{repo}/releases/latest` returns it. */
@Serializable
public data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
) {
    /** The asset named [name], or null. */
    public fun asset(name: String): GitHubAsset? = assets.firstOrNull { it.name == name }
}

/** A file of a GitHub release; [digest] is `sha256:<hex>` for assets uploaded since mid 2025. */
@Serializable
public data class GitHubAsset(
    val name: String,
    val size: Long = 0,
    @SerialName("browser_download_url") val url: String,
    val digest: String? = null,
) {
    /** The SHA-256 GitHub states for the file, or null when it states none. */
    public val sha256: String?
        get() = digest?.takeIf { it.startsWith(SHA256_PREFIX) }?.removePrefix(SHA256_PREFIX)?.lowercase()
            ?.takeIf { Sha256.matches(it) }
}

/** Hosts GitHub serves release downloads from, including its redirects. */
public val GitHubDownloadHosts: Set<String> =
    setOf("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")

/**
 * The SHA-256 of [asset] in a `sha256sum` list (`<hex>  <name>` or `<hex> *<name>` per line), or null when the list
 * names it not exactly once.
 */
public fun sha256FromSums(sums: String, asset: String): String? {
    val matches = sums.lineSequence().mapNotNull { line ->
        val parts = line.trim().split(Whitespace, limit = 2)
        if (parts.size != 2) return@mapNotNull null
        val name = parts[1].removePrefix("*").removePrefix("./")
        parts[0].lowercase().takeIf { name == asset && Sha256.matches(it) }
    }.toList()
    return matches.singleOrNull()
}

/** Host of an https [url], lowercased; empty when there is none. */
public fun hostOf(url: String): String =
    url.substringAfter("://", "").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase()

private fun isSafeRelativePath(path: String): Boolean =
    path.isNotBlank() && !path.startsWith('/') && '\\' !in path && ':' !in path &&
        path.none { it == '\u0000' || it == '\n' } && path.split('/').none { it.isEmpty() || it == "." || it == ".." }

private const val SHA256_PREFIX = "sha256:"
private val Sha256 = Regex("[0-9a-f]{64}")
private val Whitespace = Regex("\\s+")
