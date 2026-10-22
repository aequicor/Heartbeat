package io.aequicor.heartbeat.feature.questionnaire.screenmodel

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class QuestionnaireSerializationTest {
    private val json = Json { classDiscriminator = "@type" }

    @Test
    fun `single choice can be cleared for a text alternative and pages preserve drafts`() {
        val question = QuestionUi(
            "q",
            "Q",
            null,
            QuestionKindUi.Choice(persistentListOf(ChoiceUi("a", "A")), false),
            false,
            false,
            isFreeTextAllowed = true,
        )
        val initial = QuestionnaireScreenState(persistentListOf(question))
        val selected = initial.predictDraft(QuestionnaireScreenIntent.ToggleChoice("q", "a"))
        val written = selected.predictDraft(QuestionnaireScreenIntent.TextChanged("q", "Custom"))
        val cleared = written.predictDraft(QuestionnaireScreenIntent.ToggleChoice("q", "a"))
        assertEquals(emptySet(), cleared.questions.single().selected)
        assertEquals(true, cleared.questions.single().isAnswerReady)
        val paged = cleared.predictDraft(QuestionnaireScreenIntent.ShowQuestion("q"))
        assertEquals("Custom", paged.questions.single().text)
        assertEquals("q", paged.activeQuestionId)
        assertEquals(paged, json.decodeFromString<QuestionnaireScreenState>(json.encodeToString(paged)))
    }

    @Test
    fun `screen drafts and every input kind survive the remote wire`() {
        val kinds = listOf(
            QuestionKindUi.Confirm("Да", "Нет"),
            QuestionKindUi.Choice(persistentListOf(ChoiceUi("a", "Первый")), true, 0, 1),
            QuestionKindUi.Text("Ответ", true),
        )
        for (kind in kinds) {
            val state = QuestionnaireScreenState(
                persistentListOf(QuestionUi("q", "Вопрос", "Описание", kind, true, true, persistentSetOf("a"), "Я🚀")),
            )
            assertEquals(state, json.decodeFromString<QuestionnaireScreenState>(json.encodeToString(state)))
        }
        val intents = listOf(
            QuestionnaireScreenIntent.ShowQuestion("q"),
            QuestionnaireScreenIntent.ToggleChoice("q", "a"),
            QuestionnaireScreenIntent.TextChanged("q", "Я🚀"),
            QuestionnaireScreenIntent.Confirm("q", true),
            QuestionnaireScreenIntent.Submit("q"),
            QuestionnaireScreenIntent.Skip("q"),
        )
        for (intent in intents) {
            val encoded = json.encodeToString(QuestionnaireScreenIntent.serializer(), intent)
            assertEquals(intent, json.decodeFromString(QuestionnaireScreenIntent.serializer(), encoded))
        }
    }
}
