package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismRefusal
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WorkflowOrganismOutcomesTest {
    @Test
    fun `every outcome survives the journal roundtrip`() {
        val outcomes = listOf(
            OrganismOutcome.Answered("done"),
            OrganismOutcome.Answered(""),
            OrganismOutcome.Refused(OrganismRefusal.Unavailable),
            OrganismOutcome.Ended(OrganismEnd.NoModel),
        )
        for (outcome in outcomes) assertEquals(outcome, outcome.toJournal()?.toOrganismOutcome())
    }

    @Test
    fun `an answer beyond the result bound has no journal value instead of being cut`() {
        val fits = OrganismOutcome.Answered("x".repeat(HarnessLimits.RESULT_CHARS - ENVELOPE))
        val tooLong = OrganismOutcome.Answered("x".repeat(HarnessLimits.RESULT_CHARS))
        assertEquals(fits, fits.toJournal()?.toOrganismOutcome())
        assertNull(tooLong.toJournal())
    }

    @Test
    fun `a stored value that is not an outcome is unreadable`() {
        assertNull(JsonPrimitive("answered").toOrganismOutcome())
        assertNull(JsonObject(mapOf("type" to JsonPrimitive("unknown"))).toOrganismOutcome())
        val unknownReason = JsonObject(mapOf("type" to JsonPrimitive("ended"), "reason" to JsonPrimitive("Gone")))
        assertNull(unknownReason.toOrganismOutcome())
    }

    @Test
    fun `the stand-in owner journals the typed refusal and conceives nothing`() = runTest {
        val fixture = WorkflowFixture()
        val journal = fixture.journal()
        val outcome = UnavailableWorkflowOrganisms(journal)
            .execute(StepKey("s0"), "a".repeat(64), "Ship it", OrganismOptions("Title"))
        assertEquals(OrganismOutcome.Refused(OrganismRefusal.Unavailable), outcome)
        assertEquals(outcome, journal.steps.single().result?.toOrganismOutcome())
        assertEquals(0, fixture.organismGoals.size)
    }

    private companion object {
        /** Characters the JSON envelope of an answer adds around its text. */
        const val ENVELOPE = 40
    }
}
