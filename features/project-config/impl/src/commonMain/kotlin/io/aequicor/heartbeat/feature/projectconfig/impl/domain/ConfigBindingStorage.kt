package io.aequicor.heartbeat.feature.projectconfig.impl.domain

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigStorageSettings

/** Durable bindings of the profile. Reads fail instead of returning an empty list for unreadable data. */
internal interface ConfigBindingStorage {
    /** The stored bindings. */
    suspend fun load(): List<ConfigBinding>

    /** Replaces the stored bindings. */
    suspend fun save(bindings: List<ConfigBinding>)

    /** Location preferences; malformed preferences fail closed instead of defaulting to project IO. */
    suspend fun loadSettings(): ConfigStorageSettings

    /** Replaces location preferences. Call through ConfigStoragePolicy to fence concurrent project IO. */
    suspend fun saveSettings(settings: ConfigStorageSettings)
}
