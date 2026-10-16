package io.aequicor.heartbeat.feature.adaptivesubagents.impl.domain

import io.aequicor.heartbeat.core.statemachine.Machine
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesIntent
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesOutput
import io.aequicor.heartbeat.feature.adaptivesubagents.api.ModelProfilesState

/** The library machine of the profile, used by the feature's screens. */
internal typealias LibraryMachine = Machine<ModelProfilesState, ModelProfilesIntent, ModelProfilesOutput>
