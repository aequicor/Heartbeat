package io.aequicor.heartbeat.feature.harness.api.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.RequestId
import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.scheduler.api.HelperId
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class WorkflowOrganismTest {
    @Test
    fun `outcomes keep a stable journal shape`() {
        val shapes = mapOf(
            OrganismOutcome.Answered("done") to """{"type":"answered","text":"done"}""",
            OrganismOutcome.Refused(OrganismRefusal.Unavailable) to """{"type":"refused","reason":"Unavailable"}""",
            OrganismOutcome.Ended(OrganismEnd.NoModel) to """{"type":"ended","reason":"NoModel"}""",
        )
        for ((outcome, json) in shapes) {
            assertEquals(json, Json.encodeToString(OrganismOutcome.serializer(), outcome))
            assertEquals(outcome, Json.decodeFromString(OrganismOutcome.serializer(), json))
        }
    }

    @Test
    fun `answers and options never reach diagnostic strings`() {
        assertFalse(OrganismOutcome.Answered("private-answer").toString().contains("private-answer"))
        assertFalse(OrganismOptions("private-title").toString().contains("private-title"))
    }

    @Test
    fun `an organism step round trips and keeps its binding out of diagnostic strings`() {
        val step = WorkflowStep(StepKey("s0"), DIGEST, organism = WorkflowOrganism("wf-private"))
        assertEquals(step, Json.decodeFromString<WorkflowStep>(Json.encodeToString(step)))
        assertFalse(step.toString().contains("wf-private"))
        assertFalse(step.organism.toString().contains("wf-private"))
    }

    @Test
    fun `an organism step is neither a helper step nor a retried step`() {
        val organism = WorkflowOrganism("wf-one")
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep(StepKey("s0"), DIGEST, request = RequestId("request"), organism = organism)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep(
                StepKey("s0"),
                DIGEST,
                helper = HelperId("helper"),
                request = RequestId("request"),
                organism = organism,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowStep(StepKey("s0"), DIGEST, attempt = 1, organism = organism)
        }
    }

    @Test
    fun `organism identities stay within the organic AI id alphabet and length`() {
        for (id in listOf("", "has space", "wf/slash", "x".repeat(65))) {
            assertFailsWith<IllegalArgumentException> { WorkflowOrganism(id) }
        }
        assertEquals("x".repeat(64), WorkflowOrganism("x".repeat(64)).id)
        assertEquals("wf-A_1", WorkflowOrganism("wf-A_1").id)
    }

    @Test
    fun `sandbox execution rejects unbounded growth and partial Git references`() {
        for (commit in listOf("HEAD", "abc123", "x".repeat(40))) {
            assertFailsWith<IllegalArgumentException> { OrganismExecution(baseCommit = commit) }
        }
        assertFailsWith<IllegalArgumentException> { OrganismExecution(maxCells = 17) }
        assertFailsWith<IllegalArgumentException> { OrganismExecution(maxDepth = 0) }
        assertFailsWith<IllegalArgumentException> { OrganismExecution(maxCases = -1) }
        assertFailsWith<IllegalArgumentException> { OrganismExecution(maxOpenCasesPerCell = 3) }
        val requested = OrganismExecution("a".repeat(40), maxCells = 1, maxCases = 0)
        assertEquals(requested, Json.decodeFromString<OrganismExecution>(Json.encodeToString(requested)))
    }

    @Test
    fun `trusted selection and Git output survive replay while legacy answers remain readable`() {
        val context = WorkflowOrganismContext(setOf(HarnessId("owner"), HarnessId("restriction")))
        assertEquals(context, Json.decodeFromString<WorkflowOrganismContext>(Json.encodeToString(context)))
        val output = OrganismCommit("heartbeat/result", "a".repeat(40), "b".repeat(40))
        val answer = OrganismOutcome.Answered("done", output)
        assertEquals(answer, Json.decodeFromString<OrganismOutcome>(Json.encodeToString<OrganismOutcome>(answer)))
        val legacy = Json.decodeFromString<WorkflowRouting>("""{"workspace":null,"handoff":{}}""")
        assertEquals(null, legacy.organismContext)
    }

    private companion object {
        val DIGEST = "a".repeat(64)
    }
}
