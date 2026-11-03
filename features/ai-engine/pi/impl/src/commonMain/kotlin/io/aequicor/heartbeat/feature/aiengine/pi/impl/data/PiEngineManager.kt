package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineConfigValues
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineResource
import io.aequicor.heartbeat.feature.aiengine.facade.api.InstallSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.LaunchSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.LoginSupport
import io.aequicor.heartbeat.feature.aiengine.facade.api.ManagementSpec
import io.aequicor.heartbeat.feature.aiengine.facade.api.ProjectTrust
import io.aequicor.heartbeat.feature.aiengine.facade.api.ResourceInventory
import io.aequicor.heartbeat.feature.aiengine.facade.api.RuntimePreference
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineConfigurator
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineManager

/** Qualified manager contract, like [PiAdapter], so it never collides with other engines' managers. */
internal interface PiEngineManager : EngineManager

/**
 * Pi has a bundled baseline and can keep a managed copy alongside it. A user can explicitly select the managed
 * copy or another executable; selecting Managed never falls back to the bundled runtime. Version metadata alone
 * does not prove admission. `PI_*` and Heartbeat's bridge variables are set by Heartbeat itself. A job installs
 * the packages the configuration lists into the profile's Pi home.
 */
internal val PiManagementSpec = ManagementSpec(
    install = InstallSupport.Bundled,
    login = LoginSupport.Connections,
    launch = LaunchSpec(
        options = setOf(LaunchOption.Executable, LaunchOption.Environment, LaunchOption.Mcp, LaunchOption.Runtime),
        reservedEnvironmentPrefixes = setOf("PI_", "HEARTBEAT_"),
        runtimes = setOf(RuntimePreference.Managed, RuntimePreference.Bundled),
    ),
    hasPackages = true,
)

/** Qualified configurator contract of Pi's files, like [PiEngineManager]. */
internal interface PiConfigurator : EngineConfigurator {
    /** Bounded resource selection and the effective command switch from the same settings snapshot. */
    suspend fun composerResources(
        profile: EngineConfigValues,
        directory: String?,
        trust: ProjectTrust,
    ): PiComposerResources = throw UnsupportedOperationException("Composer resources are unavailable")
}

internal data class PiComposerResources(
    val inventory: ResourceInventory,
    val isSkillCommandsEnabled: Boolean,
    val commands: List<EngineResource>,
)
