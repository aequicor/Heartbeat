package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.GitHubDownloadHosts
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
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

class ReleaseDownloaderTest {
    private val workspace: Path = Files.createTempDirectory("heartbeat-download")
    private val target: Path = workspace.resolve("release.part")
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val url = "https://github.com/openai/codex/releases/download/rust-v0.159.3/codex-package.tar.gz"
    private val plan = InstallPlan(
        "0.159.3",
        url,
        sha256(bytes),
        bytes.size.toLong(),
        ArchiveKind.TarGz(),
        "bin/codex",
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
            ReleaseDownloader(releaseClient { respond(bytes, HttpStatusCode.OK) }, dispatcher)
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

        ReleaseDownloader(client, Dispatchers.IO).download(plan, target) { done, total -> reported += done to total }

        assertContentEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), reported.last())
        assertEquals(reported.sortedBy { it.first }, reported)
    }

    @Test
    fun `a checksum mismatch leaves no file behind`() = runTest {
        val client = releaseClient { respond(bytes, HttpStatusCode.OK) }

        assertInstallFailure(InstallFailureReason.ChecksumMismatch) {
            ReleaseDownloader(
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

        assertInstallFailure(InstallFailureReason.UntrustedSource) {
            ReleaseDownloader(client, Dispatchers.IO).download(plan, target) { _, _ -> }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `sizes beyond the announced size or the limit are refused`() = runTest {
        val longer = bytes + byteArrayOf(0)
        assertInstallFailure(InstallFailureReason.SizeMismatch) {
            ReleaseDownloader(releaseClient { respond(longer, HttpStatusCode.OK) }, Dispatchers.IO)
                .download(plan, target) { _, _ -> }
        }
        assertInstallFailure(InstallFailureReason.TooLarge) {
            ReleaseDownloader(releaseClient { respond(bytes, HttpStatusCode.OK) }, Dispatchers.IO, maxBytes = 1_000)
                .download(plan.copy(size = null), target) { _, _ -> }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `publisher errors map to rate limits, missing releases and network failures`() = runTest {
        mapOf(
            HttpStatusCode.Forbidden to InstallFailureReason.RateLimited,
            HttpStatusCode.TooManyRequests to InstallFailureReason.RateLimited,
            HttpStatusCode.NotFound to InstallFailureReason.NoRelease,
            HttpStatusCode.BadGateway to InstallFailureReason.Network,
        ).forEach { (status, reason) ->
            assertInstallFailure(reason) {
                ReleaseDownloader(
                    releaseClient { respond("", status) },
                    Dispatchers.IO,
                ).download(plan, target) { _, _ -> }
            }
        }
        assertFalse(target.exists())
    }
}
