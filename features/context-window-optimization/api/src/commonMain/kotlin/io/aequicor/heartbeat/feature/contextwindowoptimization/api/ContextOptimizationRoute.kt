package io.aequicor.heartbeat.feature.contextwindowoptimization.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Context settings: automatic compaction, its threshold and strategy, and how the chat feed shows reasoning and
 * tool calls. [isEmbedded] marks the route drawn as a section of another window, where only the content is shown.
 */
@Serializable
@SerialName("context-optimization")
public data class ContextOptimizationRoute(public val isEmbedded: Boolean = false) : Route
