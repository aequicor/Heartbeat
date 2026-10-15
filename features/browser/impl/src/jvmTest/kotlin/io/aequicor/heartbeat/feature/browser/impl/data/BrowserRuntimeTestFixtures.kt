package io.aequicor.heartbeat.feature.browser.impl.data

import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.network.release.ReleaseDownload
import io.aequicor.heartbeat.core.network.release.VerifiedReleaseDownloader
import kotlinx.coroutines.CoroutineDispatcher
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

internal fun bundledBrowserRuntimeInstaller(
    root: Path,
    dispatchers: DispatcherProvider,
): DesktopBrowserRuntimeInstaller = DesktopBrowserRuntimeInstaller(
    root,
    object : VerifiedReleaseDownloader {
        override suspend fun download(request: ReleaseDownload, target: Path, progress: suspend (Long, Long?) -> Unit) {
            error("Native tests must use the verified archive on their classpath")
        }
    },
    dispatchers,
)

internal fun browserRuntimeDispatchers(dispatcher: CoroutineDispatcher): DispatcherProvider =
    object : DispatcherProvider {
        override val main = dispatcher
        override val io = dispatcher
        override val default = dispatcher
    }

internal fun browserTar(entries: List<Pair<TarArchiveEntry, ByteArray>>): ByteArray =
    ByteArrayOutputStream().use { output ->
        TarArchiveOutputStream(GZIPOutputStream(output)).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            entries.forEach { (entry, bytes) ->
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        output.toByteArray()
    }
