package io.aequicor.heartbeat.feature.aistudio.screenmodel.remote

import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenIntent
import io.aequicor.heartbeat.feature.aistudio.screenmodel.AiStudioScreenState
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioChecklistView
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioEmbedViews
import io.aequicor.heartbeat.feature.aistudio.screenmodel.StudioQuestionView
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistChoiceUi
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistFieldUi
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistPhaseUi
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenState
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.ChoiceUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StudioEmbedPredictionTest {
    private val question = QuestionUi(
        "q",
        "Question",
        null,
        QuestionKindUi.Choice(persistentListOf(ChoiceUi("a", "A")), true),
        true,
        false,
        selected = persistentSetOf("a"),
    )
    private val field = ChecklistFieldUi(
        "f", "Field", false, true, true, 1, 2,
        persistentListOf(ChecklistChoiceUi("a", "A")), persistentSetOf("a"), "",
    )
    private fun screen(question: QuestionUi = this.question, field: ChecklistFieldUi = this.field) =
        AiStudioScreenState(
            remoteEmbeds = StudioEmbedViews(
                questions = persistentMapOf(
                    "s" to StudioQuestionView(
                        "instance",
                        1,
                        QuestionnaireScreenState(
                            persistentListOf(question),
                        ),
                    ),
                ),
                checklists = persistentMapOf(
                    "c" to StudioChecklistView(
                        "instance",
                        1,
                        ChecklistScreenState(
                            fields = persistentListOf(field),
                            phase = ChecklistPhaseUi.Open,
                        ),
                    ),
                ),
            ),
        )

    @Test
    fun `pending choices do not toggle a committed selection before the root ack arrives`() {
        val committed = screen()
        assertEquals(0, committed.remoteSeq)
        assertSame(
            committed,
            committed.predict(
                AiStudioScreenIntent.QuestionInput(
                    "s",
                    "instance",
                    QuestionnaireScreenIntent.ToggleChoice("q", "a"),
                ),
            ),
        )
        assertSame(
            committed,
            committed.predict(
                AiStudioScreenIntent.ChecklistInput(
                    "c",
                    "instance",
                    ChecklistScreenIntent.Choice("f", "a"),
                ),
            ),
        )
    }

    @Test
    fun `text remains visible until acknowledgement and submitting waits for the host`() {
        val state = screen(question.copy(kind = QuestionKindUi.Text(null, false)), field.copy(isText = true))
        val typed = state.predict(
            AiStudioScreenIntent.QuestionInput(
                "s",
                "instance",
                QuestionnaireScreenIntent.TextChanged("q", "answer"),
            ),
        ).predict(AiStudioScreenIntent.ChecklistInput("c", "instance", ChecklistScreenIntent.Text("f", "answer")))
        assertEquals("answer", typed.remoteEmbeds.questions.getValue("s").screen.questions.single().text)
        assertEquals("answer", typed.remoteEmbeds.checklists.getValue("c").screen.fields.single().text)
        assertSame(
            typed,
            typed.predict(
                AiStudioScreenIntent.QuestionInput(
                    "s",
                    "instance",
                    QuestionnaireScreenIntent.Submit("q"),
                ),
            ),
        )
        assertSame(
            typed,
            typed.predict(
                AiStudioScreenIntent.ChecklistInput(
                    "c",
                    "instance",
                    ChecklistScreenIntent.Complete,
                ),
            ),
        )
        assertSame(
            typed,
            typed.predict(
                AiStudioScreenIntent.QuestionInput(
                    "s",
                    "old",
                    QuestionnaireScreenIntent.TextChanged("q", "old"),
                ),
            ),
        )
        assertSame(
            typed,
            typed.predict(
                AiStudioScreenIntent.ChecklistInput(
                    "c",
                    "old",
                    ChecklistScreenIntent.Text("f", "old"),
                ),
            ),
        )
    }
}
