package io.aequicor.heartbeat.feature.agentlearning.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Registry of instructions the agent learned: their approval level, list, switches and edits.
 * [isEmbedded] marks the route drawn as a section of the settings window, where only the content is shown.
 */
@Serializable
@SerialName("agent-learning")
public data class AgentLearningRoute(public val isEmbedded: Boolean = false) : Route
