package io.aequicor.heartbeat.feature.projectconfig.api

import io.aequicor.heartbeat.feature.aiengine.facade.api.WorkspaceRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigProposalRecheckTest {
    private val project = WorkspaceRef("project")
    private val unit = ConfigUnitRef(ConfigSectionId.Harnesses, "review")
    private val files = ConfigDigest("unchanged-files")
    private val summary = ConfigSummary("Review")
    private val valid = UnitCheck.Valid(summary, unit.name, ConfigDigest("canonical"))
    private val compileProblem = ConfigProblem(
        ConfigProblemCode.Compile,
        ConfigPath("harnesses/review/check.kts"),
        "compilation timed out",
    )

    @Test
    fun `a successful recheck after a timeout makes unchanged files eligible for explicit consent`() {
        val timedOut = proposal(UnitCheck.Invalid(listOf(compileProblem)))

        val retried = proposal(valid, timedOut)

        assertEquals(files, retried.digest)
        assertEquals(summary, retried.summary)
        assertTrue(retried.problems.isEmpty())
        assertTrue(retried.isTrustable)
    }

    @Test
    fun `a failed recheck revokes trust for unchanged files`() {
        val reviewed = proposal(valid)

        val rejected = proposal(UnitCheck.Invalid(listOf(compileProblem)), reviewed)

        assertEquals(listOf(compileProblem), rejected.problems)
        assertNull(rejected.summary)
        assertFalse(rejected.isTrustable)
    }

    @Test
    fun `a changed validation summary is shown before consent for unchanged files`() {
        val reviewed = proposal(valid)
        val changedSummary = summary.copy(contents = listOf(ConfigContent(ConfigContentKind.Script, "check")))

        val rechecked = proposal(valid.copy(summary = changedSummary), reviewed)

        assertEquals(changedSummary, rechecked.summary)
        assertTrue(rechecked.isTrustable)
    }

    private fun proposal(check: UnitCheck, previous: ConfigProposal? = null): ConfigProposal = reconcile(
        bindings = emptyList(),
        snapshot = ProjectSnapshot(project, 1, listOf(ScannedUnit(unit, files, check)), emptyList()),
        previous = listOfNotNull(previous),
    ).proposals.single()
}
