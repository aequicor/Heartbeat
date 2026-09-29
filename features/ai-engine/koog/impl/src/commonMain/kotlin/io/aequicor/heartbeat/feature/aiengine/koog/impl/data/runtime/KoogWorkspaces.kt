package io.aequicor.heartbeat.feature.aiengine.koog.impl.data.runtime

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** Coding context of a session opened on a local project: its tools and the instructions describing them. */
internal class KoogWorkspace(val tools: List<KoogTool>, val instructions: String)

/** Opens coding workspaces; Desktop only, other platforms keep the plain chat. */
internal fun interface KoogWorkspaces {
    /** Workspace of [ref], or null when the platform has no coding tools or the project is unavailable. */
    suspend fun open(ref: WorkspaceRef): KoogWorkspace?
}
