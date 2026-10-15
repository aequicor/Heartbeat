package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/** Passive setup progress of an engine first requested by a chat; normal submission cancel/retry still applies. */
@Serializable
@Immutable
data class EnginePreparationUi(val engine: String, val phase: EnginePreparationPhaseUi, val percent: Int? = null)

/** Runtime setup and safe error categories shared with the remote Studio view. */
@Serializable
enum class EnginePreparationPhaseUi { Downloading, Installing, Network, Storage, InvalidRuntime, Unavailable }
