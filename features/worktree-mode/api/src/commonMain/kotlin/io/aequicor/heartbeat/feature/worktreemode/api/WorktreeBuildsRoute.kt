package io.aequicor.heartbeat.feature.worktreemode.api

import io.aequicor.heartbeat.core.navigation.Route
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Build queue settings: the machine-wide limit of parallel builds (automatic by CPU and memory, or a user value).
 * [isEmbedded] hides the screen's own header when the settings window hosts it as a section.
 */
@Serializable
@SerialName("worktree-builds")
public data class WorktreeBuildsRoute(public val isEmbedded: Boolean = false) : Route
