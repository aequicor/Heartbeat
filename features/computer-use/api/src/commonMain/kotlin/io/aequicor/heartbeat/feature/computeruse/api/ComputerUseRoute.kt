package io.aequicor.heartbeat.feature.computeruse.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The computer use control panel: mode selection, window picker, frame preview, input arming and the kill
 * switch. [isEmbedded] marks the route drawn as a section of another window, where only the content is shown.
 */
@Serializable
@SerialName("computer-use")
public data class ComputerUseRoute(public val isEmbedded: Boolean = false) : Route
