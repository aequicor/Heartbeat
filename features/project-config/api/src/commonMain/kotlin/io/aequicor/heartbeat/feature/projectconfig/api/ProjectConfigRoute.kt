package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Project configuration: proposals from `.heartbeat` folders and bundles, projects and their bound units, saving into
 * a project, export and import. [isEmbedded] marks the route drawn as a section of the settings window.
 */
@Serializable
@SerialName("project-config")
public data class ProjectConfigRoute(public val isEmbedded: Boolean = false) : Route
