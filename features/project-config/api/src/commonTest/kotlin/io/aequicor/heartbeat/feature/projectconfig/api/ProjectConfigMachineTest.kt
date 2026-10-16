package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.core.statemachine.assertIgnored
import io.aequicor.heartbeat.core.statemachine.assertTransition
import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectConfigMachineTest {
    private val spec = ProjectConfigMachineSpec
    private val project = WorkspaceRef("p")
    private val review = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val key = ConfigEntityKey("h_review")
    private val binding = ConfigBinding(project, review, key, ConfigDigest("f1"), ConfigDigest("e1"))
    private val idle = ProjectConfigState.Ready(bindings = listOf(binding), projects = mapOf(project to ProjectSync(1)))

    @Test
    fun `enabling loads bindings and syncs every project, a failed load waits for reload`() {
        spec.assertTransition(
            ProjectConfigState.Off,
            Internal.Enabled(setOf(project)),
            ProjectConfigState.Loading(setOf(project)),
            effects = listOf(ProjectConfigEffect.Load),
        )
        spec.assertTransition(
            ProjectConfigState.Loading(setOf(project)),
            Internal.Loaded(listOf(binding)),
            ProjectConfigState.Ready(listOf(binding), mapOf(project to ProjectSync(1, isSyncing = true))),
            effects = listOf(ProjectConfigEffect.Sync(listOf(SyncRequest(project, 1, listOf(binding))))),
        )
        spec.assertTransition(
            ProjectConfigState.Loading(setOf(project)),
            Internal.LoadFailed,
            ProjectConfigState.Failed(setOf(project)),
            outputs = listOf(ProjectConfigOutput.StorageFailed),
        )
        spec.assertTransition(
            ProjectConfigState.Failed(setOf(project)),
            Public.Reload,
            ProjectConfigState.Loading(setOf(project)),
            effects = listOf(ProjectConfigEffect.Load),
        )
        spec.assertTransition(idle, Internal.Disabled, ProjectConfigState.Off)
        spec.assertIgnored(ProjectConfigState.Off, Public.Sync(project))
    }

    @Test
    fun `a change during a sync marks the project dirty and the result syncs it again`() {
        val syncing = idle.send(Public.Sync(project))
        assertEquals(ProjectSync(2, isSyncing = true), syncing.projects[project])
        val dirty = syncing.send(Internal.Changed(project))
        assertTrue(dirty.projects.getValue(project).isDirty)

        spec.assertIgnored(dirty, Internal.Synced(snapshot(1, unit("f1"))))
        val resolution = checkNotNull(spec.resolve(dirty, Internal.Synced(snapshot(2, unit("f1")), hasFolder = true)))
        assertEquals(ProjectSync(3, isSyncing = true, hasFolder = true), resolution.to.ready.projects[project])
        assertEquals(
            listOf(ProjectConfigEffect.Sync(listOf(SyncRequest(project, 3, listOf(binding))))),
            resolution.effects,
        )
        spec.assertIgnored(idle, Public.Sync(WorkspaceRef("unknown")))
    }

    @Test
    fun `a new unit is reviewed, trusted only when safe or accepted, and its binding is stored`() {
        val learning = ConfigUnitRef(ConfigSectionId.Learning, "style")
        val scanned = ScannedUnit(learning, ConfigDigest("n1"), valid("c1"))
        val syncing = idle.send(Public.Sync(project))
        val synced = checkNotNull(spec.resolve(syncing, Internal.Synced(snapshot(2, unit("f1"), scanned))))
        val proposal = synced.to.ready.proposals.single()
        assertEquals(ProposalKind.Create, proposal.kind)
        assertEquals(listOf(ProjectConfigEffect.Review(listOf(proposal))), synced.effects)

        val risky = synced.to.ready.send(
            Internal.Reviewed(proposal.id, ConfigDigest("n1"), ProposalReview.Done(ReviewVerdict.Risk, "network")),
        )
        assertTrue(risky.reviewing.isEmpty())
        spec.assertIgnored(risky, Public.Trust(proposal.id))
        val applying = checkNotNull(spec.resolve(risky, Public.Trust(proposal.id, isRiskAccepted = true)))
        val started = applying.to.ready.proposals.single()
        assertTrue(started.isApplying)
        assertEquals(listOf(ProjectConfigEffect.Apply(started)), applying.effects)

        val learned = ConfigBinding(project, learning, ConfigEntityKey("l1"), ConfigDigest("n1"), ConfigDigest("c1"))
        val applied = checkNotNull(spec.resolve(applying.to.ready, Internal.Applied(proposal.id, learned)))
        assertTrue(applied.to.ready.proposals.isEmpty())
        assertEquals(listOf(binding, learned), applied.to.ready.bindings)
        assertEquals(
            listOf(ProjectConfigEffect.SaveBindings(listOf(binding, learned), 1)),
            applied.effects,
        )
        val failed = applying.to.ready.send(Internal.ApplyFailed(proposal.id, ConfigRejection.Duplicate))
        assertEquals(ConfigRejection.Duplicate, failed.proposals.single().failure)
    }

    @Test
    fun `entity changes are written back once and commit the new binding`() {
        val syncing = idle.send(Public.Sync(project))
        val changed = snapshot(
            2,
            unit("f1"),
            entity = EntityDigest(ConfigSectionId.Harnesses, key, "review", ConfigDigest("e2")),
        )
        val writing = checkNotNull(spec.resolve(syncing, Internal.Synced(changed)))
        val writeBack = ConfigWriteBack(project, review, key)
        assertEquals(listOf(ProjectConfigEffect.WriteBack(listOf(writeBack))), writing.effects)

        val again = writing.to.ready.send(Public.Sync(project))
        val repeated = checkNotNull(spec.resolve(again, Internal.Synced(changed.copy(generation = 3))))
        assertTrue(repeated.effects.isEmpty())

        val written = binding.copy(fileDigest = ConfigDigest("f2"), entityDigest = ConfigDigest("e2"))
        spec.assertTransition(
            writing.to.ready,
            Internal.WrittenBack(writeBack, written),
            writing.to.ready.copy(bindings = listOf(written), writing = emptySet(), revision = 1),
            effects = listOf(ProjectConfigEffect.SaveBindings(listOf(written), 1)),
        )
        val removal = writing.to.ready.send(Internal.WrittenBack(writeBack, null))
        assertTrue(removal.bindings.isEmpty())
    }

    @Test
    fun `a conflict can keep the app version and a dismissed proposal stays hidden until it changes`() {
        val conflict = ConfigProposal(
            ConfigSource.Project(project),
            review,
            ProposalKind.Conflict,
            key,
            ConfigDigest("f2"),
        )
        val state = idle.copy(proposals = listOf(conflict))
        spec.assertTransition(
            state,
            Public.KeepApp(conflict.id),
            idle.copy(writing = setOf(ConfigWriteBack(project, review, key))),
            effects = listOf(ProjectConfigEffect.WriteBack(listOf(ConfigWriteBack(project, review, key)))),
        )

        val dismissed = state.send(Public.Dismiss(conflict.id))
        assertEquals(setOf(conflict.reviewKey), dismissed.dismissed)
        val hidden = dismissed.send(Public.Sync(project)).send(
            Internal.Synced(
                snapshot(
                    2,
                    unit("f2", "c2"),
                    entity = EntityDigest(ConfigSectionId.Harnesses, key, "review", ConfigDigest("e2")),
                ),
            ),
        )
        assertTrue(hidden.proposals.isEmpty())
        spec.assertIgnored(idle, Public.KeepApp("missing"))
        spec.assertTransition(
            idle,
            Public.RemoveFromProject(project, review),
            idle.copy(writing = setOf(ConfigWriteBack(project, review, null))),
            effects = listOf(ProjectConfigEffect.WriteBack(listOf(ConfigWriteBack(project, review, null)))),
        )
    }

    @Test
    fun `one task runs at a time and a finished one syncs its project`() {
        val save = Public.SaveToProject("r1", project, listOf(ConfigEntityRef(ConfigSectionId.Harnesses, key)))
        val task = ConfigTask("r1", ConfigTaskKind.SaveToProject, project)
        spec.assertTransition(
            idle,
            save,
            idle.copy(task = task),
            effects = listOf(ProjectConfigEffect.SaveToProject("r1", project, save.entities)),
        )
        spec.assertTransition(
            idle.copy(task = task),
            Public.Export("r2", "/tmp/a.hbai", save.entities),
            idle.copy(task = task),
            outputs = listOf(ProjectConfigOutput.TaskFinished("r2", ConfigTaskResult.Failed(ConfigTaskFailure.Busy))),
        )
        val saved = binding.copy(unit = ConfigUnitRef(ConfigSectionId.Harnesses, "audit"))
        val done = checkNotNull(
            spec.resolve(idle.copy(task = task), Internal.TaskDone("r1", ConfigTaskResult.Saved(1, 0), listOf(saved))),
        )
        assertEquals(null, done.to.ready.task)
        assertEquals(listOf(binding, saved), done.to.ready.bindings)
        assertEquals(
            listOf(
                ProjectConfigEffect.SaveBindings(listOf(binding, saved), 1),
                ProjectConfigEffect.Sync(listOf(SyncRequest(project, 2, listOf(binding, saved)))),
            ),
            done.effects,
        )
        assertEquals(listOf(ProjectConfigOutput.TaskFinished("r1", ConfigTaskResult.Saved(1, 0))), done.outputs)
        spec.assertIgnored(idle, Internal.TaskDone("stale", ConfigTaskResult.Exported(1)))
    }

    @Test
    fun `an opened bundle replaces the previous one and is reviewed, closing drops its proposals`() {
        val previous = ConfigBundle("old", "old.hbai")
        val incoming = ConfigProposal(ConfigSource.Bundle("r1"), review, ProposalKind.Create, null, ConfigDigest("b1"))
        val state = idle.copy(bundle = previous, task = ConfigTask("r1", ConfigTaskKind.OpenBundle))
        val bundle = ConfigBundle("r1", "new.hbai")
        spec.assertTransition(
            state,
            Internal.BundleOpened("r1", bundle, listOf(incoming)),
            idle.copy(bundle = bundle, proposals = listOf(incoming), reviewing = setOf(incoming.reviewKey)),
            effects = listOf(ProjectConfigEffect.CloseBundle("old"), ProjectConfigEffect.Review(listOf(incoming))),
            outputs = listOf(ProjectConfigOutput.TaskFinished("r1", ConfigTaskResult.Opened(1))),
        )
        spec.assertTransition(
            idle.copy(bundle = bundle, proposals = listOf(incoming)),
            Public.CloseBundle,
            idle,
            effects = listOf(ProjectConfigEffect.CloseBundle("r1")),
        )
    }

    @Test
    fun `removed projects lose their proposals and effect failures map to results`() {
        val proposal = ConfigProposal(ConfigSource.Project(project), review, ProposalKind.Remove, key, null)
        val other = WorkspaceRef("q")
        spec.assertTransition(
            idle.copy(proposals = listOf(proposal)),
            Internal.ProjectsChanged(setOf(other)),
            idle.copy(projects = mapOf(other to ProjectSync(1, isSyncing = true))),
            effects = listOf(ProjectConfigEffect.Sync(listOf(SyncRequest(other, 1, emptyList())))),
        )
        val request = SyncRequest(project, 1, emptyList())
        assertEquals(
            Internal.SyncFailed(listOf(request), ConfigReadFailure.Io),
            spec.onEffectFailure(ProjectConfigEffect.Sync(listOf(request)), IllegalStateException()),
        )
        assertEquals(
            Internal.TaskDone("r", ConfigTaskResult.Failed(ConfigTaskFailure.Unavailable)),
            spec.onEffectFailure(ProjectConfigEffect.Export("r", "x", emptyList()), IllegalStateException()),
        )
        val failed = idle.send(
            Public.Sync(project),
        ).send(Internal.SyncFailed(listOf(SyncRequest(project, 2, emptyList())), ConfigReadFailure.Limit))
        assertEquals(ProjectSync(2, failure = ConfigReadFailure.Limit), failed.projects[project])
    }

    private val ProjectConfigState.ready: ProjectConfigState.Ready get() = this as ProjectConfigState.Ready

    private fun ProjectConfigState.Ready.send(intent: ProjectConfigIntent): ProjectConfigState.Ready =
        checkNotNull(spec.resolve(this, intent)).to.ready

    private fun snapshot(generation: Long, vararg units: ScannedUnit, entity: EntityDigest? = null) = ProjectSnapshot(
        project,
        generation,
        units.toList(),
        listOf(entity ?: EntityDigest(ConfigSectionId.Harnesses, key, "review", ConfigDigest("e1"))),
    )

    private fun unit(files: String, canonical: String? = null) =
        ScannedUnit(review, ConfigDigest(files), canonical?.let(::valid) ?: UnitCheck.Unchanged)

    private fun valid(canonical: String) = UnitCheck.Valid(ConfigSummary("t"), "style", ConfigDigest(canonical))
}
