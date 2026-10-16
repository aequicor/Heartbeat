package io.aequicor.heartbeat.feature.settings.impl.domain

import io.aequicor.heartbeat.feature.settings.api.SettingsSection
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow

/** Sections the settings window offers right now, in display order. */
interface SettingsSections {
    /** Current sections and their changes. */
    val available: Flow<ImmutableList<SettingsSection>>
}

/** Pure section rule of [SettingsSections] implementations. */
internal fun availableSections(
    isModelsEnabled: Boolean,
    isSearchEnabled: Boolean,
    hasProfile: Boolean,
    isComputerUseEnabled: Boolean = false,
    isChromiumBridgeEnabled: Boolean = false,
    isLearningEnabled: Boolean = false,
    isHarnessEnabled: Boolean = false,
    isContextEnabled: Boolean = false,
    isInterfacesEnabled: Boolean = false,
    isSubagentsEnabled: Boolean = false,
    isProjectConfigEnabled: Boolean = false,
    isLogsEnabled: Boolean = false,
    isDesktop: Boolean = false,
    isBuildsEnabled: Boolean = false,
): ImmutableList<SettingsSection> {
    val profileToggles = mapOf(
        SettingsSection.Models to isModelsEnabled,
        SettingsSection.Subagents to isSubagentsEnabled,
        SettingsSection.Search to isSearchEnabled,
        SettingsSection.ComputerUse to isComputerUseEnabled,
        SettingsSection.ChromiumBrowser to isChromiumBridgeEnabled,
        SettingsSection.AgentLearning to isLearningEnabled,
        SettingsSection.Context to isContextEnabled,
        SettingsSection.Builds to isBuildsEnabled,
        SettingsSection.Harness to isHarnessEnabled,
        SettingsSection.Interfaces to isInterfacesEnabled,
        SettingsSection.ProjectConfig to isProjectConfigEnabled,
    )
    return SettingsSection.entries.filter { section ->
        // App diagnostics and feature flags need no profile; other sections belong to the active profile.
        when (section) {
            SettingsSection.Logs -> isLogsEnabled && isDesktop

            SettingsSection.FeatureFlags -> true

            SettingsSection.Models,
            SettingsSection.Subagents,
            SettingsSection.Search,
            SettingsSection.ComputerUse,
            SettingsSection.ChromiumBrowser,
            SettingsSection.AgentLearning,
            SettingsSection.Context,
            SettingsSection.Builds,
            SettingsSection.Harness,
            SettingsSection.Interfaces,
            SettingsSection.ProjectConfig,
            -> hasProfile && profileToggles.getValue(section)
        }
    }.toImmutableList()
}
