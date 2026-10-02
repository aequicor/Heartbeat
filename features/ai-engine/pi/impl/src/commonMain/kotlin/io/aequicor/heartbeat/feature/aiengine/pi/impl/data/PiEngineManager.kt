package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager

/** Qualified manager contract, like [PiAdapter], so it never collides with other engines' managers. */
internal interface PiEngineManager : EngineManager

/**
 * What engine management may change for Pi: Heartbeat ships a verified Pi, a newer official release may replace
 * it (unverified until a Heartbeat build ships it) and be removed again, keys are added as connections, and launch
 * settings may name another executable and extra environment. `PI_*` and Heartbeat's bridge variables are set by
 * Heartbeat itself.
 */
internal val PiManagementSpec = ManagementSpec(
    install = InstallSupport.Bundled,
    login = LoginSupport.Connections,
    launch = LaunchSpec(
        options = setOf(LaunchOption.Executable, LaunchOption.Environment),
        reservedEnvironmentPrefixes = setOf("PI_", "HEARTBEAT_"),
    ),
)
