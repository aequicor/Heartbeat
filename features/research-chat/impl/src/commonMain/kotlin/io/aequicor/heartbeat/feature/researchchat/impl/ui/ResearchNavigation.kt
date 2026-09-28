package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.aequicor.heartbeat.ds.components.HbActivityIndicator
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbNavigationItem
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.resources.Res
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_new_question
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_new_session
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_no_questions
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_no_sessions
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ResearchSessions(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag("research-sessions")) {
        HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            HbLazyColumn(Modifier.weight(1f).fillMaxWidth(), gap = HbTheme.spacing.xs) {
                if (state.sessions.isEmpty()) {
                    item {
                        HbText(
                            stringResource(Res.string.research_no_sessions),
                            color = HbTheme.colors.textSecondary,
                        )
                    }
                }
                items(state.sessions, key = { it.id }) { session ->
                    HbNavigationItem(
                        label = session.title.ifBlank { stringResource(Res.string.research_new_session) },
                        onClick = { onIntent(ResearchScreenIntent.SelectSession(session.id)) },
                        modifier = Modifier.testTag("research-session-${session.id}"),
                        minHeight = HbTheme.studioDimensions.navigationRowHeight,
                        isSelected = session.isSelected,
                    )
                }
            }
            HbButton(
                stringResource(Res.string.research_new_session),
                { onIntent(ResearchScreenIntent.NewSession) },
                Modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("research-new-session"),
                style = HbButtonStyle.Secondary,
                enabled = state.isEditable,
            )
        }
    }
}

@Composable
internal fun ResearchQuestions(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier.testTag("research-questions")) {
        HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            HbLazyColumn(Modifier.weight(1f).fillMaxWidth(), gap = HbTheme.spacing.xs) {
                if (state.questions.isEmpty()) {
                    item {
                        HbText(
                            stringResource(Res.string.research_no_questions),
                            color = HbTheme.colors.textSecondary,
                        )
                    }
                }
                items(state.questions, key = { it.id }) { question ->
                    HbNavigationItem(
                        label = question.title.ifBlank { stringResource(Res.string.research_new_question) },
                        onClick = { onIntent(ResearchScreenIntent.SelectQuestion(question.id)) },
                        modifier = Modifier.testTag("research-question-${question.id}"),
                        minHeight = HbTheme.studioDimensions.navigationRowHeight,
                        isSelected = question.isSelected,
                        trailingContent = {
                            if (question.isRunning) HbActivityIndicator(size = HbTheme.dimensions.iconSmallSize)
                        },
                    )
                }
            }
            HbButton(
                stringResource(Res.string.research_new_question),
                { onIntent(ResearchScreenIntent.NewQuestion) },
                Modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("research-new-question"),
                style = HbButtonStyle.Secondary,
                enabled = state.isEditable && state.sessions.any { it.isSelected },
            )
        }
    }
}
