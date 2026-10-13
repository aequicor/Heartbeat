package io.aequicor.heartbeat.core.network.release

import io.aequicor.heartbeat.core.common.release.GitHubRelease

/** Anonymous, bounded release metadata requests. Failures are [ReleaseException]; cancellation propagates. */
public interface ReleaseSource {
    /** Reads GitHub's latest endpoint, preserving draft/prerelease flags for the caller's policy. */
    public suspend fun latestGitHubRelease(owner: String, repository: String): GitHubRelease

    /** Reads at most [maxBytes] from HTTPS [url]; its final host must also belong to [allowedHosts]. */
    public suspend fun document(url: String, allowedHosts: Set<String>, maxBytes: Int = MAX_DOCUMENT_BYTES): String
}

/** Safe failure categories shared by release consumers; feature boundaries retain their own user-facing errors. */
public enum class ReleaseFailure {
    /** HTTP error; [ReleaseException.status] preserves 404 versus 410 and rate limits. */
    Http,

    /** Connection, timeout or invalid transport response. */
    Network,

    /** JSON could not be decoded. */
    InvalidMetadata,

    /** Initial or final URL lies outside the caller's HTTPS allowlist. */
    UntrustedSource,

    /** Metadata or file exceeds its byte limit. */
    TooLarge,

    /** Published, announced and received sizes disagree. */
    SizeMismatch,

    /** Downloaded SHA-256 differs from the expected digest. */
    ChecksumMismatch,

    /** Insufficient room for the download and the caller's working copies. */
    NotEnoughSpace,

    /** A local file could not be created or written. */
    Storage,
}

/** Contains no URL, body, path or raw cause; safe to log once at the consumer's failure boundary. */
public class ReleaseException(
    public val failure: ReleaseFailure,
    public val status: Int? = null,
    origin: String? = null,
) : Exception(listOfNotNull(failure.name, status, origin).joinToString(": "))

private const val MAX_DOCUMENT_BYTES = 1 shl 20
