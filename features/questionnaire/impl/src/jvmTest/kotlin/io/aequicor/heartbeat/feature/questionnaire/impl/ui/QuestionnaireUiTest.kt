package io.aequicor.heartbeat.feature.questionnaire.impl.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.ChoiceUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class QuestionnaireUiTest {
    private val state = QuestionnaireScreenState(
        persistentListOf(
            QuestionUi(
                "pick",
                "Which?",
                "Details",
                QuestionKindUi.Choice(persistentListOf(ChoiceUi("a", "A"), ChoiceUi("b", "B")), isMultiple = false),
                isSkippable = true,
                isSubmitting = false,
                selected = persistentSetOf("b"),
            ),
            QuestionUi("go", "Go?", null, QuestionKindUi.Confirm("Yes", "No"), false, false),
            QuestionUi("name", "Name", null, QuestionKindUi.Text(null, false), false, false),
        ),
    )

    @Test
    fun `choices confirmations and text are forwarded as intents`() = runSkikoComposeUiTest {
        val events = mutableListOf<QuestionnaireScreenIntent>()
        setContent { HbTheme { QuestionnaireScreen(state, { events += it }) } }
        onNodeWithTag("question-choice-pick-a").performClick()
        onNodeWithTag("question-submit-pick").assertIsEnabled().performClick()
        onNodeWithTag("question-skip-pick").performClick()
        onNodeWithTag("question-yes-go").performClick()
        onNodeWithTag("question-submit-name").assertIsNotEnabled()
        onNodeWithTag("question-text-name").performTextInput("x")
        assertEquals(
            listOf(
                QuestionnaireScreenIntent.ToggleChoice("pick", "a"),
                QuestionnaireScreenIntent.Submit("pick"),
                QuestionnaireScreenIntent.Skip("pick"),
                QuestionnaireScreenIntent.Confirm("go", true),
                QuestionnaireScreenIntent.TextChanged("name", "x"),
            ),
            events,
        )
    }
}
