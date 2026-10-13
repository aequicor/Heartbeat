package io.aequicor.heartbeat.core.network.impl.release

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.logging.HighFrequency
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.ktor.client.HttpClient
import io.ktor.client.plugins.expectSuccess
import io.ktor.client.plugins.timeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Streams a release file to disk and proves it: the final URL after redirects is https on the plan's hosts, the size
 * never exceeds the announced size or [ReleaseDownload.maxBytes], free space covers its working copies, and the SHA-256
 * computed while streaming matches the publisher's. A failed or cancelled download leaves no file behind.
 */
@Inject
@ContributesBinding(AppScope::class)
internal class StreamingReleaseDownloader(private val client: HttpClient, dispatchers: DispatcherProvider) :
    VerifiedReleaseDownloader {
    private val io = dispatchers.io
    private val log = Log.tag("ReleaseDownloader")

    /** Downloads [request] into the new file [target], reporting the bytes received and the expected total. */
    override suspend fun download(
        request: ReleaseDownload,
        target: Path,
        progress: suspend (bytes: Long, total: Long?) -> Unit,
    ) {
        requireReleaseUrl(request.url, request.allowedHosts)
        log.i { "download release" }
        var isComplete = false
        var isCreated = false
        try {
            releaseRequest {
                client.prepareGet(request.url) {
                    attributes.put(ReleaseRequest, Unit)
                    expectSuccess = false
                    timeout {
                        requestTimeoutMillis = DOWNLOAD_TIMEOUT_MILLIS
                        socketTimeoutMillis = SOCKET_TIMEOUT_MILLIS
                    }
                }.execute { response -> receive(request, response, target, { isCreated = true }, progress) }
            }
            isComplete = true
            log.i { "release downloaded" }
        } finally {
            // A single unlink: quick enough to run on any thread, and it must run even when cancelled.
            if (!isComplete && isCreated) deleteQuietly(target)
        }
    }

    private suspend fun receive(
        request: ReleaseDownload,
        response: HttpResponse,
        target: Path,
        created: () -> Unit,
        progress: suspend (Long, Long?) -> Unit,
    ) {
        requireTrustedRelease(response, request.allowedHosts)
        val expected = expectedSize(request, response.contentLength())
        withContext(io) { requireSpace(target.toAbsolutePath().parent, expected, request.spaceFactor) }
        val channel = response.bodyAsChannel()
        val digest = MessageDigest.getInstance("SHA-256")
        var opened: OutputStream? = null
        var isReadComplete = false
        val total = try {
            // Capture inside the dispatcher block: cancellation can discard its return value.
            val output = withContext(io) {
                storage { Files.newOutputStream(target, StandardOpenOption.CREATE_NEW) }.also {
                    opened = it
                    created()
                }
            }
            stream(channel, expected, request.maxBytes) { buffer, read, received ->
                // Hashing and writing stay off the caller's (main) thread; progress is reported on it.
                withContext(io) {
                    digest.update(buffer, 0, read)
                    storage { output.write(buffer, 0, read) }
                }
                progress(received, expected)
            }.also { isReadComplete = true }
        } finally {
            // A single close: quick on any thread, and it must run even when cancelled.
            opened?.let { output -> close(output, isReadComplete) }
        }
        if (expected != null && total != expected) fail(ReleaseFailure.SizeMismatch)
        if (HexFormat.of().formatHex(digest.digest()) != request.sha256) {
            fail(ReleaseFailure.ChecksumMismatch)
        }
    }

    private fun close(output: OutputStream, isReadComplete: Boolean) {
        try {
            storage { output.close() }
        } catch (e: ReleaseException) {
            if (isReadComplete) throw e
            log.w(e) { "partial release stream could not be closed" }
        }
    }

    /** The size the download must have: the plan's, which the announced length must not contradict. */
    private fun expectedSize(request: ReleaseDownload, announced: Long?): Long? {
        if (announced != null && request.size != null && announced != request.size) fail(ReleaseFailure.SizeMismatch)
        val expected = request.size ?: announced
        if (expected != null && expected > request.maxBytes) fail(ReleaseFailure.TooLarge)
        return expected
    }

    /** Reads [channel] to its end within the size bounds and returns the bytes received. */
    @HighFrequency
    private suspend fun stream(
        channel: ByteReadChannel,
        expected: Long?,
        maxBytes: Long,
        chunk: suspend (buffer: ByteArray, read: Int, received: Long) -> Unit,
    ): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        var read = channel.readAvailable(buffer, 0, buffer.size)
        while (read >= 0) {
            total += read
            if (total > maxBytes) fail(ReleaseFailure.TooLarge)
            if (expected != null && total > expected) fail(ReleaseFailure.SizeMismatch)
            chunk(buffer, read, total)
            read = channel.readAvailable(buffer, 0, buffer.size)
        }
        return total
    }

    private fun requireSpace(directory: Path, expected: Long?, spaceFactor: Int) {
        if (expected == null) return
        val usable = storage { Files.getFileStore(directory).usableSpace }
        // The archive and its unpacked copy coexist until the archive is removed.
        if (usable < expected * spaceFactor + SPACE_MARGIN_BYTES) {
            fail(ReleaseFailure.NotEnoughSpace)
        }
    }

    // The file system message names the user's paths: only the kind of failure travels on, so it is not the cause.
    @Suppress("SwallowedException")
    private fun <T> storage(block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw ReleaseException(ReleaseFailure.Storage, origin = e::class.simpleName)
    }

    // Raw IO messages may contain private paths.
    @Suppress("SwallowedException")
    private fun deleteQuietly(path: Path) {
        try {
            Files.deleteIfExists(path)
        } catch (e: IOException) {
            log.w(
                ReleaseException(ReleaseFailure.Storage, origin = e::class.simpleName),
            ) { "partial release file could not be removed" }
        }
    }

    private fun fail(reason: ReleaseFailure): Nothing = throw ReleaseException(reason)

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        const val SPACE_MARGIN_BYTES = 64L * 1024 * 1024
        const val DOWNLOAD_TIMEOUT_MILLIS = 30 * 60 * 1000L
        const val SOCKET_TIMEOUT_MILLIS = 60 * 1000L
    }
}
