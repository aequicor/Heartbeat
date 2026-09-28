package io.aequicor.heartbeat.feature.aiengine.pi.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineException
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PiWorkspaceTest {
    @Test
    fun `process working directory comes from the shared registry with legacy configuration fallback`() = runTest {
        val project = WorkspaceRef("project")
        val legacy = WorkspaceRef("legacy")
        val workspaces = object : LocalWorkspaces {
            override val isAvailable = true
            override fun observe(): Flow<List<LocalWorkspace>> = flowOf(emptyList())
            override suspend fun register(directory: String): LocalWorkspace = error("Not used")
            override suspend fun resolve(ref: WorkspaceRef): String? = "/projects/Heartbeat".takeIf { ref == project }
        }
        val configured = mapOf(project.value to "/old", legacy.value to "/legacy")
        assertEquals("/projects/Heartbeat", resolvePiWorkspace(project, workspaces, configured))
        assertEquals("/legacy", resolvePiWorkspace(legacy, workspaces, configured))
        assertNull(resolvePiWorkspace(null, workspaces, configured))
        assertFailsWith<EngineException> { resolvePiWorkspace(WorkspaceRef("unknown"), workspaces, configured) }
    }
}
