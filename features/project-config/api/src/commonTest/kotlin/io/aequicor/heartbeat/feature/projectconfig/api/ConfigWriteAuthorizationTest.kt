package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigWriteAuthorizationTest {
    private val project = WorkspaceRef("project")
    private val unit = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val key = ConfigEntityKey("key")
    private val bound = ConfigDigest("bound")
    private val seen = ConfigDigest("seen")
    private val binding = ConfigBinding(project, unit, key, bound, ConfigDigest("entity"))
    private val ready = ProjectConfigState.Ready(bindings = listOf(binding))

    @Test
    fun `keeping the app authorizes only the proposal source version`() {
        val proposal = ConfigProposal(ConfigSource.Project(project), unit, ProposalKind.Conflict, key, seen)
        val state = ready.copy(proposals = listOf(proposal))
        val resolution = checkNotNull(ProjectConfigMachineSpec.resolve(state, Internal.KeepApp(proposal.id, seen)))
        val write = resolution.effects.filterIsInstance<ProjectConfigEffect.WriteBack>().single().writeBacks.single()

        assertEquals(seen, write.expectedDigest)
        val writing = state.copy(writing = setOf(write.copy(expectedDigest = bound)))
        ProjectConfigMachineSpec.assertIgnored(writing, Internal.KeepApp(proposal.id, seen))
    }

    @Test
    fun `explicit deletion authorizes the binding or the visible proposal version`() {
        fun requested(state: ProjectConfigState.Ready): ConfigWriteBack {
            val resolution = checkNotNull(
                ProjectConfigMachineSpec.resolve(state, Internal.RemoveFromProject(project, unit)),
            )
            return resolution.effects.filterIsInstance<ProjectConfigEffect.WriteBack>().single().writeBacks.single()
        }
        assertEquals(bound, requested(ready).expectedDigest)
        val proposal = ConfigProposal(ConfigSource.Project(project), unit, ProposalKind.Update, key, seen)
        assertEquals(seen, requested(ready.copy(proposals = listOf(proposal))).expectedDigest)
        val removal = proposal.copy(kind = ProposalKind.Remove, digest = null)
        assertEquals(null, requested(ready.copy(proposals = listOf(removal))).expectedDigest)
    }
}
