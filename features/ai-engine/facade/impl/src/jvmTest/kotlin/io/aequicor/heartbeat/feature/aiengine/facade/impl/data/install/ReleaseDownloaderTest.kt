package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.ReleaseException
import io.aequicor.heartbeat.core.network.release.ReleaseFailure
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallFailureReason
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.ArchiveKind
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.InstallPlan
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class ReleaseDownloaderTest {
    @Test
    fun `engine plan reserves two copies and forwards progress and failure`() = runTest {
        val plan = InstallPlan(
            "1",
            "https://publisher.test/a",
            "ab".repeat(32),
            42,
            ArchiveKind.Raw("a"),
            "a",
            setOf("publisher.test"),
        )
        val target = Path.of("unused")
        val reports = mutableListOf<Pair<Long, Long?>>()
        val shared = object : VerifiedReleaseDownloader {
            override suspend fun download(
                request: ReleaseDownload,
                target: Path,
                progress: suspend (Long, Long?) -> Unit,
            ) {
                assertEquals(
                    ReleaseDownload(plan.url, plan.sha256, plan.size, plan.allowedHosts, spaceFactor = 2),
                    request,
                )
                assertEquals(Path.of("unused"), target)
                progress(21, 42)
                throw ReleaseException(ReleaseFailure.ChecksumMismatch)
            }
        }
        assertInstallFailure(InstallFailureReason.ChecksumMismatch) {
            ReleaseDownloader(shared).download(plan, target) { received, total -> reports += received to total }
        }
        assertEquals(listOf<Pair<Long, Long?>>(21L to 42L), reports)
    }
}
