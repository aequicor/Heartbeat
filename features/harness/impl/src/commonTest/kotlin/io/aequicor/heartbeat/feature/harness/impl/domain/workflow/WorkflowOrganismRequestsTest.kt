package io.aequicor.heartbeat.feature.harness.impl.domain.workflow

import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionAnswer
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionDecision
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOption
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionOptionId
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequest
import io.aequicor.heartbeat.feature.aiengine.facade.api.PermissionRequestId
import io.aequicor.heartbeat.feature.aiengine.facade.api.TurnId
import io.aequicor.heartbeat.feature.harness.api.workflow.OrganismPermission
import io.aequicor.heartbeat.feature.harness.api.workflow.StepKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkflowOrganismRequestsTest {
    private val published = mutableListOf<List<OrganismPermission>>()
    private val requests = WorkflowOrganismRequests { published += it }

    @Test
    fun `steps show their requests as one snapshot that is published only when it changes`() = runTest {
        requests.show(S1, listOf(asking(S1, "c1")))
        requests.show(S0, listOf(asking(S0, "c2")))
        requests.show(S0, listOf(asking(S0, "c2")))
        assertEquals(listOf(S1), published[0].map { it.step })
        assertEquals(2, published.size)
        assertEquals(listOf(S0, S1), published.last().map { it.step })
        requests.show(S1, emptyList())
        assertEquals(listOf(S0), published.last().map { it.step })
        requests.show(S1, emptyList())
        assertEquals(3, published.size)
        requests.show(S0, emptyList())
        assertTrue(published.last().isEmpty())
    }

    @Test
    fun `an answer is queued only for a request that is shown and its owner takes it once`() = runTest {
        requests.show(S0, listOf(asking(S0, "c1")))
        assertTrue(requests.answer(S0, "c1", ALLOW))
        assertFalse(requests.answer(S0, "c2", ALLOW))
        assertFalse(requests.answer(S1, "c1", ALLOW))
        assertFalse(requests.answer(S0, "c1", ALLOW.copy(option = PermissionOptionId("unoffered"))))
        assertFalse(requests.answer(S0, "c1", ALLOW.copy(request = PermissionRequestId("other"))))
        assertFalse(requests.answer(S0, "c1", ALLOW.copy(answer = PermissionAnswer.Text("no input is asked"))))
        assertEquals(emptyList(), requests.take(S1))
        assertEquals(listOf(OrganismAnswer("c1", ALLOW)), requests.take(S0))
        assertEquals(emptyList(), requests.take(S0))
    }

    @Test
    fun `answers queue oldest first within a bound and vanish with the request that was asking`() = runTest {
        requests.show(S0, listOf(asking(S0, "c1")))
        repeat(12) { assertTrue(requests.answer(S0, "c1", ALLOW)) }
        assertEquals(8, requests.take(S0).size)
        assertTrue(requests.answer(S0, "c1", ALLOW))
        requests.show(S0, emptyList())
        assertEquals(emptyList(), requests.take(S0))
        assertFalse(requests.answer(S0, "c1", ALLOW))
    }

    @Test
    fun `an answer never renders its text`() {
        val answer = OrganismAnswer("c1", ALLOW.copy(answer = PermissionAnswer.Text("private text")))
        assertFalse("private" in answer.toString())
    }

    private fun asking(step: StepKey, cell: String) = OrganismPermission(
        step,
        cell,
        "Builder",
        PermissionRequest(
            PermissionRequestId("req"),
            TurnId("turn"),
            "Run a command?",
            listOf(
                PermissionOption(PermissionOptionId("allow"), "Allow"),
                PermissionOption(PermissionOptionId("deny"), "Deny"),
            ),
        ),
    )

    private companion object {
        val S0 = StepKey("s0")
        val S1 = StepKey("s1")
        val ALLOW = PermissionDecision(TurnId("turn"), PermissionRequestId("req"), PermissionOptionId("allow"))
    }
}
