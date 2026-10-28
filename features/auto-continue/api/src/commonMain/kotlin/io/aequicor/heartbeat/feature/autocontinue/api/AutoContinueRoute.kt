package io.aequicor.heartbeat.feature.autocontinue.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Auto-continue settings: the mode of new chats, the continuer model, the autopilot's limit and countdown and the
 * number of offered replies. [isEmbedded] marks the route drawn as a section of another window.
 */
@Serializable
@SerialName("auto-continue")
public data class AutoContinueRoute(public val isEmbedded: Boolean = false) : Route
