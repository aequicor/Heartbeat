package io.aequicor.heartbeat.feature.projectconfig.impl.domain

import io.aequicor.heartbeat.feature.projectconfig.api.ConfigBinding

/** Durable bindings of the profile. Reads fail instead of returning an empty list for unreadable data. */
internal interface ConfigBindingStorage {
    /** The stored bindings. */
    suspend fun load(): List<ConfigBinding>

    /** Replaces the stored bindings. */
    suspend fun save(bindings: List<ConfigBinding>)
}
