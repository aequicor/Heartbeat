package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.autocontinue.api.ContinuationAnswer
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Choice
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StudioAutopilotAnswersTest {
    private fun question(value: Question) = Questionnaire(QuestionnaireId("q"), "chat", "Question", value)

    @Test
    fun `only exact offered choices are accepted`() {
        val q = question(Question.SingleChoice(listOf(Choice("a", "First"), Choice("b", "Second"))))
        assertEquals(Answer.Selected(listOf("b")), q.autopilotAnswer(ContinuationAnswer(selected = listOf("b"))))
        assertNull(q.autopilotAnswer(ContinuationAnswer(selected = listOf("missing"))))
        assertNull(q.autopilotAnswer(ContinuationAnswer(selected = listOf("a", "b"))))
        assertNull(q.autopilotAnswer(ContinuationAnswer(text = "arbitrary replacement")))
        assertNull(q.autopilotAnswer(ContinuationAnswer(selected = listOf("a"), text = "extra")))
    }

    @Test
    fun `confirm and free text cannot smuggle fields of a different question`() {
        val confirm = question(Question.Confirm("Yes", "No"))
        assertEquals(Answer.Confirmed(false), confirm.autopilotAnswer(ContinuationAnswer(confirmed = false)))
        assertNull(confirm.autopilotAnswer(ContinuationAnswer(confirmed = true, selected = listOf("always"))))
        val text = question(Question.FreeText())
        assertEquals(Answer.Text("Answer"), text.autopilotAnswer(ContinuationAnswer(text = "Answer")))
        assertNull(text.autopilotAnswer(ContinuationAnswer(text = "Answer", confirmed = true)))
        assertNull(text.autopilotAnswer(ContinuationAnswer(text = " ")))
    }
}
