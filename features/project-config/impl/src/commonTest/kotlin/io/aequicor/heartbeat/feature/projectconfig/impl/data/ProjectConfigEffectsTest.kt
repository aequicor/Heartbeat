package io.aequicor.heartbeat.feature.projectconfig.impl.data

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityKey
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigEntityRef
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigReadFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigRejection
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigSectionId
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskFailure
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigTaskResult
import io.aequicor.heartbeat.feature.projectconfig.api.ConfigUnitRef
import io.aequicor.heartbeat.feature.projectconfig.api.ExistingUnits
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigEffect
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Internal
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigIntent.Public
import io.aequicor.heartbeat.feature.projectconfig.api.ProjectConfigOutput
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalKind
import io.aequicor.heartbeat.feature.projectconfig.api.ProposalReview
import io.aequicor.heartbeat.feature.projectconfig.api.ReviewVerdict
import io.aequicor.heartbeat.feature.projectconfig.api.SyncRequest
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeEntity
import io.aequicor.heartbeat.feature.projectconfig.impl.domain.FakeSection
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectConfigEffectsTest {
    private val harnesses = FakeSection()
    private val interfaces = FakeSection(ConfigSectionId.Interfaces, parent = ConfigSectionId.Harnesses)
    private val learning = FakeSection(ConfigSectionId.Learning, isProjectScoped = true)
    private val fixture = EffectsFixture(harnesses, interfaces, learning)

    @Test
    fun `a project unit is reviewed and trusted into the profile and kept in sync both ways`() = runTest {
        fixture.files["harnesses/review.md"] = "rules\n"
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.run(Internal.ProjectsChanged(setOf(PROJECT)))

        val proposal = fixture.ready.proposals.single()
        assertEquals(ProposalKind.Create, proposal.kind)
        assertEquals(ProposalReview.Done(ReviewVerdict.Safe, "because"), proposal.review)
        val (policy, change) = fixture.reviewer.changes.single()
        assertTrue("fake policy" in policy && "rules" in change)

        fixture.run(Internal.Trust(proposal.id, proposal.digest))
        assertEquals(PROJECT, harnesses.applied.single().second.project)
        assertEquals(listOf("rules"), harnesses.state.value.orEmpty().map { it.text })
        assertEquals(1, fixture.storage.bindings.size)
        assertTrue(fixture.ready.proposals.isEmpty())

        // Changed in the app: written back into the folder without asking.
        harnesses.state.value = listOf(FakeEntity("k-review", "review", "rules v2", revision = 1))
        fixture.run(Internal.Changed(PROJECT))
        assertEquals("rules v2\n", fixture.files["harnesses/review.md"])
        assertTrue(fixture.ready.proposals.isEmpty())

        // Changed in the folder: a new proposal, applied only after trust.
        fixture.files["harnesses/review.md"] = "rules v3\n"
        fixture.run(Internal.Changed(PROJECT))
        val update = fixture.ready.proposals.single()
        assertEquals(ProposalKind.Update, update.kind)
        assertEquals("rules v2", harnesses.state.value.orEmpty().single().text)
        fixture.run(Internal.Trust(update.id, update.digest))
        assertEquals("rules v3", harnesses.state.value.orEmpty().single().text)

        // Removed from the folder: removal is confirmed without review.
        fixture.files.remove("harnesses/review.md")
        fixture.run(Internal.Changed(PROJECT))
        val removal = fixture.ready.proposals.single()
        assertEquals(ProposalReview.NotNeeded, removal.review)
        fixture.run(Internal.Trust(removal.id, removal.digest))
        assertTrue(harnesses.state.value.orEmpty().isEmpty())
        assertTrue(fixture.storage.bindings.isEmpty())
    }

    @Test
    fun `risky units need accepted risk and units without a default model stay unreviewed`() = runTest {
        fixture.reviewer.verdict = "risk"
        fixture.files["harnesses/review.md"] = "rules\n"
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.run(Internal.ProjectsChanged(setOf(PROJECT)))
        val risky = fixture.ready.proposals.single()
        assertEquals(ReviewVerdict.Risk, (risky.review as ProposalReview.Done).verdict)
        fixture.run(Internal.Trust(risky.id, risky.digest))
        assertTrue(harnesses.applied.isEmpty())
        fixture.run(Internal.Trust(risky.id, risky.digest, isRiskAccepted = true))
        assertEquals(1, harnesses.applied.size)

        fixture.selections.selection.value = fixture.selections.selection.value.withDefault(null)
        fixture.files["harnesses/audit.md"] = "audit\n"
        fixture.run(Internal.Changed(PROJECT))
        assertEquals(ProposalReview.Unavailable, fixture.ready.proposals.single().review)
    }

    @Test
    fun `a unit changed after its review is not applied and a child needs its parent`() = runTest {
        fixture.files["harnesses/review.md"] = "rules\n"
        fixture.files["interfaces/review-board.md"] = "board\n"
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.run(Internal.ProjectsChanged(setOf(PROJECT)))
        val harness = fixture.ready.proposals.single { it.unit.section == ConfigSectionId.Harnesses }
        val board = fixture.ready.proposals.single { it.unit.section == ConfigSectionId.Interfaces }

        assertEquals(
            listOf(Internal.ApplyFailed(board.id, ConfigRejection.MissingParent)),
            fixture.results(ProjectConfigEffect.Apply(board)),
        )
        fixture.files["harnesses/review.md"] = "changed\n"
        assertEquals(
            listOf(Internal.ApplyFailed(harness.id, ConfigRejection.Conflict)),
            fixture.results(ProjectConfigEffect.Apply(harness)),
        )
        fixture.files["harnesses/review.md"] = "rules\n"
        fixture.run(Internal.Trust(harness.id, harness.digest))
        fixture.run(Internal.Trust(board.id, board.digest))
        assertEquals(ConfigEntityKey("k-review"), interfaces.applied.single().second.parent)
    }

    @Test
    fun `entities are saved into a project while skipping existing units and moving lessons there`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h1", "review", "rules"), FakeEntity("h2", "taken", "x"))
        learning.state.value = listOf(FakeEntity("l1", "style", "tabs"))
        fixture.files["harnesses/taken.md"] = "theirs\n"
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.run(Internal.ProjectsChanged(setOf(PROJECT)))
        val refs = listOf(
            ConfigEntityRef(ConfigSectionId.Harnesses, ConfigEntityKey("h1")),
            ConfigEntityRef(ConfigSectionId.Harnesses, ConfigEntityKey("h2")),
            ConfigEntityRef(ConfigSectionId.Learning, ConfigEntityKey("l1")),
        )

        fixture.run(Internal.SaveToProject("r1", PROJECT, refs))

        assertEquals(ProjectConfigOutput.TaskFinished("r1", ConfigTaskResult.Saved(2, 1)), fixture.outputs.last())
        assertEquals("rules\n", fixture.files["harnesses/review.md"])
        assertEquals("theirs\n", fixture.files["harnesses/taken.md"])
        assertEquals(PROJECT, learning.state.value.orEmpty().single().project)
        assertEquals(2, fixture.ready.bindings.size)
    }

    @Test
    fun `bundles export and open as proposals for the profile and import into a project`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h1", "review", "rules"))
        learning.state.value = listOf(FakeEntity("l1", "style", "tabs", PROJECT))
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        val refs = listOf(
            ConfigEntityRef(ConfigSectionId.Harnesses, ConfigEntityKey("h1")),
            ConfigEntityRef(ConfigSectionId.Learning, ConfigEntityKey("l1")),
        )
        // Only a bundle file is written: an export never replaces another file.
        fixture.run(Internal.Export("x", "/tmp/notes.txt", refs))
        assertEquals(
            ProjectConfigOutput.TaskFinished("x", ConfigTaskResult.Failed(ConfigTaskFailure.Unwritable)),
            fixture.outputs.last(),
        )
        fixture.run(Internal.Export("e", "/tmp/out.hbai", refs))
        assertEquals(ProjectConfigOutput.TaskFinished("e", ConfigTaskResult.Exported(2)), fixture.outputs.last())

        harnesses.state.value = emptyList()
        fixture.run(Public.OpenBundle("o", "/tmp/out.hbai"))
        assertEquals("out.hbai", fixture.ready.bundle?.name)
        // The lesson belongs to a project, so for the profile it is a new one; the harness is gone and created again.
        assertEquals(listOf(ProposalKind.Create, ProposalKind.Create), fixture.ready.proposals.map { it.kind })
        val lesson = fixture.ready.proposals.single { it.unit.section == ConfigSectionId.Learning }
        fixture.run(Internal.Trust(lesson.id, lesson.digest))
        assertNull(learning.applied.single().second.project)
        fixture.run(Public.CloseBundle)
        assertTrue(fixture.ready.proposals.isEmpty())

        fixture.run(Internal.ImportToProject("i", "/tmp/out.hbai", PROJECT))
        // The project lesson was already saved automatically before the bundle was imported.
        assertEquals(ProjectConfigOutput.TaskFinished("i", ConfigTaskResult.Imported(1, 1)), fixture.outputs.last())
        assertEquals("rules\n", fixture.files["harnesses/review.md"])
    }

    @Test
    fun `an import asks before replacing different project units and then overwrites or skips`() = runTest {
        harnesses.state.value = listOf(FakeEntity("h1", "review", "rules"), FakeEntity("h2", "audit", "checks"))
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        val refs = listOf("h1", "h2").map { ConfigEntityRef(ConfigSectionId.Harnesses, ConfigEntityKey(it)) }
        fixture.run(Internal.Export("e", "/tmp/out.hbai", refs))
        fixture.files["harnesses/review.md"] = "mine\n"
        // The same files are already there: nothing to ask about.
        fixture.files["harnesses/audit.md"] = "checks\n"

        fixture.run(Internal.ImportToProject("i1", "/tmp/out.hbai", PROJECT))
        val conflicts = ConfigTaskResult.ImportConflicts(listOf(ConfigUnitRef(ConfigSectionId.Harnesses, "review")))
        assertEquals(ProjectConfigOutput.TaskFinished("i1", conflicts), fixture.outputs.last())
        assertEquals("mine\n", fixture.files["harnesses/review.md"])

        fixture.run(Internal.ImportToProject("i2", "/tmp/out.hbai", PROJECT, ExistingUnits.Skip))
        assertEquals(ProjectConfigOutput.TaskFinished("i2", ConfigTaskResult.Imported(0, 2)), fixture.outputs.last())
        assertEquals("mine\n", fixture.files["harnesses/review.md"])

        fixture.run(Internal.ImportToProject("i3", "/tmp/out.hbai", PROJECT, ExistingUnits.Overwrite))
        assertEquals(ProjectConfigOutput.TaskFinished("i3", ConfigTaskResult.Imported(1, 1)), fixture.outputs.last())
        assertEquals("rules\n", fixture.files["harnesses/review.md"])
    }

    @Test
    fun `reviews and applications take units from the read of an unchanged folder`() = runTest {
        fixture.files["harnesses/review.md"] = "rules\n"
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        val proposal = fixture.ready.proposals.single()
        val reads = fixture.folders.reads

        assertEquals("rules\n", fixture.access.unit(proposal)?.files?.single()?.text)
        assertEquals(reads, fixture.folders.reads)
        // Changed since: the folder is read again and the reviewed unit is gone.
        fixture.files["harnesses/review.md"] = "other\n"
        assertNull(fixture.access.unit(proposal))
        assertEquals(reads + 1, fixture.folders.reads)
    }

    @Test
    fun `unknown projects fail to sync and sections that are off neither propose nor write back`() = runTest {
        val unknown = SyncRequest(WorkspaceRef("gone"), 1, emptyList())
        assertEquals(
            listOf(Internal.SyncFailed(listOf(unknown), ConfigReadFailure.Unavailable)),
            fixture.results(ProjectConfigEffect.Sync(listOf(unknown))),
        )

        harnesses.state.value = null
        fixture.files["harnesses/review.md"] = "rules\n"
        fixture.run(Internal.Enabled(setOf(PROJECT)))
        fixture.run(Internal.ProjectsChanged(setOf(PROJECT)))
        assertTrue(fixture.ready.proposals.isEmpty())
        assertTrue(fixture.ready.projects.getValue(PROJECT).hasFolder)
        assertFalse(fixture.ready.writing.isNotEmpty())
        assertIs<Internal.Synced>(
            fixture.results(ProjectConfigEffect.Sync(listOf(SyncRequest(PROJECT, 9, emptyList())))).single(),
        )
    }
}
