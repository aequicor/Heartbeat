package io.aequicor.heartbeat.feature.aiengine.facade.impl.data.install

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.DispatcherProvider
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.datastore.StorageRoot
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.ManagedInstallStore
import io.ktor.client.HttpClient
import java.nio.file.Path
import kotlin.time.Clock

/**
 * Desktop managed copies. Executables are machine-local: on Windows they live in `%LOCALAPPDATA%` (never in the
 * roaming `%APPDATA%` of the other app data), which the Windows installer removes on uninstall;
 * on macOS next to the app data.
 */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class, priority = 1)
internal class DesktopManagedInstallStore(
    client: HttpClient,
    storage: StorageRoot,
    platform: PlatformInfo,
    dispatchers: DispatcherProvider,
    clock: Clock,
    fileLocks: ManagedFileLocks,
) : ManagedInstallStore by FileManagedInstallStore(
        managedRoot(platform.host, storage),
        ReleaseDownloader(client, dispatchers.io),
        ArchiveExtractor(),
        dispatchers.io,
        clock,
        fileLocks,
    )

/** Folder of the managed copies on [host]. */
internal fun managedRoot(host: HostPlatform, storage: StorageRoot): Path = when (host) {
    HostPlatform.Windows -> {
        val home = System.getProperty("user.home").orEmpty()
        Path.of(System.getenv("LOCALAPPDATA") ?: "$home/AppData/Local", "Aequicor", "Heartbeat", "engines", "managed")
    }

    HostPlatform.MacOs, HostPlatform.Linux, HostPlatform.Android, HostPlatform.Ios ->
        Path.of(storage.path(), "engines", "managed")
}
