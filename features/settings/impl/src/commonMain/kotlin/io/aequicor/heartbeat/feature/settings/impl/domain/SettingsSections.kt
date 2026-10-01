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
): ImmutableList<SettingsSection> = SettingsSection.entries.filter { section ->
    when (section) {
        SettingsSection.Models -> isModelsEnabled && hasProfile
        SettingsSection.Search -> isSearchEnabled && hasProfile
        SettingsSection.ComputerUse -> isComputerUseEnabled && hasProfile
        SettingsSection.FeatureFlags -> true
    }
}.toImmutableList()
