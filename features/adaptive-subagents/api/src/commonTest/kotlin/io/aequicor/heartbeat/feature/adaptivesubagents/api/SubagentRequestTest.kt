package io.aequicor.heartbeat.feature.adaptivesubagents.api

import kotlin.test.Test
import kotlin.test.assertEquals

class SubagentRequestTest {
    @Test
    fun `tool values become a request and unknown ones are ignored`() {
        assertEquals(
            SubagentRequest(
                "t",
                SubagentPriority.Speed,
                SubagentComplexity.High,
                setOf(SubagentNeed.Pdf, SubagentNeed.LongContext),
                setOf("coding"),
            ),
            subagentRequestOf("t", "SPEED", "high", listOf("pdf", "long_context", "video"), listOf("Coding")),
        )
        assertEquals(SubagentRequest("t"), subagentRequestOf("t", "fastest", "extreme"))
    }
}
