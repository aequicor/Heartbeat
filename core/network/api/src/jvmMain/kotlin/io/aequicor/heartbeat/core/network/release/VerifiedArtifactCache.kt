package io.aequicor.heartbeat.core.network.release

import java.nio.file.Path

/** Immutable downloaded component, addressed by its pinned SHA-256 and a portable file name. */
public data class ReleaseArtifact(val fileName: String, val download: ReleaseDownload) {
    init {
        require(fileName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid artifact file name" }
        require(download.sha256.matches(Regex("[a-f0-9]{64}"))) { "Invalid artifact digest" }
    }
}

/** Process-wide cache of pinned optional components. Features own preparation lifetime, progress and retry UI. */
public interface VerifiedArtifactCache {
    /**
     * Verifies an existing file on every access, or downloads and atomically publishes a new verified file.
     * Calls for the same artifact are serialized across coroutines and processes. Cancellation while waiting or
     * downloading propagates and removes only this attempt's temporary file; completed files remain reusable offline.
     * The result is a regular file with the exact pinned SHA-256. Failures are safe [ReleaseException]s.
     */
    public suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit = { _, _ -> }): Path
}
