package io.aequicor.heartbeat.feature.questionnaire.api

import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Resolves a happy-path admission, explicitly completing the source's successful durable revocation. */
internal fun resolveAfterManualAdmission(state: QuestionnaireState, intent: QuestionnaireIntent) =
    QuestionnaireMachineSpec.resolve(state, intent)?.let { result ->
        val preparation = result.effects.singleOrNull() as? QuestionnaireEffect.PrepareManual
        if (preparation == null) {
            result
        } else {
            assertTrue(result.outputs.isEmpty())
            assertTrue(intent is QuestionnaireIntent.Public.Answer)
            assertTrue(preparation.answer.id !in assertIs<QuestionnaireState.Asking>(result.to).submitting)
            assertNotNull(
                QuestionnaireMachineSpec.resolve(result.to, QuestionnaireIntent.Internal.ManualPrepared(preparation)),
            )
        }
    }

/** Checks final acceptance after successful durable preparation rather than skipping the new admission phase. */
internal fun assertAfterManualAdmission(
    from: QuestionnaireState,
    intent: QuestionnaireIntent,
    to: QuestionnaireState,
    outputs: List<QuestionnaireOutput> = emptyList(),
) {
    val result = assertNotNull(resolveAfterManualAdmission(from, intent))
    assertEquals(to, result.to)
    assertTrue(result.effects.isEmpty())
    assertEquals(outputs, result.outputs)
}
