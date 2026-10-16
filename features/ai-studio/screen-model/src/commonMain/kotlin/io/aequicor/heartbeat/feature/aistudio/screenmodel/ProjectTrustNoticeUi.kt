package io.aequicor.heartbeat.feature.aistudio.screenmodel

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable

/**
 * Asks whether the project [projectId] of the focused chat may load what it configures for the engine [engine]
 * (settings, tool servers, extensions). Until decided, only the project's instructions load.
 */
@Serializable
@Immutable
data class ProjectTrustNoticeUi(
    val projectId: String,
    val engine: String,
    val projectTitle: String,
    val engineTitle: String,
)
