package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.networkResult
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.hostOf
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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Streams a release file to disk and proves it: the final URL after redirects is https on the plan's hosts, the size
 * never exceeds the announced size or [maxBytes], free space covers the file and its unpacked copy, and the SHA-256
 * computed while streaming matches the publisher's. A failed or cancelled download leaves no file behind.
 */
class ReleaseDownloader(
    private val client: HttpClient,
    private val io: CoroutineDispatcher,
    private val maxBytes: Long = MAX_DOWNLOAD_BYTES,
) {
    private val log = Log.tag("ReleaseDownloader")

    /** Downloads [plan] into the new file [target], reporting the bytes received and the expected total. */
    suspend fun download(plan: InstallPlan, target: Path, progress: suspend (bytes: Long, total: Long?) -> Unit) {
        log.i { "download release version=${plan.version} host=${hostOf(plan.url)}" }
        var isComplete = false
        try {
            networkResult {
                client.prepareGet(plan.url) {
                    timeout {
                        requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                    }
                }.execute { response -> receive(plan, response, target, progress) }
            }.getOrElse { error ->
                log.w(error) { "release download failed version=${plan.version}" }
                throw error.asInstallFailure()
            }
            isComplete = true
            log.i { "release downloaded version=${plan.version} bytes=${Files.size(target)}" }
        } finally {
            // A single unlink: quick enough to run on any thread, and it must run even when cancelled.
            if (!isComplete) deleteQuietly(target)
        }
    }

    private suspend fun receive(
        plan: InstallPlan,
        response: HttpResponse,
        target: Path,
        progress: suspend (Long, Long?) -> Unit,
    ) {
        requireTrusted(response, plan.allowedHosts)
        val expected = expectedSize(plan, response.contentLength())
        withContext(io) { requireSpace(target.parent, expected) }
        val channel = response.bodyAsChannel()
        val digest = MessageDigest.getInstance("SHA-256")
        val output = withContext(io) { storage { Files.newOutputStream(target, StandardOpenOption.CREATE_NEW) } }
        val total = try {
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
            storage { output.close() }
        }
        if (expected != null && total != expected) fail(InstallFailureReason.SizeMismatch)
        if (HexFormat.of().formatHex(digest.digest()) != plan.sha256) {
            log.w { "release checksum mismatch version=${plan.version}" }
            fail(InstallFailureReason.ChecksumMismatch)
        }
    }

    /** The size the download must have: the plan's, which the announced length must not contradict. */
    private fun expectedSize(plan: InstallPlan, announced: Long?): Long? {
        if (announced != null && plan.size != null && announced != plan.size) fail(InstallFailureReason.SizeMismatch)
        val expected = plan.size ?: announced
        if (expected != null && expected > maxBytes) fail(InstallFailureReason.TooLarge)
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
            if (total > maxBytes) fail(InstallFailureReason.TooLarge)
            if (expected != null && total > expected) fail(InstallFailureReason.SizeMismatch)
            chunk(buffer, read, total)
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return total
    }

    private fun requireSpace(directory: Path, expected: Long?) {
        if (expected == null) return
        val usable = storage { Files.getFileStore(directory).usableSpace }
        // The archive and its unpacked copy coexist until the archive is removed.
        if (usable < expected * 2 + SPACE_MARGIN_BYTES) {
            log.w { "not enough disk space for the release bytes=$expected usable=$usable" }
            fail(InstallFailureReason.NotEnoughSpace)
        }
    }

    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        log.w(e.withoutDetails()) { "release file could not be written" }
        throw installFailure(InstallFailureReason.Storage, e.withoutDetails())
    }

    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log.w(e.withoutDetails()) { "partial release file could not be removed" }
        }
    }

    private fun fail(reason: InstallFailureReason): Nothing {
        log.w { "release download refused reason=$reason" }
        throw installFailure(reason)
    }

    private companion object {
        const val MAX_DOWNLOAD_BYTES = 1L shl 30
        const val BUFFER_BYTES = 64 * 1024
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
        const val DOWNLOAD_TIMEOUT_MILLIS = 30 * 60 * 1000L
        const val SOCKET_TIMEOUT_MILLIS = 60 * 1000L
    }
}
