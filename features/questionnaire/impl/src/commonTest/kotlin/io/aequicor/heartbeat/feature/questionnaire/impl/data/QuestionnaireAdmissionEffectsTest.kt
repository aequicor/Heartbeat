package io.aequicor.heartbeat.feature.questionnaire.impl.data

import io.aequicor.heartbeat.core.statemachine.EffectScope
import io.aequicor.heartbeat.core.statemachine.SendResult
import io.aequicor.heartbeat.feature.aistudio.api.StudioQuestionnaireAdmission
import io.aequicor.heartbeat.feature.questionnaire.api.Answer
import io.aequicor.heartbeat.feature.questionnaire.api.Question
import io.aequicor.heartbeat.feature.questionnaire.api.Questionnaire
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireEffect
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireId
import io.aequicor.heartbeat.feature.questionnaire.api.QuestionnaireIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class QuestionnaireAdmissionEffectsTest {
    private val question = Questionnaire(QuestionnaireId("manual"), "chat", "Restriction", Question.FreeText())
    private val effect = QuestionnaireEffect.PrepareManual(
        question,
        "generation",
        QuestionnaireIntent.Public.Answer(question.id, Answer.Text("Do not delete")),
    )

    @Test
    fun `completion waits for durable revocation without an output collector`() = runTest {
        val written = CompletableDeferred<Unit>()
        var persisted = false
        val results = mutableListOf<QuestionnaireIntent>()
        val handler = QuestionnaireAdmissionEffects(
            lazy {
                StudioQuestionnaireAdmission { source ->
                    assertEquals("chat", source)
                    written.await()
                    persisted = true
                }
            },
        )
        val job = launch { handler.handle(effect, recording(results)) }
        runCurrent()
        assertTrue(results.isEmpty())
        written.complete(Unit)
        job.join()
        assertTrue(persisted)
        assertEquals(effect, assertIs<QuestionnaireIntent.Internal.ManualPrepared>(results.single()).preparation)
        // Losing this completion or its eventual output cannot roll back the already committed revocation.
        results.clear()
        assertTrue(persisted)
    }

    @Test
    fun `failed durable write never reports preparation success`() = runTest {
        val results = mutableListOf<QuestionnaireIntent>()
        val handler = QuestionnaireAdmissionEffects(lazy { StudioQuestionnaireAdmission { error("write failed") } })
        assertFailsWith<IllegalStateException> { handler.handle(effect, recording(results)) }
        assertTrue(results.isEmpty())
    }

    @Test
    fun `cancellation before persistence never reports preparation success`() = runTest {
        val results = mutableListOf<QuestionnaireIntent>()
        val write = CompletableDeferred<Unit>()
        val handler = QuestionnaireAdmissionEffects(lazy { StudioQuestionnaireAdmission { write.await() } })
        val job = launch { handler.handle(effect, recording(results)) }
        runCurrent()
        job.cancel()
        job.join()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `cancellation after persistence keeps revocation without reporting acceptance`() = runTest {
        val results = mutableListOf<QuestionnaireIntent>()
        var persisted = false
        val handler = QuestionnaireAdmissionEffects(
            lazy {
                StudioQuestionnaireAdmission {
                    persisted = true
                    currentCoroutineContext().cancel()
                }
            },
        )
        val job = launch { handler.handle(effect, recording(results)) }
        job.join()
        assertTrue(persisted)
        assertTrue(job.isCancelled)
        assertTrue(results.isEmpty())
    }

    private fun recording(results: MutableList<QuestionnaireIntent>) = object : EffectScope<QuestionnaireIntent> {
        override suspend fun send(intent: QuestionnaireIntent): SendResult {
            results += intent
            return SendResult.Accepted
        }
    }
}
