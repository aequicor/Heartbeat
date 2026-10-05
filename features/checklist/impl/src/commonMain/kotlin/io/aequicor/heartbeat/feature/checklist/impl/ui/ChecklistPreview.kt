package io.aequicor.heartbeat.feature.checklist.impl.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistChoiceUi
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistFieldUi
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistPhaseUi
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistScreenState
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

internal fun checklistSample() = ChecklistScreenState(
    title = "Ручная проверка изменений",
    phase = ChecklistPhaseUi.Open,
    isCompletionAllowed = true,
    fields = persistentListOf(
        ChecklistFieldUi(
            "check", "Проверьте сценарии", false, true, true, 1, 2,
            persistentListOf(
                ChecklistChoiceUi("light", "Светлая тема"),
                ChecklistChoiceUi("dark", "Тёмная тема и длинное название проверяемого сценария"),
            ),
            persistentSetOf("light"), "",
        ),
        ChecklistFieldUi(
            "result", "Результат проверки", false, false, true, 1, 2,
            persistentListOf(ChecklistChoiceUi("ok", "Всё работает"), ChecklistChoiceUi("no", "Есть дефекты")),
            persistentSetOf("ok"), "",
        ),
        ChecklistFieldUi(
            "text", "Комментарий", true, false, false, 0, 0,
            persistentListOf(), persistentSetOf(), "Проверено с клавиатуры",
        ),
    ),
)

@Preview
@Composable
private fun ChecklistLightPreview() {
    HbTheme(darkTheme = false) { ChecklistScreen(checklistSample(), {}) }
}

@Preview
@Composable
private fun ChecklistDarkPreview() {
    HbTheme(darkTheme = true) { ChecklistScreen(checklistSample(), {}) }
}
