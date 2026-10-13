package io.aequicor.heartbeat.feature.questionnaire.screenui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.ChoiceUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

private val PreviewState = QuestionnaireScreenState(
    persistentListOf(
        QuestionUi(
            "pick",
            "Какую базу данных использовать для проекта?",
            "Агент ожидает ваш ответ, чтобы продолжить настройку приложения.",
            QuestionKindUi.Choice(
                persistentListOf(
                    ChoiceUi("pg", "PostgreSQL для рабочего окружения"),
                    ChoiceUi("sq", "SQLite для локального прототипа"),
                ),
                false,
            ),
            isSkippable = true,
            isSubmitting = false,
            selected = persistentSetOf("pg"),
        ),
        QuestionUi(
            "go",
            "Запустить миграции базы данных?",
            null,
            QuestionKindUi.Confirm("Разрешить запуск миграций", "Отложить до проверки изменений"),
            false,
            false,
        ),
        QuestionUi(
            "name",
            "Как назвать новое рабочее пространство?",
            null,
            QuestionKindUi.Text("Название рабочего пространства", false),
            true,
            false,
        ),
    ),
)

@Preview
@Composable
private fun QuestionnaireLightPreview() {
    HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) { QuestionnaireScreen(PreviewState, {}) }
}

@Preview
@Composable
private fun QuestionnaireDarkPreview() {
    HbTheme(darkTheme = true, motion = HbMotion(isReducedMotion = true)) { QuestionnaireScreen(PreviewState, {}) }
}
