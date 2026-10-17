package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineBindingId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineId
import io.aequicor.heartbeat.feature.aiengine.facade.api.EngineTarget
import io.aequicor.heartbeat.feature.aiengine.facade.api.ModelId
import io.aequicor.heartbeat.feature.harness.api.HarnessLimits
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismEnd
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOptions
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismOutcome
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismRefusal
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import io.aequicor.heartbeat.feature.harness.api.workflow.StepPhase
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowDefinition
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowFailure
import io.aequicor.heartbeat.feature.harness.api.workflow.WorkflowStepFailed
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

class WorkflowOrganismEngineTest {
    private val options = OrganismOptions("Build the feature")

    @Test
    fun `a recorded organism outcome replays without conceiving another organism`() = runTest {
        val fixture = WorkflowFixture()
        val outcomes = mutableListOf<OrganismOutcome>()
        val definition: WorkflowDefinition = {
            outcomes += organism("Ship it", options)
            JsonNull
        }
        fixture.engine().execute(definition)
        fixture.clock.instant += 2.hours
        fixture.engine().execute(definition)
        assertEquals(listOf<OrganismOutcome>(OrganismOutcome.Answered("Organism: Ship it")), outcomes.distinct())
        assertEquals(2, outcomes.size)
        assertEquals(listOf(StepKey("s0") to "Ship it"), fixture.organismGoals)
        assertEquals(StepPhase.Completed, fixture.storage.load().single().steps.single().phase)
    }

    @Test
    fun `refusals and endings without an answer replay as the same data`() = runTest {
        val results = listOf(
            OrganismOutcome.Refused(OrganismRefusal.Unavailable),
            OrganismOutcome.Refused(OrganismRefusal.Rejected),
            OrganismOutcome.Ended(OrganismEnd.NoModel),
            OrganismOutcome.Ended(OrganismEnd.Failed),
            OrganismOutcome.Ended(OrganismEnd.Aborted),
        )
        for (expected in results) {
            val fixture = WorkflowFixture()
            val seen = mutableListOf<OrganismOutcome>()
            val definition: WorkflowDefinition = {
                seen += organism("Ship it", options)
                JsonNull
            }
            fixture.engine { journal ->
                WorkflowOrganismSteps { key, digest, _, _ ->
                    journal.prepare(key, digest)
                    journal.complete(key, checkNotNull(expected.toJournal()))
                    expected
                }
            }.execute(definition)
            fixture.engine {
                WorkflowOrganismSteps { _, _, _, _ -> error("Replay must not ask the organism owner again") }
            }.execute(definition)
            assertEquals(listOf<OrganismOutcome>(expected, expected), seen)
        }
    }

    @Test
    fun `an unavailable organic AI is a typed refusal that the workflow can handle`() = runTest {
        val fixture = WorkflowFixture()
        val seen = mutableListOf<OrganismOutcome>()
        val definition: WorkflowDefinition = {
            seen += organism("Ship it", options)
            JsonNull
        }
        fixture.engine { UnavailableWorkflowOrganisms(it) }.execute(definition)
        fixture.engine { UnavailableWorkflowOrganisms(it) }.execute(definition)
        val refusal: OrganismOutcome = OrganismOutcome.Refused(OrganismRefusal.Unavailable)
        assertEquals(listOf(refusal, refusal), seen)
        assertEquals(StepPhase.Completed, fixture.storage.load().single().steps.single().phase)
    }

    @Test
    fun `a changed goal title or model for a recorded organism is divergent`() = runTest {
        val fixture = WorkflowFixture()
        fixture.engine().execute {
            organism("Ship it", options)
            JsonNull
        }
        val target = EngineTarget(EngineId("engine"), EngineBindingId("binding"), ModelId("model"))
        val changes = listOf(
            "Ship that" to options,
            "Ship it" to OrganismOptions("Another title"),
            "Ship it" to options.copy(target = target),
            "Ship it" to options.copy(immunityTarget = target),
        )
        for ((goal, changed) in changes) {
            val failure = assertFailsWith<WorkflowStepFailed> {
                fixture.engine().execute {
                    organism(goal, changed)
                    JsonNull
                }
            }
            assertEquals(WorkflowFailure.Diverged, failure.reason)
        }
        assertEquals(1, fixture.organismGoals.size)
    }

    @Test
    fun `a blank or oversized goal fails the step as an error before any owner is asked`() = runTest {
        val fixture = WorkflowFixture()
        for (goal in listOf(" ", "x".repeat(HarnessLimits.ORGANISM_GOAL_CHARS + 1))) {
            val failure = assertFailsWith<WorkflowStepFailed> {
                fixture.engine().execute {
                    organism(goal, options)
                    JsonNull
                }
            }
            assertEquals(WorkflowFailure.Error, failure.reason)
        }
        assertTrue(fixture.organismGoals.isEmpty())
    }

    @Test
    fun `parallel organisms get separate structural keys`() = runTest {
        val fixture = WorkflowFixture()
        fixture.engine().execute {
            val branches = parallel(
                { organism("first", options).let { JsonNull } },
                { organism("second", options).let { JsonNull } },
            )
            JsonArray(branches)
        }
        assertEquals(setOf("p0/p0/s0", "p0/p1/s0"), fixture.organismGoals.map { it.first.value }.toSet())
        assertEquals(
            setOf("s0", "p0/p0/s0", "p0/p1/s0"),
            fixture.storage.load().single().steps.map { it.key.value }.toSet(),
        )
    }

    @Test
    fun `an outcome the owner did not journal is not returned to the workflow`() = runTest {
        val fixture = WorkflowFixture()
        val engine = fixture.engine { WorkflowOrganismSteps { _, _, _, _ -> OrganismOutcome.Answered("lost") } }
        assertFailsWith<WorkflowExecutionUnavailable> {
            engine.execute {
                organism("Ship it", options)
                JsonNull
            }
        }
        assertTrue(fixture.storage.load().single().steps.none { it.phase == StepPhase.Completed })
    }
}
