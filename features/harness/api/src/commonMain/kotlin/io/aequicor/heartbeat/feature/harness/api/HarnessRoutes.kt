package io.aequicor.heartbeat.feature.harness.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The harness library of the profile: approval level of agent edits and the list of harnesses.
 * [isEmbedded] marks the route drawn as a section of the settings window, where only the content is shown.
 */
@Serializable
@SerialName("harness")
public data class HarnessRoute(public val isEmbedded: Boolean = false) : Route

/** One harness: switch, scope, connected chats, items, tool policy entry and workflow runs. */
@Serializable
@SerialName("harness_detail")
public data class HarnessDetailRoute(public val harness: String) : Route

/** Editor of one item's text or code; an empty [item] never addresses a new item: users do not create items in v1. */
@Serializable
@SerialName("harness_item")
public data class HarnessItemRoute(public val harness: String, public val item: String) : Route

/** Tool policy of one harness: Heartbeat tools that are off and native engine tool switches. */
@Serializable
@SerialName("harness_tools")
public data class HarnessToolsRoute(public val harness: String) : Route
