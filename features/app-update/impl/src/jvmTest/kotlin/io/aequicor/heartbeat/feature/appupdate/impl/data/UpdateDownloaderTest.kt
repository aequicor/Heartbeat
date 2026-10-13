package io.aequicor.heartbeat.feature.appupdate.impl.data

import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import io.aequicor.heartbeat.feature.appupdate.api.AppUpdateFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateDownloaderTest {
    private val release = AppRelease("1.4.0", "build.dmg", "https://github.com/build", "ab".repeat(32), 42, "")
    private val target = Path.of("unused")

    @Test
    fun `app updates reserve three copies and forward progress`() = runTest {
        val reports = mutableListOf<Pair<Long, Long?>>()
        val shared = object : VerifiedReleaseDownloader {
            override suspend fun download(
                request: ReleaseDownload,
                target: Path,
                progress: suspend (Long, Long?) -> Unit,
            ) {
                assertEquals(
                    ReleaseDownload(
                        release.url,
                        release.sha256,
                        release.size,
                        GitHubDownloadHosts,
                        spaceFactor = 3,
                    ),
                    request,
                )
                assertEquals(Path.of("unused"), target)
                progress(21, 42)
            }
        }
        UpdateDownloader(
            shared,
            TestDispatchers,
        ).download(release, target) { received, total -> reports += received to total }
        assertEquals(listOf<Pair<Long, Long?>>(21L to 42L), reports)
    }

    @Test
    fun `all shared failures retain the app update categories`() = runTest {
        val cases = listOf(
            ReleaseException(ReleaseFailure.Http, 403) to AppUpdateFailure.RateLimited,
            ReleaseException(ReleaseFailure.Http, 429) to AppUpdateFailure.RateLimited,
            ReleaseException(ReleaseFailure.Http, 404) to AppUpdateFailure.Network,
            ReleaseException(ReleaseFailure.Http, 410) to AppUpdateFailure.Network,
            ReleaseException(ReleaseFailure.Http, 502) to AppUpdateFailure.Network,
            ReleaseException(ReleaseFailure.Network) to AppUpdateFailure.Network,
            ReleaseException(ReleaseFailure.InvalidMetadata) to AppUpdateFailure.InvalidRelease,
            ReleaseException(ReleaseFailure.UntrustedSource) to AppUpdateFailure.InvalidRelease,
            ReleaseException(ReleaseFailure.TooLarge) to AppUpdateFailure.InvalidRelease,
            ReleaseException(ReleaseFailure.SizeMismatch) to AppUpdateFailure.Corrupted,
            ReleaseException(ReleaseFailure.ChecksumMismatch) to AppUpdateFailure.Corrupted,
            ReleaseException(ReleaseFailure.NotEnoughSpace) to AppUpdateFailure.NotEnoughSpace,
            ReleaseException(ReleaseFailure.Storage) to AppUpdateFailure.Storage,
        )
        cases.forEach { (error, failure) ->
            assertUpdateFailure(failure) { failing(error).download(release, target) { _, _ -> } }
        }
    }

    @Test
    fun `cancellation crosses the boundary unchanged`() = runTest {
        val cancellation = CancellationException("cancel")
        val error = assertFailsWith<CancellationException> {
            failing(cancellation).download(release, target) { _, _ -> }
        }
        // Coroutine stack-trace recovery may copy cancellation when returning from the IO dispatcher.
        assertEquals(cancellation.message, error.message)
    }

    private fun failing(failure: Exception) = UpdateDownloader(
        object : VerifiedReleaseDownloader {
            override suspend fun download(
                request: ReleaseDownload,
                target: Path,
                progress: suspend (Long, Long?) -> Unit,
            ): Unit = throw failure
        },
        TestDispatchers,
    )
}
