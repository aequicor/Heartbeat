package io.aequicor.heartbeat.core.common.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A GitHub release as `GET /repos/{owner}/{repo}/releases/latest` returns it. */
@Serializable
public data class GitHubRelease(
    @SerialName("tag_name") val tag: String,
    @SerialName("draft") val isDraft: Boolean = false,
    @SerialName("prerelease") val isPrerelease: Boolean = false,
    val assets: List<GitHubAsset> = emptyList(),
    @SerialName("html_url") val pageUrl: String = "",
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

/**
 * Host of an https [url], lowercased; empty when there is none. An authority with user info, a backslash, an escape or
 * anything but a plain host name and port has no host, because browsers and clients disagree about where it points.
 */
public fun hostOf(url: String): String {
    val authority = url.substringAfter("://", "").takeWhile { it != '/' && it != '?' && it != '#' }
    return Authority.matchEntire(authority)?.groupValues?.get(1)?.lowercase().orEmpty()
}

/** Whether [url] is HTTPS on [allowedHosts]. Check both the initial URL and the final response URL. */
public fun isTrustedReleaseUrl(url: String, allowedHosts: Set<String>): Boolean =
    url.startsWith("https://") && hostOf(url) in allowedHosts

private const val SHA256_PREFIX = "sha256:"
private val Sha256 = Regex("[0-9a-f]{64}")
private val Authority = Regex("([A-Za-z0-9.-]+)(?::[0-9]{1,5})?")
private val Whitespace = Regex("\\s+")
