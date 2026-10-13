package io.aequicor.heartbeat.feature.serviceasui.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiIntent
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiOutput
import io.aequicor.heartbeat.feature.serviceasui.api.ServiceUiState
import io.aequicor.heartbeat.feature.serviceasui.api.UiLibrary

/** The profile machine of agent interfaces, with internal intents. */
internal typealias ServiceUiMachine = Machine<ServiceUiState, ServiceUiIntent, ServiceUiOutput>

/** Durable surface library of the profile. */
internal interface UiLibraryStorage {
    /** The stored library, empty when nothing is stored; fails when the stored value cannot be read. */
    suspend fun load(): UiLibrary

    /** Stores [library] unless a newer revision is already stored. */
    suspend fun save(library: UiLibrary)
}
