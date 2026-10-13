package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(kotlin.io.path.ExperimentalPathApi::class)
class UpdateDownloaderTest {
    private val directory = createTempDirectory("app-update-download")
    private val bytes = ByteArray(200_000) { (it % 251).toByte() }
    private val release = AppRelease(
        version = "1.4.0",
        assetName = "Heartbeat-1.4.0-macos-arm64.dmg",
        url = "https://github.com/aequicor/Heartbeat/releases/download/v1.4.0/Heartbeat-1.4.0-macos-arm64.dmg",
        sha256 = sha256(bytes),
        size = bytes.size.toLong(),
        pageUrl = "https://github.com/aequicor/Heartbeat/releases/tag/v1.4.0",
    )

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private fun downloader(body: ByteArray = bytes) = UpdateDownloader(
        releaseClient {
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, body.size.toString()))
        },
        TestDispatchers,
    )

    @Test
    fun `a verified download reports its progress`() = runTest {
        val target = directory.resolve("update.dmg")
        val progress = mutableListOf<Pair<Long, Long?>>()

        downloader().download(release, target) { received, total -> progress += received to total }

        assertContentEquals(bytes, target.readBytes())
        assertEquals(bytes.size.toLong() to bytes.size.toLong(), progress.last())
    }

    @Test
    fun `a corrupted download leaves no file`() = runTest {
        val target = directory.resolve("update.dmg")
        val corrupted = bytes.copyOf().also { it[0] = 1 }

        assertUpdateFailure(AppUpdateFailure.Corrupted) { downloader(corrupted).download(release, target) { _, _ -> } }
        assertFalse(target.exists())
        assertUpdateFailure(AppUpdateFailure.Corrupted) {
            downloader(bytes.copyOf(bytes.size - 1)).download(release, target) { _, _ -> }
        }
        assertFalse(target.exists())
    }

    @Test
    fun `a release outside GitHub is not downloaded`() = runTest {
        val target = directory.resolve("update.dmg")
        assertUpdateFailure(AppUpdateFailure.InvalidRelease) {
            downloader().download(release.copy(url = "https://example.com/a.dmg"), target) { _, _ -> }
        }
        assertFalse(Files.exists(target))
    }
}
