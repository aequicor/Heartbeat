package io.aequicor.heartbeat.feature.aistudio.impl.presentation.remote

import io.aequicor.heartbeat.feature.aistudio.api.AiStudioState
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioModelTest
import io.aequicor.heartbeat.feature.aistudio.impl.presentation.store.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioEmbedViews
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioQuestionView
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class StudioEmbedBarrierTest {
    @Test
    fun `root ack waits for the child and a late collector cannot roll back its committed text`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val updates = MutableStateFlow(StudioEmbedViews())
        val committed = views(2, "confirmed")
        val embeds = object : StudioEmbeds {
            override val states = updates
            override suspend fun dispatch(input: AiStudioScreenIntent.Embed): StudioEmbedViews {
                gate.await()
                return committed
            }
        }
        val fixture = AiStudioModelTest.Fixture(this, AiStudioState.Loading, embeds = embeds)
        val screen = fixture.subscribe()
        screen.intent(
            AiStudioScreenIntent.QuestionInput(
                "source",
                "child",
                QuestionnaireScreenIntent.TextChanged("q", "confirmed"),
            ),
        )
        screen.intent(AiStudioScreenIntent.RemoteApplied(7))
        runCurrent()
        assertEquals(0, screen.states.value.remoteSeq)
        gate.complete(Unit)
        runCurrent()
        assertEquals(7, screen.states.value.remoteSeq)
        assertEquals(committed, screen.states.value.remoteEmbeds)
        updates.value = views(1, "old")
        runCurrent()
        assertEquals(committed, screen.states.value.remoteEmbeds)
    }

    private fun views(revision: Long, text: String) = StudioEmbedViews(
        revision,
        questions = persistentMapOf(
            "source" to StudioQuestionView(
                "child",
                revision,
                QuestionnaireScreenState(
                    persistentListOf(
                        QuestionUi("q", "Question", null, QuestionKindUi.Text(null, false), true, false, text = text),
                    ),
                ),
            ),
        ),
    )
}
