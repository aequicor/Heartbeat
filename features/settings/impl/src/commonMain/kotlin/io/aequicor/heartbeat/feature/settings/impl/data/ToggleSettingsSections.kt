package io.aequicor.heartbeat.feature.settings.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.common.HostPlatform
import io.aequicor.heartbeat.core.common.PlatformInfo
import io.aequicor.heartbeat.core.featuretoggles.FeatureToggles
import io.aequicor.heartbeat.core.profilefacade.ProfileSessions
import io.aequicor.heartbeat.feature.agentlearning.api.AgentLearningEnabled
import io.aequicor.heartbeat.feature.aiengine.connections.api.EngineConnectionsEnabled
import io.aequicor.heartbeat.feature.aistudio.api.StudioEngineRuntime
import io.aequicor.heartbeat.feature.applogs.api.AppLogsEnabled
import io.aequicor.heartbeat.feature.chromiumbridge.api.ChromiumBridgeEnabled
import io.aequicor.heartbeat.feature.computeruse.api.ComputerUseEnabled
import io.aequicor.heartbeat.feature.contextwindowoptimization.api.ContextOptimizationEnabled
import io.aequicor.heartbeat.feature.harness.api.HarnessEnabled
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEnabled
import io.aequicor.heartbeat.feature.searchengine.api.SearchEngineTools
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiEnabled
import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import io.aequicor.heartbeat.feature.settings.impl.di.scope.SettingsScope
import io.aequicor.heartbeat.feature.settings.impl.domain.SettingsSections
import io.aequicor.heartbeat.feature.settings.impl.domain.availableSections
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart

/**
 * Sections gated like their former entry points: models and search are profile routes behind their own toggles
 * and need an active profile (the guest tree cannot show them); feature flags are always available, as the flag
 * panel was. Desktop diagnostics are application-wide and also need no profile.
 */
@Inject
@ContributesBinding(SettingsScope::class)
internal class ToggleSettingsSections(toggles: FeatureToggles, sessions: ProfileSessions, platform: PlatformInfo) :
    SettingsSections {
    private val isDesktop = when (platform.host) {
        HostPlatform.MacOs, HostPlatform.Windows, HostPlatform.Linux -> true
        HostPlatform.Android, HostPlatform.Ios -> false
    }

    override val available: Flow<ImmutableList<SettingsSection>> = combine(
        toggles.observe(EngineConnectionsEnabled),
        toggles.observe(SearchEngineTools),
        combine(toggles.observe(ComputerUseEnabled), toggles.observe(ChromiumBridgeEnabled)) { computer, chromium ->
            computer to chromium
        },
        // Profile features beyond the five-flow overload are combined first.
        combine(
            toggles.observe(AgentLearningEnabled),
            toggles.observe(HarnessEnabled),
            toggles.observe(ContextOptimizationEnabled),
            toggles.observe(ServiceUiEnabled),
            toggles.observe(ProjectConfigEnabled),
        ) { learning, harness, context, interfaces, config ->
            ProfileSections(learning, harness, context, interfaces, config)
        },
        combine(
            sessions.active,
            toggles.observe(AppLogsEnabled),
            toggles.observe(StudioEngineRuntime),
        ) { session, logs, security -> Triple(session, logs, security) },
    ) { connections, search, (computerUse, chromium), profile, (session, logs, security) ->
        availableSections(
            isModelsEnabled = connections,
            isSearchEnabled = search,
            hasProfile = session != null,
            isSecurityEnabled = security,
            isComputerUseEnabled = computerUse,
            isChromiumBridgeEnabled = chromium,
            isLearningEnabled = profile.isLearning,
            isHarnessEnabled = profile.isHarness,
            isContextEnabled = profile.isContext,
            isInterfacesEnabled = profile.isInterfaces,
            isProjectConfigEnabled = profile.isProjectConfig,
            isLogsEnabled = logs,
            isDesktop = isDesktop,
        )
    }
        // Sections without a toggle appear at once; gated ones join when their toggles are read from storage.
        .onStart { emit(availableSections(isModelsEnabled = false, isSearchEnabled = false, hasProfile = false)) }
        .distinctUntilChanged()
}

/** Toggles of the profile sections beyond the five-flow overload of `combine`. */
private data class ProfileSections(
    val isLearning: Boolean,
    val isHarness: Boolean,
    val isContext: Boolean,
    val isInterfaces: Boolean,
    val isProjectConfig: Boolean,
)
