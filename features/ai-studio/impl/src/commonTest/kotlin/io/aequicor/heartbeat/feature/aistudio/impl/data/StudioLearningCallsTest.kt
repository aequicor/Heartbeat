package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.agentlearning.api.InstructionKind
import io.aequicor.heartbeat.feature.aistudio.impl.domain.LearningAction
import io.aequicor.heartbeat.feature.aistudio.impl.domain.StudioLearningCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioLearningCallsTest {
    @Test
    fun `learning tools are recognised under the plain and the hosted server name only`() {
        val arguments = """{"kind":"model","title":"LF","content":"Use LF"}"""
        val expected = StudioLearningCall(LearningAction.Remember, InstructionKind.Model, "LF", "Use LF")
        assertEquals(expected, learningCall("remember", arguments))
        assertEquals(expected, learningCall("mcp__heartbeat_tools__remember", arguments))
        assertNull(learningCall("mcp__other__remember", arguments))
        assertEquals(
            StudioLearningCall(LearningAction.LoadSkill, null, "release", ""),
            learningCall("load_learned_skill", """{"name":"release"}"""),
        )
    }

    @Test
    fun `incomplete, invalid and unknown arguments still make a card without raw text`() {
        val empty = StudioLearningCall(LearningAction.Remember, null, "", "")
        assertEquals(empty, learningCall("remember", """{"kind":"""))
        assertEquals(empty, learningCall("remember", "{oops}"))
        assertEquals(
            StudioLearningCall(LearningAction.Remember, null, "T", "C"),
            learningCall("remember", """{"kind":"rule","title":"T","content":"C"}"""),
        )
    }
}
