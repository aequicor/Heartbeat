package io.aequicor.heartbeat.feature.aistudio.impl.domain

import io.aequicor.heartbeat.feature.aistudio.api.StudioPermission
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionAnswer
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionInput
import io.aequicor.heartbeat.feature.aistudio.api.StudioPermissionOption
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Choice
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StudioDecisionTest {
    private val approval = StudioPermission(
        "s1",
        "r1",
        "bash: ls",
        listOf(StudioPermissionOption("allow", "Allow"), StudioPermissionOption("deny", "Deny")),
    )
    private val select = approval.copy(
        options = listOf(
            StudioPermissionOption("answer", "Answer"),
            StudioPermissionOption("skip", "Skip", isSkip = true),
        ),
        input = StudioPermissionInput.SingleChoice(listOf(StudioPermissionOption("0", "red"))),
    )

    @Test
    fun `two plain options become an unskippable confirmation of the session`() {
        val questionnaire = assertNotNull(approval.toQuestionnaire())
        assertEquals(QuestionnaireId("s1/r1"), questionnaire.id)
        assertEquals("s1", questionnaire.source)
        assertEquals(Question.Confirm("Allow", "Deny"), questionnaire.question)
        assertFalse(questionnaire.isSkippable)
        assertEquals(StudioDecision("deny", null), approval.decision(Answer.Confirmed(false)))
        assertNull(approval.decision(Answer.Skipped))
    }

    @Test
    fun `other plain option counts become a single choice among the options`() {
        val three = approval.copy(options = approval.options + StudioPermissionOption("always", "Always"))
        assertEquals(
            Question.SingleChoice(listOf(Choice("allow", "Allow"), Choice("deny", "Deny"), Choice("always", "Always"))),
            assertNotNull(three.toQuestionnaire()).question,
        )
        assertEquals(StudioDecision("always", null), three.decision(Answer.Selected(listOf("always"))))
        assertNull(three.decision(Answer.Selected(listOf("foreign"))))
    }

    @Test
    fun `structured input submits with the submit option and skips with the skip option`() {
        assertTrue(assertNotNull(select.toQuestionnaire()).isSkippable)
        assertEquals(
            StudioDecision("answer", StudioPermissionAnswer.Selected(listOf("0"))),
            select.decision(Answer.Selected(listOf("0"))),
        )
        assertEquals(StudioDecision("skip", null), select.decision(Answer.Skipped))
        val text = select.copy(input = StudioPermissionInput.FreeText(null, true))
        assertEquals(Question.FreeText(null, true), assertNotNull(text.toQuestionnaire()).question)
        assertEquals(StudioDecision("answer", StudioPermissionAnswer.Text("hi")), text.decision(Answer.Text("hi")))
    }

    @Test
    fun `permissions without usable options ask nothing and never throw`() {
        val empty = approval.copy(options = emptyList())
        assertNull(empty.toQuestionnaire())
        assertNull(empty.decision(Answer.Confirmed(true)))
        val onlySkip = select.copy(options = listOf(StudioPermissionOption("skip", "Skip", isSkip = true)))
        assertNull(onlySkip.toQuestionnaire())
        assertNull(onlySkip.decision(Answer.Selected(listOf("0"))))
        val noSkip = select.copy(options = listOf(StudioPermissionOption("answer", "Answer")))
        assertFalse(assertNotNull(noSkip.toQuestionnaire()).isSkippable)
        assertNull(noSkip.decision(Answer.Skipped))
        assertNull(select.copy(options = emptyList()).decision(Answer.Text("x")))
    }
}
