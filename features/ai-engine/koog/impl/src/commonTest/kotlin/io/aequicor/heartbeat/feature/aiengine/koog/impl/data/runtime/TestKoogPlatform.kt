package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo

/** Common SDK transport tests emulate the desktop host whose logging boundary is covered on JVM. */
internal object TestKoogPlatform : PlatformInfo {
    override val host: HostPlatform = HostPlatform.MacOs
}
