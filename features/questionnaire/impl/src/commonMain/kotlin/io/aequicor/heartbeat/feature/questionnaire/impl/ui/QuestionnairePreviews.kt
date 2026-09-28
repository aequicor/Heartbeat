package io.aequicor.heartbeat.feature.questionnaire.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.ChoiceUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

private val PreviewState = QuestionnaireScreenState(
    persistentListOf(
        QuestionUi(
            "pick",
            "Which database?",
            "The agent will set up the schema.",
            QuestionKindUi.Choice(persistentListOf(ChoiceUi("pg", "Postgres"), ChoiceUi("sq", "SQLite")), false),
            isSkippable = true,
            isSubmitting = false,
            selected = persistentSetOf("pg"),
        ),
        QuestionUi("go", "Run migrations?", null, QuestionKindUi.Confirm("Yes", "No"), false, false),
        QuestionUi("name", "Project name", null, QuestionKindUi.Text(null, false), true, false),
    ),
)

@Preview
@Composable
private fun QuestionnaireLightPreview() {
    HbTheme(darkTheme = false) { QuestionnaireScreen(PreviewState, {}) }
}

@Preview
@Composable
private fun QuestionnaireDarkPreview() {
    HbTheme(darkTheme = true) { QuestionnaireScreen(PreviewState, {}) }
}
