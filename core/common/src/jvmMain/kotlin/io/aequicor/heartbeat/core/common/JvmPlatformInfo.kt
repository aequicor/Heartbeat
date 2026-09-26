package io.aequicor.heartbeat.core.common

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

@ContributesBinding(AppScope::class)
@Inject
internal class JvmPlatformInfo : PlatformInfo {
    override val host: HostPlatform = hostOf(System.getProperty("os.name").orEmpty())

    internal companion object {
        fun hostOf(osName: String): HostPlatform = when {
            osName.startsWith("Mac", ignoreCase = true) -> HostPlatform.MacOs
            osName.startsWith("Windows", ignoreCase = true) -> HostPlatform.Windows
            else -> HostPlatform.Linux
        }
    }
}
