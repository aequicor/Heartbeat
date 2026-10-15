package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.release.ReleaseArtifact
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.VerifiedArtifactCache
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.archivers.tar.TarConstants
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

internal data class PiArchiveEntry(val name: String, val body: String = "", val type: Byte = TarConstants.LF_NORMAL)

internal fun piArchive(
    root: Path,
    entries: List<PiArchiveEntry> = listOf(
        PiArchiveEntry("pi/pi", "executable"),
    ),
): Path {
    val file = Files.createTempFile(root, "archive-", ".tar.gz")
    TarArchiveOutputStream(GZIPOutputStream(Files.newOutputStream(file))).use { tar ->
        for (entry in entries) {
            val bytes = entry.body.encodeToByteArray()
            val item = TarArchiveEntry(entry.name, entry.type).apply {
                size = bytes.size.toLong()
                mode = 493
                if (entry.type == TarConstants.LF_SYMLINK) linkName = "outside"
            }
            tar.putArchiveEntry(item)
            tar.write(bytes)
            tar.closeArchiveEntry()
        }
    }
    return file
}

internal fun piRelease(): PiRuntimeRelease = PiRuntimeRelease(
    "0.87.1",
    "darwin-arm64",
    ReleaseArtifact(
        "pi-darwin-arm64.tar.gz",
        ReleaseDownload("https://github.com/pi.tar.gz", "a".repeat(64), null, setOf("github.com")),
    ),
)

internal class PiArtifactFixture(val archive: Path) : VerifiedArtifactCache {
    var calls = 0
    var before: suspend () -> Unit = {}
    override suspend fun prepare(artifact: ReleaseArtifact, progress: suspend (Long, Long?) -> Unit): Path {
        calls++
        progress(1, 2)
        before()
        progress(2, 2)
        return archive
    }
}

internal fun TestScope.piDispatchers(): DispatcherProvider = object : DispatcherProvider {
    override val main: CoroutineDispatcher = StandardTestDispatcher(testScheduler)
    override val default: CoroutineDispatcher = main
    override val io: CoroutineDispatcher = main
}

internal suspend fun <T> piTemporary(block: suspend (Path) -> T): T {
    val directory = Files.createTempDirectory("pi-runtime-test-")
    try {
        return block(directory)
    } finally {
        Files.walk(directory).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
}
