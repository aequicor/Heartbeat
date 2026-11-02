package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.NoAgentTools
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class StudioGraphWorkspaceTest {
    @Test
    fun `graph executor persists its approved borrowed sandbox without creating another workspace`() = runTest {
        val workspace = WorkspaceRef("sandbox-owner")
        val record = StudioChatRecord(
            "graph",
            "Graph",
            Instant.DISTANT_PAST,
            projectId = "project",
            executionWorkspace = workspace,
            identity = StudioSessionIdentity(StudioIdentityOrigin.InternalExecution),
        )
        var saved: StudioChatRecord? = null
        val workspaces = object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe() = flowOf(emptyList<LocalWorkspace>())
            override suspend fun register(directory: String): LocalWorkspace = error("No registration during save")
            override suspend fun resolve(ref: WorkspaceRef): String? = error("Already validated before save")
            override suspend fun virtual(ref: WorkspaceRef): VirtualWorkspace? = error(
                "A graph borrows its approved workspace",
            )
        }
        saveHelperWorkspace(record, workspaces, StudioWorktrees(ConfigurationRegistry(), NoAgentTools)) { saved = it }
        assertEquals(record, saved)
        assertEquals(workspace, saved?.executionWorkspace)
    }
}
