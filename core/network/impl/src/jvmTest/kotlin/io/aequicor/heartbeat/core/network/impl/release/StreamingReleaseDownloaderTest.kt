package io.aequicor.heartbeat.core.network.impl.release

import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class StreamingReleaseDownloaderTest {
    private val workspace: Path = Files.createTempDirectory("heartbeat-download")
    private val target: Path = workspace.resolve("release.part")
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val url = "https://github.com/openai/codex/releases/download/rust-v0.159.3/codex-package.tar.gz"
    private val plan = ReleaseDownload(
        url,
        sha256(bytes),
        bytes.size.toLong(),
        GitHubDownloadHosts,
    )

    @AfterTest
    fun cleanUp() {
        workspace.toFile().deleteRecursively()
    }

    @Test
    fun `cancellation on return from opening the output removes the partial file`() = runTest {
        lateinit var request: kotlinx.coroutines.Deferred<Unit>
        val dispatcher = afterDispatch(kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)) {
            if (target.exists()) request.cancel()
        }
        request = async(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            downloader(releaseClient { respond(bytes, HttpStatusCode.OK) }, dispatcher)
                .download(plan, target) { _, _ -> }
        }
        request.start()
        request.join()
        kotlin.test.assertTrue(request.isCancelled)
        assertFalse(target.exists())
    }

    @Test
    fun `a release is streamed to disk through GitHub's redirect and verified`() = runTest {
        val client = releaseClient { request ->
            if (request.url.host == "github.com") {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://release-assets.githubusercontent.com/a"),
                )
            } else {
                respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, bytes.size.toString()))
            }
        }
        val reported = mutableListOf<Pair<Long, Long?>>()

        downloader(client, Dispatchers.IO).download(plan, target) { done, total -> reported += done to total }

        assertContentEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), reported.last())
        assertEquals(reported.sortedBy { it.first }, reported)
    }

    @Test
    fun `an existing target is never removed`() = runTest {
        Files.write(target, byteArrayOf(42))
        assertReleaseFailure(ReleaseFailure.Storage) {
            downloader(releaseClient { respond(bytes, HttpStatusCode.OK) }, Dispatchers.IO)
                .download(plan, target) { _, _ -> }
        }
        assertContentEquals(byteArrayOf(42), target.readBytes())
    }

    @Test
    fun `insufficient space is refused before creating a file`() = runTest {
        assertReleaseFailure(ReleaseFailure.NotEnoughSpace) {
            downloader(releaseClient { respond(bytes, HttpStatusCode.OK) }, Dispatchers.IO)
                .download(plan.copy(spaceFactor = Int.MAX_VALUE), target) { _, _ -> }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `a checksum mismatch leaves no file behind`() = runTest {
        val client = releaseClient { respond(bytes, HttpStatusCode.OK) }

        assertReleaseFailure(ReleaseFailure.ChecksumMismatch) {
            downloader(
                client,
                Dispatchers.IO,
            ).download(plan.copy(sha256 = sha256(byteArrayOf(1))), target) { _, _ -> }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `a redirect off the publisher's hosts is untrusted`() = runTest {
        val client = releaseClient { request ->
            if (request.url.host == "github.com") {
                respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://evil.example.com/codex"))
            } else {
                respond(bytes, HttpStatusCode.OK)
            }
        }

        assertReleaseFailure(ReleaseFailure.UntrustedSource) {
            downloader(client, Dispatchers.IO).download(plan, target) { _, _ -> }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `sizes beyond the announced size or the limit are refused`() = runTest {
        val longer = bytes + byteArrayOf(0)
        assertReleaseFailure(ReleaseFailure.SizeMismatch) {
            downloader(releaseClient { respond(longer, HttpStatusCode.OK) }, Dispatchers.IO)
                .download(plan, target) { _, _ -> }
        }
        assertReleaseFailure(ReleaseFailure.TooLarge) {
            downloader(releaseClient { respond(bytes, HttpStatusCode.OK) }, Dispatchers.IO)
                .download(plan.copy(size = null, maxBytes = 1_000), target) { _, _ -> }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `publisher errors map to rate limits, missing releases and network failures`() = runTest {
        mapOf(
            HttpStatusCode.Forbidden to ReleaseFailure.Http,
            HttpStatusCode.TooManyRequests to ReleaseFailure.Http,
            HttpStatusCode.NotFound to ReleaseFailure.Http,
            HttpStatusCode.BadGateway to ReleaseFailure.Http,
        ).forEach { (status, reason) ->
            assertReleaseFailure(reason) {
                downloader(
                    releaseClient { respond("", status) },
                    Dispatchers.IO,
                ).download(plan, target) { _, _ -> }
            }
        }
        assertFalse(target.exists())
    }
}
