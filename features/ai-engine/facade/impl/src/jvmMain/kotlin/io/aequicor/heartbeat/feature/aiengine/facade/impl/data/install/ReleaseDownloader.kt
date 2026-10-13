package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import java.nio.file.Path

/** Engine installation boundary: reserves two copies and retains management failure types. */
@Inject
internal class ReleaseDownloader(private val downloader: VerifiedReleaseDownloader) {
    /** Downloads and verifies [plan]; progress retains the caller's coroutine context. */
    suspend fun download(plan: InstallPlan, target: Path, progress: suspend (Long, Long?) -> Unit): Unit =
        installRequest {
            downloader.download(ReleaseDownload(plan.url, plan.sha256, plan.size, plan.allowedHosts), target, progress)
        }
}
