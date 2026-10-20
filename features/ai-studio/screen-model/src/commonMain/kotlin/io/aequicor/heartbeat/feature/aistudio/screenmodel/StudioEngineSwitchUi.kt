package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Shared local/remote projection of a profile-owned engine replacement. */
@Serializable
@SerialName("EngineSwitchUi")
@Immutable
data class EngineSwitchUi(val isPending: Boolean = false, val failure: EngineSwitchFailureUi? = null)

/** Localized by the renderer, including per-route compatibility reasons. */
@Serializable
@SerialName("EngineSwitchFailureUi")
enum class EngineSwitchFailureUi { Disabled, Busy, Stale, Unsupported, Unavailable, Workspace }
