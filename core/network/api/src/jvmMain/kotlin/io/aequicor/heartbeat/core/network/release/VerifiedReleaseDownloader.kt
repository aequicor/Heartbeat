package io.aequicor.heartbeat.core.network.release

import java.nio.file.Path

/** Streams and verifies release files. IO runs on the injected dispatcher; progress runs on the caller's context. */
public interface VerifiedReleaseDownloader {
    /**
     * Creates [target] exclusively, checks HTTPS before and after redirects, size and SHA-256 while streaming.
     * Reserves [ReleaseDownload.spaceFactor] copies plus 64 MiB. Failure/cancellation deletes only the file created
     * by this call, never a pre-existing target. Failures are safe [ReleaseException]s, logged by the consumer.
     */
    public suspend fun download(request: ReleaseDownload, target: Path, progress: suspend (Long, Long?) -> Unit)
}

/** File identity and limits; callers choose their publisher hosts and required number of coexisting copies. */
public data class ReleaseDownload(
    val url: String,
    val sha256: String,
    val size: Long?,
    val allowedHosts: Set<String>,
    val spaceFactor: Int = 2,
    val maxBytes: Long = MAX_DOWNLOAD_BYTES,
) {
    init {
        require(spaceFactor > 0 && maxBytes > 0 && maxBytes <= Long.MAX_VALUE / spaceFactor - SPACE_MARGIN) {
            "Invalid download limits"
        }
    }
}

private const val SPACE_MARGIN = 64L * 1024 * 1024

private const val MAX_DOWNLOAD_BYTES = 1L shl 30
