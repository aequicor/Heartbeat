package io.aequicor.heartbeat.feature.chromiumbridge.impl.di

import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.ContributesIntoSet
import dev.zacsweers.metro.ContributesTo
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.di.ForScope
import io.aequicor.heartbeat.core.di.ProfileScope
import io.aequicor.heartbeat.core.di.ScopeHandle
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.logging.Log
import io.aequicor.heartbeat.core.profilefacade.ProfileStartup
import io.aequicor.heartbeat.core.statemachine.EffectHandler
import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.core.statemachine.MachineLauncher
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeAgentControl
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEffect
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeIntent
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeMachineSpec
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeOutput
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeState
import io.aequicor.heartbeat.feature.chromiumbridge.impl.data.ToggleBridgeFeatures
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.BridgeServer
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.ChromiumBridgeEffects
import io.aequicor.heartbeat.feature.chromiumbridge.impl.domain.NativeHostRegistry
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** The machine of the bridge lives as long as the profile, so the socket survives screens. */
@ContributesTo(ProfileScope::class)
@BindingContainer
public object ChromiumBridgeBindings {
    /** Launches the machine for the lifetime of the profile. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun machine(
        launcher: MachineLauncher,
        @ForScope(ProfileScope::class) scope: ScopeHandle,
        effects: EffectHandler<ChromiumBridgeEffect, ChromiumBridgeIntent>,
    ): Machine<ChromiumBridgeState, ChromiumBridgeIntent, ChromiumBridgeOutput> =
        launcher.launch(ChromiumBridgeMachineSpec, scope, effects)

    /** Effects reach the platform server and registry lazily. */
    @Provides
    internal fun effects(
        server: Lazy<BridgeServer>,
        hosts: Lazy<NativeHostRegistry>,
    ): EffectHandler<ChromiumBridgeEffect, ChromiumBridgeIntent> = ChromiumBridgeEffects(server, hosts)

    /** Features offered to extensions. */
    @Provides
    @SingleIn(ProfileScope::class)
    internal fun features(toggles: FeatureToggles): ToggleBridgeFeatures = ToggleBridgeFeatures(toggles)
}

/**
 * Feeds toggle and platform availability into the machine. Nothing of the bridge is created until it is enabled
 * for the first time; afterwards switching it off closes the socket.
 */
@ContributesIntoSet(ProfileScope::class)
@Inject
internal class ChromiumBridgeStartup(
    private val machine: Lazy<Machine<ChromiumBridgeState, ChromiumBridgeIntent, ChromiumBridgeOutput>>,
    private val toggles: FeatureToggles,
    private val platform: PlatformInfo,
    @ForScope(ProfileScope::class) private val profile: ScopeHandle,
) : ProfileStartup {
    private val log = Log.tag("ChromiumBridgeStartup")

    override fun start() {
        val isSupported = platform.host == HostPlatform.MacOs || platform.host == HostPlatform.Windows
        profile.coroutineScope.launch {
            val availability = combine(
                toggles.observe(ChromiumBridgeEnabled),
                toggles.observe(ChromiumBridgeAgentControl),
            ) { isEnabled, isAgentControl -> isEnabled to isAgentControl }
            availability.distinctUntilChanged()
                .collect { (isEnabled, isAgentControl) ->
                    if (!isEnabled && !machine.isInitialized()) return@collect
                    val intent =
                        ChromiumBridgeIntent.Internal.AvailabilityChanged(isEnabled, isSupported, isAgentControl)
                    val result = machine.value.send(intent)
                    log.i {
                        "Chromium bridge availability enabled=$isEnabled supported=$isSupported " +
                            "agentControl=$isAgentControl result=$result"
                    }
                }
        }
    }
}
