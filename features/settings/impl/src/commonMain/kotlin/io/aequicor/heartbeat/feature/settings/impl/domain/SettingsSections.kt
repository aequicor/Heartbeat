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
    isLearningEnabled: Boolean = false,
    isHarnessEnabled: Boolean = false,
    isContextEnabled: Boolean = false,
    isInterfacesEnabled: Boolean = false,
): ImmutableList<SettingsSection> = SettingsSection.entries.filter { section ->
    val isOn = when (section) {
        SettingsSection.Models -> isModelsEnabled
        SettingsSection.Search -> isSearchEnabled
        SettingsSection.ComputerUse -> isComputerUseEnabled
        SettingsSection.AgentLearning -> isLearningEnabled
        SettingsSection.Context -> isContextEnabled
        SettingsSection.Harness -> isHarnessEnabled
        SettingsSection.Interfaces -> isInterfacesEnabled
        SettingsSection.FeatureFlags -> true
    }
    // Every section but the device-local feature flags belongs to a profile.
    isOn && (hasProfile || section == SettingsSection.FeatureFlags)
}.toImmutableList()
