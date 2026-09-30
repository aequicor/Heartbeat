package io.aequicor.heartbeat.feature.questionnaire.impl.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChip
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.theme.HbQuestionnaireTheme
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionKindUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionUi
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenIntent
import io.aequicor.heartbeat.feature.questionnaire.impl.presentation.store.QuestionnaireScreenState
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.Res
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_attention
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_pick_range
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_selected
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_sending
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_skip
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_submit
import io.aequicor.heartbeat.feature.questionnaire.impl.resources.questionnaire_text_hint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/** Pending questions on dark oak attention surfaces; renders nothing when the source asks nothing. */
@Composable
internal fun QuestionnaireScreen(
    state: QuestionnaireScreenState,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state.questions.isEmpty()) return
    HbQuestionnaireTheme {
        HbColumn(modifier.fillMaxWidth().testTag("questionnaire"), gap = HbTheme.spacing.m) {
            state.questions.forEach { question ->
                key(question.id) { QuestionCard(question, onIntent) }
            }
        }
    }
}

@Composable
private fun QuestionCard(
    question: QuestionUi,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = HbTheme.motion
    val entrance = remember { Animatable(if (motion.isReducedMotion) 1f else 0f) }
    val attention = remember { Animatable(0f) }
    LaunchedEffect(question.id, motion) {
        if (motion.isReducedMotion) {
            entrance.snapTo(1f)
            attention.snapTo(0f)
        } else {
            coroutineScope {
                launch { entrance.animateTo(1f, tween(motion.slowMillis)) }
                repeat(motion.questionnairePulseCount) {
                    attention.animateTo(1f, tween(motion.questionnairePulseMillis))
                    attention.animateTo(0f, tween(motion.questionnairePulseMillis))
                }
            }
        }
    }
    val colors = HbTheme.colors
    val entryOffset = HbTheme.spacing.l
    HbColumn(
        modifier.fillMaxWidth().testTag("question-${question.id}")
            .graphicsLayer { translationY = entryOffset.toPx() * (1f - entrance.value) }
            .background(colors.surface, HbTheme.shapes.medium)
            .border(
                HbTheme.dimensions.borderWidth,
                lerp(colors.outlineSubtle, colors.primary, attention.value),
                HbTheme.shapes.medium,
            )
            .clip(HbTheme.shapes.medium),
        gap = HbTheme.spacing.none,
    ) {
        HbRow(
            Modifier.fillMaxWidth().height(HbTheme.dimensions.questionnaireAccentHeight)
                .background(lerp(colors.primary, colors.textPrimary, attention.value))
                .testTag("question-accent-${question.id}"),
        ) {}
        HbColumn(Modifier.padding(HbTheme.spacing.l), gap = HbTheme.spacing.m) {
            HbRow(
                Modifier.testTag("question-attention-${question.id}"),
                gap = HbTheme.spacing.s,
            ) {
                HbIcon(HbIcons.Chat, contentDescription = null, tint = colors.primary)
                HbText(
                    stringResource(Res.string.questionnaire_attention),
                    style = HbTheme.typography.caption,
                    color = colors.primary,
                )
            }
            HbText(
                question.title,
                modifier = Modifier.semantics {
                    heading()
                    liveRegion = LiveRegionMode.Polite
                },
                style = HbTheme.typography.label,
            )
            question.description?.let {
                HbText(it, style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
            }
            when (val kind = question.kind) {
                is QuestionKindUi.Confirm -> ConfirmActions(question, kind, onIntent)
                is QuestionKindUi.Choice -> ChoiceInput(question, kind, onIntent)
                is QuestionKindUi.Text -> TextInput(question, kind, onIntent)
            }
            if (question.kind !is QuestionKindUi.Confirm) AnswerActions(question, onIntent)
        }
    }
}

@Composable
private fun ConfirmActions(
    question: QuestionUi,
    kind: QuestionKindUi.Confirm,
    onIntent: (QuestionnaireScreenIntent) -> Unit,
) {
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
    HbFlowRow(gap = HbTheme.spacing.s) {
        kind.choices.forEach { choice ->
            val isSelected = choice.id in question.selected
            HbChip(
                label = choice.title,
                modifier = Modifier
                    .semantics { selected = isSelected }
                    .testTag("question-choice-${question.id}-${choice.id}"),
                icon = if (isSelected) HbIcons.Check else null,
                onClick = if (question.isSubmitting) {
                    null
                } else {
                    { onIntent(QuestionnaireScreenIntent.ToggleChoice(question.id, choice.id)) }
                },
                trailingIcon = null,
                accessibleLabel = if (isSelected) {
                    stringResource(Res.string.questionnaire_selected, choice.title)
                } else {
                    choice.title
                },
            )
        }
    }
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
        singleLine = !kind.isMultiline,
    )
}

@Composable
private fun AnswerActions(question: QuestionUi, onIntent: (QuestionnaireScreenIntent) -> Unit) {
    HbFlowRow(gap = HbTheme.spacing.m) {
        HbButton(
            stringResource(
                if (question.isSubmitting) Res.string.questionnaire_sending else Res.string.questionnaire_submit,
            ),
            onClick = { onIntent(QuestionnaireScreenIntent.Submit(question.id)) },
            modifier = Modifier.testTag("question-submit-${question.id}"),
            enabled = question.isAnswerReady,
        )
        if (question.isSkippable) {
            HbButton(
                stringResource(Res.string.questionnaire_skip),
                onClick = { onIntent(QuestionnaireScreenIntent.Skip(question.id)) },
                modifier = Modifier.testTag("question-skip-${question.id}"),
                style = HbButtonStyle.Ghost,
                enabled = !question.isSubmitting,
            )
        }
    }
}
