package io.aequicor.heartbeat.feature.searchengine.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Profile settings, initially containing web search and resource retrieval connections. */
@Serializable
@SerialName("profile-settings")
public data object ProfileSettingsRoute : Route
