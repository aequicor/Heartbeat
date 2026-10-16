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
    isProjectConfigEnabled: Boolean = false,
    isLogsEnabled: Boolean = false,
    isDesktop: Boolean = false,
): ImmutableList<SettingsSection> = SettingsSection.entries.filter { section ->
    // App diagnostics and feature flags need no profile; other sections belong to the active profile.
    val isToggledOn = when (section) {
        SettingsSection.Models -> isModelsEnabled
        SettingsSection.Search -> isSearchEnabled
        SettingsSection.ComputerUse -> isComputerUseEnabled
        SettingsSection.ChromiumBrowser -> isChromiumBridgeEnabled
        SettingsSection.AgentLearning -> isLearningEnabled
        SettingsSection.Context -> isContextEnabled
        SettingsSection.Harness -> isHarnessEnabled
        SettingsSection.Interfaces -> isInterfacesEnabled
        SettingsSection.ProjectConfig -> isProjectConfigEnabled
        SettingsSection.Logs -> return@filter isLogsEnabled && isDesktop
        SettingsSection.FeatureFlags -> return@filter true
    }
    isToggledOn && hasProfile
}.toImmutableList()
