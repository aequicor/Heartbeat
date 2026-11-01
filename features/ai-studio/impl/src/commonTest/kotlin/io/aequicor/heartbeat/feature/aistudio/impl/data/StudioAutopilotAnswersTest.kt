package io.aequicor.heartbeat.feature.aistudio.impl.data

import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.aistudio.impl.domain.toQuestionnaire
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
    private val permission = StudioPermission(
        "chat",
        "request",
        "Sleep for 35 seconds",
        listOf(
            StudioPermissionOption("allow", "Allow", isOneTime = true),
            StudioPermissionOption("deny", "Deny", isSkip = true, isOneTime = true),
        ),
        turnId = "turn",
    )

    @Test
    fun `native one time option ids map to their exact confirmation positions`() {
        val q = permission.toQuestionnaire()!!
        assertEquals(
            Answer.Confirmed(true),
            q.autopilotAnswer(ContinuationAnswer(selected = listOf("allow")), permission),
        )
        assertEquals(
            Answer.Confirmed(false),
            q.autopilotAnswer(ContinuationAnswer(selected = listOf("deny")), permission),
        )
        val reversed = permission.copy(options = permission.options.reversed())
        assertEquals(
            Answer.Confirmed(false),
            reversed.toQuestionnaire()!!.autopilotAnswer(ContinuationAnswer(selected = listOf("allow")), reversed),
        )
    }

    @Test
    fun `native ids do not relax snapshot fields or one time requirements`() {
        val q = permission.toQuestionnaire()!!
        val selected = ContinuationAnswer(selected = listOf("allow"))
        assertNull(q.autopilotAnswer(selected))
        assertNull(q.autopilotAnswer(selected, permission.copy(turnId = "replacement")))
        assertNull(q.autopilotAnswer(selected, permission.copy(title = "Different action")))
        val persistent = permission.copy(options = permission.options.map { it.copy(isOneTime = false) })
        assertNull(persistent.toQuestionnaire()!!.autopilotAnswer(selected, persistent))
        listOf(
            selected.copy(selected = listOf("always")),
            selected.copy(selected = listOf("allow", "deny")),
            selected.copy(selected = listOf("allow", "allow")),
            selected.copy(isConfirmed = true),
            selected.copy(isConfirmed = false),
            selected.copy(text = "yes"),
        ).forEach { assertNull(q.autopilotAnswer(it, permission)) }
        val duplicate = permission.copy(options = listOf(permission.options.first(), permission.options.first()))
        assertNull(duplicate.toQuestionnaire()!!.autopilotAnswer(selected, duplicate))
        val mixedDuplicate = duplicate.copy(
            options = listOf(duplicate.options[0], duplicate.options[1].copy(isOneTime = false)),
        )
        assertNull(mixedDuplicate.toQuestionnaire()!!.autopilotAnswer(selected, mixedDuplicate))
    }

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
        assertEquals(Answer.Confirmed(false), confirm.autopilotAnswer(ContinuationAnswer(isConfirmed = false)))
        assertNull(confirm.autopilotAnswer(ContinuationAnswer(isConfirmed = true, selected = listOf("always"))))
        val text = question(Question.FreeText())
        assertEquals(Answer.Text("Answer"), text.autopilotAnswer(ContinuationAnswer(text = "Answer")))
        assertNull(text.autopilotAnswer(ContinuationAnswer(text = "Answer", isConfirmed = true)))
        assertNull(text.autopilotAnswer(ContinuationAnswer(text = " ")))
    }
}
