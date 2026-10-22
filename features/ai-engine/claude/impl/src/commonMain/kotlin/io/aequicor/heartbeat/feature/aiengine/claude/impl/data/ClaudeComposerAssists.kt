package io.aequicor.heartbeat.feature.aiengine.claude.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineAssist
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ListsComposerAssists
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef

/** The same isolated project catalog feeds suggestions and the next CLI operation. */
internal interface ClaudeComposerAssists : ListsComposerAssists {
    suspend fun prepare(workspace: WorkspaceRef?): ClaudePreparedAssists?

    companion object {
        val None: ClaudeComposerAssists = object : ClaudeComposerAssists {
            override suspend fun assists(target: EngineTarget, workspace: WorkspaceRef?): List<EngineAssist> =
                emptyList()
            override suspend fun prepare(workspace: WorkspaceRef?): ClaudePreparedAssists? = null
        }
    }
}

/** Operation-owned plugin; disposed when the CLI operation ends. */
internal interface ClaudePreparedAssists : AutoCloseable {
    val directory: String
}
