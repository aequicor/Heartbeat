package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme

@Preview(name = "Questionnaire · light", widthDp = 420)
@Composable
private fun QuestionnaireLightPreview() {
    HbTheme(darkTheme = false) { QuestionnairePreviewContent() }
}

@Preview(name = "Questionnaire · dark", widthDp = 420)
@Composable
private fun QuestionnaireDarkPreview() {
    HbTheme(darkTheme = true) { QuestionnairePreviewContent() }
}

@Composable
private fun QuestionnairePreviewContent() {
    HbQuestionnaireCard(
        questionId = "preview",
        title = "Зигота просит разрешение сохранить инструкцию для этого проекта",
        attentionLabel = "Нужен ваш ответ",
        modifier = Modifier.padding(HbTheme.spacing.m),
    ) {
        HbText("Полный текст запроса доступен до принятия решения.")
        HbFlowRow {
            HbButton("Разрешить сохранение инструкции", {})
            HbButton("Запретить", {}, style = HbButtonStyle.Secondary)
            HbButton("Отправка ответа…", {}, enabled = false)
        }
    }
}
