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
    isSecurityEnabled: Boolean = false,
    isComputerUseEnabled: Boolean = false,
    isChromiumBridgeEnabled: Boolean = false,
    isLearningEnabled: Boolean = false,
    isHarnessEnabled: Boolean = false,
    isContextEnabled: Boolean = false,
    isInterfacesEnabled: Boolean = false,
    isProjectConfigEnabled: Boolean = false,
    isLogsEnabled: Boolean = false,
    isDesktop: Boolean = false,
): ImmutableList<SettingsSection> = SettingsSection.entries.filter { section ->
    val isToggledOn = when (section) {
        SettingsSection.Models -> isModelsEnabled
        SettingsSection.Security -> isSecurityEnabled
        SettingsSection.Search -> isSearchEnabled
        SettingsSection.ComputerUse -> isComputerUseEnabled
        SettingsSection.ChromiumBrowser -> isChromiumBridgeEnabled
        SettingsSection.AgentLearning -> isLearningEnabled
        SettingsSection.Context -> isContextEnabled
        SettingsSection.Harness -> isHarnessEnabled
        SettingsSection.Interfaces -> isInterfacesEnabled
        SettingsSection.ProjectConfig -> isProjectConfigEnabled
        SettingsSection.Logs -> isLogsEnabled
        SettingsSection.FeatureFlags -> true
    }
    isToggledOn && section.isInScope(hasProfile, isDesktop)
}.toImmutableList()

/** App diagnostics and feature flags need no profile; other sections belong to the active profile. */
private fun SettingsSection.isInScope(hasProfile: Boolean, isDesktop: Boolean): Boolean = when (this) {
    SettingsSection.Logs -> isDesktop

    SettingsSection.FeatureFlags -> true

    SettingsSection.Models,
    SettingsSection.Security,
    SettingsSection.Search,
    SettingsSection.ComputerUse,
    SettingsSection.ChromiumBrowser,
    SettingsSection.AgentLearning,
    SettingsSection.Context,
    SettingsSection.Harness,
    SettingsSection.Interfaces,
    SettingsSection.ProjectConfig,
    -> hasProfile
}
