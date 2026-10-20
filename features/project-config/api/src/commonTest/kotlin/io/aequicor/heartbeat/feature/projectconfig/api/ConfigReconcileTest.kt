package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigReconcileTest {
    private val project = WorkspaceRef("p")
    private val other = WorkspaceRef("other")
    private val review = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val key = ConfigEntityKey("h_review")
    private val binding = ConfigBinding(project, review, key, digest("f1"), digest("e1"))
    private val source = ConfigSource.Project(project)

    @Test
    fun `nothing changes while files and entity are as bound and other projects are not returned`() {
        val foreign = binding.copy(project = other)
        val result = reconcile(listOf(binding, foreign), snapshot(unit(review, "f1"), entity(key, "e1")))

        assertEquals(Reconciled(listOf(binding), emptyList(), emptyList()), result)
    }

    @Test
    fun `an entity changed in the app is written back and a removed one deletes its files`() {
        val changed = reconcile(listOf(binding), snapshot(unit(review, "f1"), entity(key, "e2")))
        assertEquals(listOf(ConfigWriteBack(project, review, key, digest("f1"))), changed.writeBacks)
        assertEquals(listOf(binding), changed.bindings)

        val removed = reconcile(listOf(binding), snapshot(unit(review, "f1")))
        assertEquals(listOf(ConfigWriteBack(project, review, null, digest("f1"))), removed.writeBacks)
        assertTrue(removed.proposals.isEmpty())
    }

    @Test
    fun `changed files become an update or a conflict or only a new digest when canonically equal`() {
        val update = reconcile(listOf(binding), snapshot(unit(review, "f2", "c2"), entity(key, "e1")))
        assertEquals(ProposalKind.Update, update.proposals.single().kind)
        assertEquals(key, update.proposals.single().target)
        assertEquals(listOf(binding), update.bindings)

        val conflict = reconcile(listOf(binding), snapshot(unit(review, "f2", "c2"), entity(key, "e2")))
        assertEquals(ProposalKind.Conflict, conflict.proposals.single().kind)

        val formatted = reconcile(listOf(binding), snapshot(unit(review, "f2", "e1"), entity(key, "e1")))
        assertTrue(formatted.proposals.isEmpty())
        assertEquals(listOf(binding.copy(fileDigest = digest("f2"))), formatted.bindings)
    }

    @Test
    fun `changed files of an entity removed in the app are handled as a new unit`() {
        val result = reconcile(listOf(binding), snapshot(unit(review, "f2", "c2")))

        assertTrue(result.bindings.isEmpty())
        assertEquals(ProposalKind.Create, result.proposals.single().kind)
    }

    @Test
    fun `an unbound unit binds silently to an equal entity or updates a different one or creates one`() {
        val equal = reconcile(emptyList(), snapshot(unit(review, "f1", "e1"), entity(key, "e1")))
        assertEquals(listOf(ConfigBinding(project, review, key, digest("f1"), digest("e1"))), equal.bindings)
        assertTrue(equal.proposals.isEmpty())

        val different = reconcile(emptyList(), snapshot(unit(review, "f1", "c1"), entity(key, "e1")))
        assertEquals(ProposalKind.Update to key, different.proposals.single().let { it.kind to it.target })

        val created = reconcile(emptyList(), snapshot(unit(review, "f1", "c1")))
        assertEquals(ProposalKind.Create, created.proposals.single().kind)
    }

    @Test
    fun `missing files propose removal unless the entity is gone too`() {
        val removal = reconcile(listOf(binding), snapshot(entity = entity(key, "e2")))
        val proposal = removal.proposals.single()
        assertEquals(ProposalKind.Remove, proposal.kind)
        assertTrue(proposal.isTrustable)

        assertEquals(Reconciled(emptyList(), emptyList(), emptyList()), reconcile(listOf(binding), snapshot()))
    }

    @Test
    fun `units and bindings of unavailable sections are left untouched`() {
        val paused = ProjectSnapshot(
            project,
            1,
            listOf(unit(ConfigUnitRef(ConfigSectionId.Harnesses, "new"), "f9", "c9")),
            emptyList(),
            unavailable = setOf(ConfigSectionId.Harnesses),
        )

        assertEquals(Reconciled(listOf(binding), emptyList(), emptyList()), reconcile(listOf(binding), paused))
    }

    @Test
    fun `invalid units are shown but never trusted and a failed apply survives an unchanged rescan`() {
        val problem = ConfigProblem(ConfigProblemCode.Syntax, ConfigPath("harnesses/review/harness.json"))
        val invalid = ScannedUnit(review, digest("f1"), UnitCheck.Invalid(listOf(problem)))
        val shown = reconcile(emptyList(), snapshot(invalid)).proposals.single()
        assertEquals(listOf(problem), shown.problems)
        assertFalse(shown.isTrustable)

        val first = reconcile(emptyList(), snapshot(unit(review, "f1", "c1"))).proposals.single()
        val reviewed = first.copy(failure = ConfigRejection.Failed)
        val again = reconcile(emptyList(), snapshot(unit(review, "f1", "c1")), listOf(reviewed))
        assertEquals(listOf(reviewed), again.proposals)
        assertTrue(reviewed.isTrustable)
        val changed = reconcile(emptyList(), snapshot(unit(review, "f2", "c2")), listOf(reviewed))
        assertEquals(null, changed.proposals.single().failure)
        assertEquals(source, changed.proposals.single().source)
    }

    @Test
    fun `bundle units create or update or are left out when the profile already has them`() {
        val learning = ConfigUnitRef(ConfigSectionId.Learning, "style")
        val proposals = bundleProposals(
            "b1",
            listOf(
                unit(review, "f1", "e1"),
                unit(learning, "f2", "c2"),
                unit(ConfigUnitRef(ConfigSectionId.Learning, "new"), "f3", "c3"),
            ),
            listOf(
                entity(key, "e1"),
                EntityDigest(ConfigSectionId.Learning, ConfigEntityKey("l1"), "style", digest("old")),
            ),
        )

        assertEquals(listOf(ProposalKind.Update, ProposalKind.Create), proposals.map { it.kind })
        assertEquals(ConfigEntityKey("l1"), proposals.first().target)
        assertTrue(proposals.all { it.source == ConfigSource.Bundle("b1") })
    }

    private fun snapshot(vararg units: ScannedUnit, entity: EntityDigest? = null) =
        ProjectSnapshot(project, 1, units.toList(), listOfNotNull(entity))

    private fun snapshot(unit: ScannedUnit, entity: EntityDigest) = ProjectSnapshot(
        project,
        1,
        listOf(unit),
        listOf(entity),
    )

    private fun unit(ref: ConfigUnitRef, files: String, canonical: String? = null) = ScannedUnit(
        ref,
        digest(files),
        canonical?.let { UnitCheck.Valid(ConfigSummary(ref.name), ref.name, digest(it)) } ?: UnitCheck.Unchanged,
    )

    private fun entity(key: ConfigEntityKey, digest: String) =
        EntityDigest(ConfigSectionId.Harnesses, key, "review", digest(digest))

    private fun digest(value: String) = ConfigDigest(value)
}
