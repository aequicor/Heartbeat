package io.aequicor.heartbeat.feature.autocomplete.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** A project file offered by the `@`-mention completion; [location] is transient and never persisted. */
internal data class ProjectFile(
    val relativePath: String,
    val location: String,
    val sizeBytes: Long,
    val mediaType: String?,
) {
    override fun toString(): String = "ProjectFile(path=$relativePath, size=$sizeBytes)"
}

/**
 * Bounded file listing of the pane's workspace. Implementations cache their walk, never follow symbolic
 * links and never leave the workspace root; a platform without local project access always answers empty.
 */
internal interface ProjectFileIndex {
    /** Ranked files of [workspace] for the typed [query]; empty when there is no workspace or no match. */
    suspend fun search(workspace: WorkspaceRef?, query: String, limit: Int): List<ProjectFile>
}
