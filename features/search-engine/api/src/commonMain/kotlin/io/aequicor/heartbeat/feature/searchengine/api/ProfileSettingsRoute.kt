package io.aequicor.heartbeat.feature.searchengine.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Profile search settings: web search and resource retrieval connections. [isEmbedded] marks the route shown as
 * a section of the settings window, where only the content is drawn; elsewhere the screen has its own header.
 */
@Serializable
@SerialName("profile-settings")
public data class ProfileSettingsRoute(val isEmbedded: Boolean = false) : Route
