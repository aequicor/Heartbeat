package io.aequicor.heartbeat.feature.aistudio.screenmodel

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pro.respawn.flowmvi.api.MVIAction

/** No one-shot screen actions: every reaction is state. */
@Serializable
@SerialName("AiStudioScreenAction")
sealed interface AiStudioScreenAction : MVIAction
