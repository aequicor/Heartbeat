package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConfigWriteBackFailureTest {
    private val spec = ProjectConfigMachineSpec
    private val project = WorkspaceRef("p")
    private val unit = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val binding = ConfigBinding(project, unit, ConfigEntityKey("h"), ConfigDigest("f1"), ConfigDigest("e1"))
    private val proposal = ConfigProposal(
        ConfigSource.Project(project),
        unit,
        ProposalKind.Conflict,
        binding.key,
        ConfigDigest("f2"),
    )
    private val ready = ProjectConfigState.Ready(
        bindings = listOf(binding),
        projects = mapOf(project to ProjectSync(1)),
        proposals = listOf(proposal),
    )
    private val write = ConfigWriteBack(project, unit, binding.key, proposal.digest)

    @Test
    fun `keeping the app version waits for a successful write and a failure leaves a visible retry`() {
        val keep = Internal.KeepApp(proposal.id, proposal.digest)
        val writing = ready.copy(writing = setOf(write))
        spec.assertTransition(ready, keep, writing, effects = listOf(ProjectConfigEffect.WriteBack(listOf(write))))
        spec.assertTransition(
            writing,
            Internal.WriteBackFailed(listOf(write)),
            ready,
            outputs = listOf(ProjectConfigOutput.WriteBackFailed),
        )
        // No automatic IO retry is started by the failure. The same action remains available to the user.
        spec.assertTransition(ready, keep, writing, effects = listOf(ProjectConfigEffect.WriteBack(listOf(write))))
        val saved = binding.copy(fileDigest = ConfigDigest("e2"), entityDigest = ConfigDigest("e2"))
        val completed = checkNotNull(spec.resolve(writing, Internal.WrittenBack(write, saved)))
        val state = completed.to as ProjectConfigState.Ready
        assertTrue(state.proposals.isEmpty())
        assertTrue(state.writing.isEmpty())
        assertEquals(listOf(saved), state.bindings)
    }

    @Test
    fun `a proposal being written cannot be trusted dismissed or written again`() {
        val writing = ready.copy(writing = setOf(write))
        spec.assertIgnored(writing, Internal.Trust(proposal.id, proposal.digest))
        spec.assertIgnored(writing, Internal.KeepApp(proposal.id, proposal.digest))
        spec.assertIgnored(writing, Public.Dismiss(proposal.id, proposal.digest))
        spec.assertTransition(writing, Internal.RemoveFromProject(project, unit), writing)
    }

    @Test
    fun `a failed explicit removal reports the failure and keeps the binding for retry`() {
        val idle = ready.copy(proposals = emptyList())
        val deletion = ConfigWriteBack(project, unit, null, binding.fileDigest, isUnbinding = true)
        val deleting = idle.copy(writing = setOf(deletion))
        spec.assertTransition(
            idle,
            Internal.RemoveFromProject(project, unit),
            deleting,
            effects = listOf(ProjectConfigEffect.WriteBack(listOf(deletion))),
        )
        spec.assertTransition(
            deleting,
            Internal.WriteBackFailed(listOf(deletion)),
            idle,
            outputs = listOf(ProjectConfigOutput.WriteBackFailed),
        )
    }

    @Test
    fun `a write conflict releases the unit and syncs its current files without an IO failure notice`() {
        spec.assertTransition(
            ready.copy(writing = setOf(write)),
            Internal.WriteBackConflict(write),
            ready.copy(projects = mapOf(project to ProjectSync(2, isSyncing = true))),
            effects = listOf(ProjectConfigEffect.Sync(listOf(SyncRequest(project, 2, listOf(binding))))),
        )
    }
}
