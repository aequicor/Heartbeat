package io.aequicor.heartbeat.feature.chromiumbridge.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chromium browser settings: bridge status, host registration per browser, the extension to load and the tabs
 * agents drive. [isEmbedded] marks the route drawn as a section of the settings window, where only content is shown.
 */
@Serializable
@SerialName("chromium-bridge")
public data class ChromiumBridgeRoute(public val isEmbedded: Boolean = false) : Route
