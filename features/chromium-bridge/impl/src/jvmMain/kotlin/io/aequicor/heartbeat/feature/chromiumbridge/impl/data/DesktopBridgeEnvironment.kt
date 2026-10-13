package io.aequicor.heartbeat.feature.chromiumbridge.impl.data

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.datastore.DataStores
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts.BrowserLocations
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts.HostEnvironment
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.hosts.HostLauncher
import java.nio.file.Path

/** Desktop locations of the bridge: its application files directory, the socket and the browsers. */
internal class DesktopBridgeEnvironment(private val stores: Lazy<DataStores>, private val platform: PlatformInfo) {
    private val directory: Path by lazy { Path.of(stores.value.filesDirectory(FILES)).toAbsolutePath() }

    /** The app socket hosts connect to. */
    fun socket(): Path = BridgePaths.socket(directory)

    /** Everything host registration needs, read fresh so a moved app is noticed. */
    fun host(): HostEnvironment {
        val isWindows = platform.host == HostPlatform.Windows
        val home = Path.of(System.getProperty("user.home"))
        val locations = when (platform.host) {
            HostPlatform.MacOs -> BrowserLocations.macOs(home)

            HostPlatform.Windows -> BrowserLocations.windows(
                System.getenv("LOCALAPPDATA")?.let(Path::of) ?: home.resolve("AppData/Local"),
            )

            HostPlatform.Android, HostPlatform.Ios, HostPlatform.Linux -> emptyList()
        }
        return HostEnvironment(locations, isWindows, directory, socket(), HostLauncher.current(isWindows))
    }

    private companion object {
        const val FILES = "chromium_bridge"
    }
}
