package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioPlanProposal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioPlanCallsTest {
    @Test
    fun `a plan proposal shows its revision and summary under any hosted name`() {
        val arguments = """{"revision":3,"summary":"Three steps"}"""
        assertEquals(StudioPlanProposal(3, "Three steps"), planProposal("plan_propose", arguments))
        assertEquals(
            StudioPlanProposal(3, "Three steps"),
            planProposal("mcp__heartbeat_tools__plan_propose", arguments),
        )
    }

    @Test
    fun `streaming or unreadable arguments still mark the proposal`() {
        assertEquals(StudioPlanProposal(null, ""), planProposal("plan_propose", """{"revision":3,"sum"""))
        assertEquals(StudioPlanProposal(null, ""), planProposal("plan_propose", "{not json}"))
    }

    @Test
    fun `other tools are no proposals`() {
        assertNull(planProposal("plan_write", """{"base_revision":1,"content":"x"}"""))
        assertNull(planProposal("Read", "{}"))
    }

    @Test
    fun `the summary never reaches toString`() {
        assertTrue("secret" !in StudioPlanProposal(1, "secret").toString())
    }
}
