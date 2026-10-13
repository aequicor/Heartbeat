package io.aequicor.heartbeat.feature.appupdate.impl.data

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.release.GitHubDownloadHosts
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.appupdate.api.AppRelease
import kotlinx.coroutines.withContext
import java.nio.file.Path

/** App update boundary: reserves three coexisting copies, reports progress on IO and maps failures to app errors. */
@Inject
internal class UpdateDownloader(private val downloader: VerifiedReleaseDownloader, dispatchers: DispatcherProvider) {
    private val io = dispatchers.io

    /** Downloads and verifies [release] into a new [target]. */
    suspend fun download(release: AppRelease, target: Path, progress: suspend (Long, Long?) -> Unit): Unit =
        withContext(
            io,
        ) {
            updateRequest {
                downloader.download(
                    ReleaseDownload(
                        release.url,
                        release.sha256,
                        release.size,
                        GitHubDownloadHosts,
                        spaceFactor = SPACE_FACTOR,
                    ),
                    target,
                    progress,
                )
            }
        }
}

private const val SPACE_FACTOR = 3
