package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.LocalWorkspaces
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualFileChange
import io.aequicor.heartbeat.feature.aiengine.facade.api.VirtualWorkspace
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Instant

class StudioExecutionWorkspaceTest {
    @Test
    fun `virtual sandbox opens with its opaque identity and rechecks availability`() = runTest {
        val workspaces = ExecutionWorkspaces()
        val sandbox = WorkspaceRef("sandbox-chat")
        val record = chat().copy(executionWorkspace = sandbox, worktreeTaskId = "chat")

        assertEquals(sandbox, record.validatedExecutionWorkspace(workspaces, true))
        assertEquals(1, workspaces.revisionChecks)
        assertEquals(emptyList(), workspaces.resolved)
    }

    @Test
    fun `physical worktree takes precedence over the source project`() = runTest {
        val workspaces = ExecutionWorkspaces()
        val checkout = WorkspaceRef("checkout")
        val record = chat().copy(executionWorkspace = checkout, worktreeTaskId = "chat")

        assertEquals(checkout, record.validatedExecutionWorkspace(workspaces, true))
        assertEquals(listOf(checkout), workspaces.resolved)
    }

    @Test
    fun `ordinary project opens but a missing project is rejected`() = runTest {
        val workspaces = ExecutionWorkspaces()
        assertEquals(WorkspaceRef("project"), chat().validatedExecutionWorkspace(workspaces, true))
        assertFailsWith<IllegalStateException> {
            chat().copy(projectId = "missing").validatedExecutionWorkspace(workspaces, true)
        }
    }

    @Test
    fun `unavailable virtual sandbox never falls back to a physical directory`() = runTest {
        val workspaces = ExecutionWorkspaces()
        assertFailsWith<IllegalStateException> {
            chat().copy(executionWorkspace = WorkspaceRef("sandbox-missing"))
                .validatedExecutionWorkspace(workspaces, true)
        }
        workspaces.revisionFailure = IllegalStateException("SandboxPaused")
        assertFailsWith<IllegalStateException> {
            chat().copy(executionWorkspace = WorkspaceRef("sandbox-chat"))
                .validatedExecutionWorkspace(workspaces, true)
        }
        assertEquals(emptyList(), workspaces.resolved)
    }

    @Test
    fun `sandbox availability cancellation propagates`() = runTest {
        val workspaces = ExecutionWorkspaces().apply { revisionFailure = CancellationException("Cancelled") }
        assertFailsWith<CancellationException> {
            chat().copy(executionWorkspace = WorkspaceRef("sandbox-chat"))
                .validatedExecutionWorkspace(workspaces, true)
        }
    }

    @Test
    fun `unprepared worktree and unsupported model are rejected before directory access`() = runTest {
        val workspaces = ExecutionWorkspaces()
        assertFailsWith<IllegalStateException> {
            chat().copy(worktreeTaskId = "chat").validatedExecutionWorkspace(workspaces, true)
        }
        assertFailsWith<IllegalStateException> {
            chat().validatedExecutionWorkspace(workspaces, false)
        }
        assertEquals(emptyList(), workspaces.resolved)
    }

    @Test
    fun `chat without a project needs no local workspace support`() = runTest {
        val workspaces = ExecutionWorkspaces()
        assertNull(chat().copy(projectId = null).validatedExecutionWorkspace(workspaces, false))
        assertEquals(emptyList(), workspaces.resolved)
    }

    private fun chat() = StudioChatRecord("chat", "Chat", Instant.fromEpochSeconds(0), projectId = "project")
}

private class ExecutionWorkspaces : LocalWorkspaces {
    val resolved = mutableListOf<WorkspaceRef>()
    var revisionChecks = 0
    var revisionFailure: Exception? = null
    override val isAvailable = true

    override fun observe() = flowOf(emptyList<LocalWorkspace>())

    override suspend fun register(directory: String): LocalWorkspace = error("unused")

    override suspend fun resolve(ref: WorkspaceRef): String? {
        resolved += ref
        return "/physical".takeIf { ref.value == "project" || ref.value == "checkout" }
    }

    override suspend fun virtual(ref: WorkspaceRef): VirtualWorkspace? {
        if (ref.value != "sandbox-chat") return null
        return object : VirtualWorkspace {
            override val ref = ref
            override val processDirectory = "/empty-host-directory"

            override suspend fun revision(): Long {
                revisionChecks++
                revisionFailure?.let { throw it }
                return 0L
            }

            override suspend fun paths(): List<String> = error("unused")

            override suspend fun read(path: String): String? = error("unused")

            override suspend fun change(expectedRevision: Long, changes: List<VirtualFileChange>): Long =
                error("unused")

            override suspend fun move(expectedRevision: Long, path: String, destination: String): Long = error("unused")
        }
    }
}
