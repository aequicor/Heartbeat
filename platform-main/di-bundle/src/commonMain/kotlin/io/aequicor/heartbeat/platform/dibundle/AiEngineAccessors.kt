package io.aequicor.heartbeat.platform.dibundle

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.feature.aiengine.facade.api.AiEngines
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineDefaults
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EnginePlatform
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.EngineRegistration
import io.aequicor.heartbeat.feature.aiengine.facade.api.spi.validateEngineRegistrations
import io.aequicor.heartbeat.feature.aiengine.pi.api.PiEngine

/** Profile entry point for the bundled AI engine and future facade implementations. */
@ContributesTo(ProfileScope::class)
public interface AiEngineAccessors {
    /** Explicit Pi configuration; managed keys remain in the profile vault. */
    public val piEngine: PiEngine

    /** Lazy registrations; metadata access never launches Pi. */
    public val engineRegistrations: Set<EngineRegistration>

    /** Default choice for new routes, respecting platform and toggle. */
    public val engineDefaults: EngineDefaults
}

@Inject
@ContributesBinding(ProfileScope::class)
internal class BundledEngineDefaults(
    private val registrations: Set<EngineRegistration>,
    private val platform: PlatformInfo,
    private val toggles: FeatureToggles,
) : EngineDefaults {
    override suspend fun preferred(): EngineId? {
        validateEngineRegistrations(registrations)
        if (!toggles.get(AiEngines)) return null
        val host = when (platform.host) {
            HostPlatform.Android -> EnginePlatform.Android
            HostPlatform.Ios -> EnginePlatform.Ios
            HostPlatform.MacOs -> EnginePlatform.DesktopMacOs
            HostPlatform.Windows -> EnginePlatform.DesktopWindows
            HostPlatform.Linux -> null
        }
        val candidates = registrations.filter { it.descriptor.isDefault && host in it.descriptor.platforms }
        require(candidates.size <= 1) { "Multiple default engines for platform" }
        return candidates.singleOrNull()?.descriptor?.takeIf { toggles.get(it.toggle) }?.id
    }
}
