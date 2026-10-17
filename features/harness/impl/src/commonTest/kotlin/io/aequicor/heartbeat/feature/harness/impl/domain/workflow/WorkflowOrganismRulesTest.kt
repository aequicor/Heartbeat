package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.harness.api.HarnessId
import io.aequicor.heartbeat.feature.harness.api.workflow.RunId
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import okio.ByteString.Companion.encodeUtf8
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class WorkflowOrganismRulesTest {
    private val digest: (String) -> String = { it.encodeUtf8().sha256().hex() }

    @Test
    fun `the organism of a step comes from its run and key alone`() {
        val id = workflowOrganism(RunId("wf_a"), StepKey("s0"), digest)
        assertEquals(id, workflowOrganism(RunId("wf_a"), StepKey("s0"), digest))
        assertNotEquals(id, workflowOrganism(RunId("wf_b"), StepKey("s0"), digest))
        assertNotEquals(id, workflowOrganism(RunId("wf_a"), StepKey("p0/s0"), digest))
        assertTrue(id.id.startsWith("wf-") && id.id.length == 43)
    }

    @Test
    fun `the run bound is checked before the profile bound`() {
        val own = grant("wf_a", "s0")
        assertEquals(OrganismAdmission.Granted, admissionFor(emptyList(), own))
        assertEquals(OrganismAdmission.Granted, admissionFor(listOf(grant("wf_b", "s0")), own))
        assertEquals(OrganismAdmission.RunLimit, admissionFor(listOf(grant("wf_a", "s1")), own))
        val full = listOf(grant("wf_b", "s0"), grant("wf_c", "s0"))
        assertEquals(OrganismAdmission.ProfileLimit, admissionFor(full, own))
        assertEquals(OrganismAdmission.RunLimit, admissionFor(full + grant("wf_a", "s1"), own))
    }

    private fun grant(run: String, key: String) = WorkflowOrganismGrant(
        RunId(run),
        HarnessId("harness"),
        StepKey(key),
        "a".repeat(64),
        null,
        workflowOrganism(RunId(run), StepKey(key), digest),
    )
}
