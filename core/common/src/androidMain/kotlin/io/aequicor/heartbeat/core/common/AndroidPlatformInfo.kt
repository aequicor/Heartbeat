package io.aequicor.heartbeat.core.common

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject

@ContributesBinding(AppScope::class)
@Inject
internal class AndroidPlatformInfo : PlatformInfo {
    override val host: HostPlatform = HostPlatform.Android
}
