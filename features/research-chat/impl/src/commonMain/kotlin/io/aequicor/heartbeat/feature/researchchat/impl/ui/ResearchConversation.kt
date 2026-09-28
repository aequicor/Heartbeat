package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbChatComposer
import io.aequicor.heartbeat.ds.components.HbComposerIconButton
import io.aequicor.heartbeat.ds.components.HbComposerLayout
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.hbVerticalScroll
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.resources.Res
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_add_source
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_composer_hint
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_empty_chat
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_empty_hint
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_new_question
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_new_session
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_previous_run_failed
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_send
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_stop
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun ResearchConversation(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    HbPanel(modifier.testTag("research-conversation")) {
        HbColumn(Modifier.fillMaxSize(), gap = HbTheme.spacing.none) {
            HbColumn(Modifier.fillMaxWidth().padding(HbTheme.spacing.l), gap = HbTheme.spacing.xxs) {
                HbText(
                    state.sessionTitle.ifBlank { stringResource(Res.string.research_new_session) },
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                    maxLines = 1,
                )
                HbText(
                    state.questionTitle.ifBlank { stringResource(Res.string.research_new_question) },
                    style = HbTheme.typography.label,
                    maxLines = 2,
                )
            }
            HbDivider()
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.messages.isEmpty()) {
                    ResearchEmptyConversation(Modifier.fillMaxSize())
                } else {
                    key(state.questionId) {
                        ResearchTranscript(
                            state,
                            Modifier.fillMaxSize().widthIn(max = HbTheme.dimensions.chatMessageMaxWidth),
                        )
                    }
                }
            }
            if (state.hasQuestionFailed) {
                HbText(
                    stringResource(Res.string.research_previous_run_failed),
                    Modifier.padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.s)
                        .semantics { liveRegion = LiveRegionMode.Polite }.testTag("research-previous-run-failed"),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.error,
                )
            }
            HbChatComposer(
                value = state.draft,
                onValueChange = { onIntent(ResearchScreenIntent.DraftChanged(it)) },
                onSend = { onIntent(ResearchScreenIntent.Submit) },
                onStop = { onIntent(ResearchScreenIntent.Stop) },
                sendLabel = stringResource(Res.string.research_send),
                stopLabel = stringResource(Res.string.research_stop),
                modifier = Modifier.fillMaxWidth().padding(HbTheme.spacing.m).testTag("research-composer"),
                layout = HbComposerLayout.Panel,
                placeholder = stringResource(Res.string.research_composer_hint),
                isStreaming = state.isRunning,
                enabled = state.isEditable || state.isRunning,
                leadingContent = {
                    HbComposerIconButton(
                        icon = HbIcons.Paperclip,
                        contentDescription = stringResource(Res.string.research_add_source),
                        onClick = { onIntent(ResearchScreenIntent.ShowResourceDialog(true)) },
                        modifier = Modifier.testTag("research-attach-source"),
                        enabled = state.isEditable,
                    )
                },
            )
        }
    }
}

@Composable
private fun ResearchEmptyConversation(modifier: Modifier = Modifier) {
    Box(modifier.hbVerticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        HbColumn(Modifier.padding(HbTheme.spacing.xxl), horizontalAlignment = Alignment.CenterHorizontally) {
            HbIcon(HbIcons.Library, contentDescription = null, tint = HbTheme.colors.primary)
            HbText(stringResource(Res.string.research_empty_chat), style = HbTheme.typography.title)
            HbText(stringResource(Res.string.research_empty_hint), color = HbTheme.colors.textSecondary)
        }
    }
}
