package io.aequicor.heartbeat.feature.aiengine.facade.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.validateEngineRegistrations
import io.aequicor.heartbeat.feature.aiengine.facade.impl.domain.EngineToggles

/**
 * Picks the unique default registration for the host platform, unless flags or the profile switched it off. Reads
 * descriptors only, so no adapter factory is constructed. Registrations are validated once, on first use.
 */
@Inject
@SingleIn(ProfileScope::class)
@ContributesBinding(ProfileScope::class)
internal class RegisteredEngineDefaults(
    private val registrations: Set<EngineRegistration>,
    private val platform: PlatformInfo,
    private val gate: EngineToggles,
) : EngineDefaults {
    private val log = Log.tag("EngineDefaults")
    private val defaults: Map<EnginePlatform, EngineRegistration> by lazy {
        validateEngineRegistrations(registrations)
        log.d { "Validated ${registrations.size} engine registrations" }
        EnginePlatform.entries.mapNotNull { target ->
            registrations.singleOrNull { it.descriptor.isDefault && target in it.descriptor.platforms }
                ?.let { target to it }
        }.toMap()
    }

    override suspend fun preferred(): EngineId? {
        val byPlatform = defaults
        val registration = platform.host.enginePlatform()?.let(byPlatform::get)
        val rejection = when {
            registration == null -> "unsupported platform"
            !gate.isEnabled(registration.descriptor) -> "${registration.descriptor.id.value} switched off"
            else -> return registration.descriptor.id.also { log.i { "Default engine: ${it.value}" } }
        }
        log.d { "No default engine: $rejection" }
        return null
    }

    private fun HostPlatform.enginePlatform(): EnginePlatform? = when (this) {
        HostPlatform.Android -> EnginePlatform.Android
        HostPlatform.Ios -> EnginePlatform.Ios
        HostPlatform.MacOs -> EnginePlatform.DesktopMacOs
        HostPlatform.Windows -> EnginePlatform.DesktopWindows
        HostPlatform.Linux -> null
    }
}
