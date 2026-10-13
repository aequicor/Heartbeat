package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.AppUpdateException
import io.aequicor.heartbeat.feature.appupdate.impl.domain.updateFailure
import io.aequicor.heartbeat.feature.appupdate.impl.domain.withoutDetails
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Streams a release file to disk and proves it: the final URL after redirects is https on GitHub, the size never
 * exceeds the published size or [maxBytes], free space covers the file and its unpacked copy, and the SHA-256
 * computed while streaming matches the published one. A failed or cancelled download leaves no file behind.
 */
internal class UpdateDownloader(
    private val client: HttpClient,
    private val io: CoroutineDispatcher,
    private val maxBytes: Long = MAX_DOWNLOAD_BYTES,
) {
    private val log = Log.tag("AppUpdateDownload")

    /** Downloads [release] into the new file [target], reporting the bytes received and the expected total. */
    suspend fun download(release: AppRelease, target: Path, progress: suspend (bytes: Long, total: Long?) -> Unit) {
        log.i { "download release version=${release.version} asset=${release.assetName}" }
        if (!isTrustedUrl(release.url, GitHubDownloadHosts)) {
            log.w { "release download outside GitHub host=${hostOf(release.url)}" }
            updateFailure(AppUpdateFailure.InvalidRelease)
        }
        var isComplete = false
        try {
            networkResult {
                client.prepareGet(release.url) {
                    timeout {
                        requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                    }
                }.execute { response -> receive(release, response, target, progress) }
            }.getOrElse { error ->
                log.w(error) { "release download failed version=${release.version}" }
                throw error.asUpdateFailure()
            }
            isComplete = true
            log.i { "release downloaded version=${release.version} bytes=${Files.size(target)}" }
        } finally {
            // A single unlink: quick enough to run on any thread, and it must run even when cancelled.
            if (!isComplete) deleteQuietly(target)
        }
    }

    private suspend fun receive(
        release: AppRelease,
        response: HttpResponse,
        target: Path,
        progress: suspend (Long, Long?) -> Unit,
    ) {
        if (!response.isTrusted(GitHubDownloadHosts)) {
            log.w { "release download redirected outside GitHub host=${response.call.request.url.host}" }
            updateFailure(AppUpdateFailure.InvalidRelease)
        }
        val expected = expectedSize(release, response.contentLength())
        withContext(io) { requireSpace(target.parent, expected) }
        val channel = response.bodyAsChannel()
        val digest = MessageDigest.getInstance("SHA-256")
        var opened: OutputStream? = null
        val total = try {
            // Capture inside the dispatcher block: cancellation can discard its return value.
            val output = withContext(io) {
                storage { Files.newOutputStream(target, StandardOpenOption.CREATE_NEW) }.also { opened = it }
            }
            stream(channel, expected) { buffer, read, received ->
                // Hashing and writing stay off the caller's (main) thread; progress is reported on it.
                withContext(io) {
                    digest.update(buffer, 0, read)
                    storage { output.write(buffer, 0, read) }
                }
                progress(received, expected)
            }
        } finally {
            // A single close: quick on any thread, and it must run even when cancelled.
            opened?.let { output -> storage { output.close() } }
        }
        if (expected != null && total != expected) fail(AppUpdateFailure.Corrupted)
        if (HexFormat.of().formatHex(digest.digest()) != release.sha256) {
            log.w { "release checksum mismatch version=${release.version}" }
            fail(AppUpdateFailure.Corrupted)
        }
    }

    /** The size the download must have: the published one, which the announced length must not contradict. */
    private fun expectedSize(release: AppRelease, announced: Long?): Long? {
        if (announced != null && release.size != null && announced != release.size) fail(AppUpdateFailure.Corrupted)
        val expected = release.size ?: announced
        if (expected != null && expected > maxBytes) fail(AppUpdateFailure.InvalidRelease)
        return expected
    }

    /** Reads [channel] to its end within the size bounds and returns the bytes received. */
    private suspend fun stream(
        channel: ByteReadChannel,
        expected: Long?,
        chunk: suspend (buffer: ByteArray, read: Int, received: Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var read = channel.readAvailable(buffer, 0, buffer.size)
        while (read >= 0) {
            total += read
            if (total > maxBytes) fail(AppUpdateFailure.InvalidRelease)
            if (expected != null && total > expected) fail(AppUpdateFailure.Corrupted)
            chunk(buffer, read, total)
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return total
    }

    private fun requireSpace(directory: Path, expected: Long?) {
        if (expected == null) return
        val usable = storage { Files.getFileStore(directory).usableSpace }
        // The download, its unpacked copy and the copy it replaces coexist until the update is applied.
        if (usable < expected * SPACE_FACTOR + SPACE_MARGIN_BYTES) {
            log.w { "not enough disk space for the update bytes=$expected usable=$usable" }
            fail(AppUpdateFailure.NotEnoughSpace)
        }
    }

    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "update file could not be written" }
        throw AppUpdateException(AppUpdateFailure.Storage, e.withoutDetails())
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "partial update file could not be removed" }
        }
    }

    private fun fail(failure: AppUpdateFailure): Nothing {
        log.w { "release download refused failure=$failure" }
        updateFailure(failure)
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 1L shl 30
        const val BUFFER_BYTES = 64 * 1024
        const val SPACE_FACTOR = 3
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
        const val DOWNLOAD_TIMEOUT_MILLIS = 30 * 60 * 1000L
        const val SOCKET_TIMEOUT_MILLIS = 60 * 1000L
    }
}
