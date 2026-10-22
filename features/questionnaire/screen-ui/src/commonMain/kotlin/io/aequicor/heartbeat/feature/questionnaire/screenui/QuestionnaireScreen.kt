package io.aequicor.heartbeat.feature.questionnaire.screenui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChoiceRow
import io.aequicor.heartbeat.ds.components.HbQuestionnaireCard
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbBoxWithConstraints
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.Res
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_attention
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_continue
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_other_hint
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_page
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_pick_range
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_previous
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_sending
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_skip
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_submit
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_text_hint
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.screenmodel.QuestionnaireScreenState
import org.jetbrains.compose.resources.stringResource

/** One questionnaire panel with stable pages and independent drafts, shared by native and remote hosts. */
@Composable
fun QuestionnaireScreen(
    state: QuestionnaireScreenState,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.questions.isEmpty()) return
    HbBoxWithConstraints(modifier.fillMaxWidth()) {
        val descriptionHeight = if (constraints.hasBoundedHeight) {
            minOf(HbTheme.dimensions.toolPayloadMaxHeight, maxHeight / 3)
        } else {
            HbTheme.dimensions.toolPayloadMaxHeight
        }
        val index = state.questions.indexOfFirst { it.id == state.activeQuestionId }.coerceAtLeast(0)
        val question = state.questions[index]
        key(question.id) {
            HbColumn(
                Modifier.fillMaxWidth().hbVerticalScroll(rememberScrollState()).testTag("questionnaire"),
                gap = HbTheme.spacing.m,
            ) {
                QuestionCard(question, descriptionHeight, state.questions.size == 1, onIntent) {
                    if (state.questions.size > 1 || question.isSkippable) QuestionPages(state, index, onIntent)
                }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionUi,
    descriptionHeight: Dp,
    isLastQuestion: Boolean,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
    navigation: @Composable () -> Unit = {},
) {
    HbQuestionnaireCard(
        questionId = question.id,
        title = question.title,
        attentionLabel = stringResource(Res.string.questionnaire_attention),
        modifier = modifier,
        headerActions = navigation,
    ) {
        question.description?.takeIf { it.isNotBlank() }?.let {
            Box(
                Modifier.fillMaxWidth().heightIn(max = descriptionHeight)
                    .hbVerticalScroll(rememberScrollState()).focusable()
                    .testTag("question-description-scroll-${question.id}"),
            ) {
                HbText(
                    it,
                    Modifier.testTag("question-description-${question.id}"),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
            }
        }
        when (val kind = question.kind) {
            is QuestionKindUi.Confirm -> ConfirmActions(question, kind, onIntent)
            is QuestionKindUi.Choice -> ChoiceInput(question, kind, onIntent)
            is QuestionKindUi.Text -> Unit
        }
        if (question.kind !is QuestionKindUi.Confirm || question.isFreeTextAllowed) {
            AnswerInput(question, isLastQuestion, onIntent)
        }
    }
}

@Composable
private fun QuestionPages(state: QuestionnaireScreenState, index: Int, onIntent: (QuestionnaireScreenIntent) -> Unit) {
    val question = state.questions[index]
    HbRow(gap = HbTheme.spacing.m) {
        if (state.questions.size > 1) {
            HbButton(
                stringResource(Res.string.questionnaire_previous),
                onClick = { onIntent(QuestionnaireScreenIntent.ShowQuestion(state.questions[index - 1].id)) },
                modifier = Modifier.testTag("questionnaire-previous"),
                style = HbButtonStyle.Ghost,
                enabled = index > 0,
            )
            HbText(
                stringResource(Res.string.questionnaire_page, index + 1, state.questions.size),
                modifier = Modifier.testTag("questionnaire-page"),
                style = HbTheme.typography.caption,
            )
        }
        HbButton(
            stringResource(Res.string.questionnaire_skip),
            onClick = { onIntent(QuestionnaireScreenIntent.Skip(question.id)) },
            modifier = Modifier.testTag("question-skip-${question.id}"),
            style = HbButtonStyle.Ghost,
            enabled = question.isSkippable && !question.isSubmitting,
        )
    }
}

@Composable
private fun ConfirmActions(
    question: QuestionUi,
    kind: QuestionKindUi.Confirm,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
) {
    if (question.isFreeTextAllowed) {
        ConfirmationChoices(question, kind, onIntent)
        return
    }
    HbFlowRow(gap = HbTheme.spacing.m) {
        HbButton(
            kind.yes,
            onClick = { onIntent(QuestionnaireScreenIntent.Confirm(question.id, true)) },
            modifier = Modifier.testTag("question-yes-${question.id}"),
            enabled = !question.isSubmitting,
        )
        HbButton(
            kind.no,
            onClick = { onIntent(QuestionnaireScreenIntent.Confirm(question.id, false)) },
            modifier = Modifier.testTag("question-no-${question.id}"),
            style = HbButtonStyle.Secondary,
            enabled = !question.isSubmitting,
        )
    }
}

@Composable
private fun ConfirmationChoices(
    question: QuestionUi,
    kind: QuestionKindUi.Confirm,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
) {
    HbColumn(Modifier.selectableGroup(), gap = HbTheme.spacing.s) {
        listOf(true to kind.yes, false to kind.no).forEach { (value, label) ->
            QuestionOption(
                question,
                label,
                "question-${if (value) "yes" else "no"}-${question.id}",
                question.isConfirmed == value,
                isMultiple = false,
            ) { onIntent(QuestionnaireScreenIntent.Confirm(question.id, value)) }
        }
    }
}

@Composable
private fun ChoiceInput(
    question: QuestionUi,
    kind: QuestionKindUi.Choice,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
) {
    if (kind.isMultiple) {
        HbText(
            stringResource(Res.string.questionnaire_pick_range, kind.min, kind.max),
            style = HbTheme.typography.caption,
            color = HbTheme.colors.textSecondary,
        )
    }
    HbColumn(Modifier.selectableGroup(), gap = HbTheme.spacing.s) {
        kind.choices.forEach { choice ->
            QuestionOption(
                question,
                choice.title,
                "question-choice-${question.id}-${choice.id}",
                choice.id in question.selected,
                isMultiple = kind.isMultiple,
            ) { onIntent(QuestionnaireScreenIntent.ToggleChoice(question.id, choice.id)) }
        }
    }
}

@Composable
private fun QuestionOption(
    question: QuestionUi,
    label: String,
    tag: String,
    isSelected: Boolean,
    isMultiple: Boolean,
    onClick: () -> Unit,
) {
    HbChoiceRow(
        label = label,
        selected = isSelected,
        isMultiple = isMultiple,
        onClick = onClick,
        modifier = Modifier.testTag(tag),
        enabled = !question.isSubmitting,
    )
}

@Composable
private fun TextInput(
    question: QuestionUi,
    kind: QuestionKindUi.Text,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbTextField(
        value = question.text,
        onValueChange = { onIntent(QuestionnaireScreenIntent.TextChanged(question.id, it)) },
        modifier = modifier.fillMaxWidth().testTag("question-text-${question.id}"),
        placeholder = kind.placeholder ?: stringResource(Res.string.questionnaire_text_hint),
        enabled = !question.isSubmitting,
        singleLine = !kind.isMultiline || question.text.isEmpty(),
    )
}

@Composable
private fun AnswerInput(question: QuestionUi, isLastQuestion: Boolean, onIntent: (QuestionnaireScreenIntent) -> Unit) {
    HbRow(Modifier.fillMaxWidth(), gap = HbTheme.spacing.m, verticalAlignment = Alignment.Bottom) {
        val textKind = question.kind as? QuestionKindUi.Text
        if (textKind != null || question.isFreeTextAllowed) {
            TextInput(
                question,
                textKind ?: QuestionKindUi.Text(
                    stringResource(Res.string.questionnaire_other_hint),
                    isMultiline = true,
                ),
                onIntent,
                Modifier.weight(1f),
            )
        }
        HbButton(
            stringResource(
                when {
                    question.isSubmitting -> Res.string.questionnaire_sending
                    isLastQuestion -> Res.string.questionnaire_submit
                    else -> Res.string.questionnaire_continue
                },
            ),
            onClick = { onIntent(QuestionnaireScreenIntent.Submit(question.id)) },
            modifier = Modifier.testTag("question-submit-${question.id}"),
            enabled = question.isAnswerReady,
        )
    }
}
