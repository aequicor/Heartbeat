package io.aequicor.heartbeat.feature.autocomplete.impl.data

import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import io.aequicor.heartbeat.core.di.ProfileScope

/** Android has no local project workspaces, so the file section of the completion stays empty. */
@Inject
@ContributesBinding(ProfileScope::class)
internal class UnsupportedProjectFileIndex : ProjectFileIndex {
    override suspend fun search(
        workspace: io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef?,
        query: String,
        limit: Int,
    ) = emptyList<ProjectFile>()
}
