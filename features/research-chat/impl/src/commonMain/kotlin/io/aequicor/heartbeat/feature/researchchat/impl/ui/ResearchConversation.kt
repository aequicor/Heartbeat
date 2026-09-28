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
import io.aequicor.heartbeat.ds.components.HbComposerToggle
import io.aequicor.heartbeat.ds.components.HbIcon
import io.aequicor.heartbeat.ds.components.HbIcons
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
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_mode
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_new_question
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_new_session
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_previous_run_failed
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_send
import io.aequicor.heartbeat.feature.researchchat.impl.resources.research_stop
import org.jetbrains.compose.resources.stringResource

/**
 * The research conversation laid out like a studio chat: a centered transcript column and the same composer, where
 * the checked "Research" toggle returns the chat area to the regular chat and [panelToggle] shows the side panel.
 */
@Composable
internal fun ResearchConversation(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    panelToggle: (@Composable () -> Unit)? = null,
) {
    val column = Modifier.widthIn(max = HbTheme.studioDimensions.composerMaxWidth).fillMaxWidth()
    HbColumn(
        modifier.testTag("research-conversation"),
        gap = HbTheme.spacing.none,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ResearchContextLine(state, column.padding(horizontal = HbTheme.spacing.xl, vertical = HbTheme.spacing.s))
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (state.messages.isEmpty()) {
                ResearchEmptyConversation(Modifier.fillMaxSize())
            } else {
                key(state.questionId) {
                    ResearchTranscript(
                        state,
                        Modifier.fillMaxSize().widthIn(max = HbTheme.studioDimensions.messageMaxWidth),
                    )
                }
            }
        }
        HbColumn(
            Modifier.fillMaxWidth().padding(horizontal = HbTheme.spacing.xl, vertical = HbTheme.spacing.m),
            gap = HbTheme.spacing.s,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (state.hasQuestionFailed) {
                HbText(
                    stringResource(Res.string.research_previous_run_failed),
                    column.semantics { liveRegion = LiveRegionMode.Polite }.testTag("research-previous-run-failed"),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.error,
                )
            }
            ResearchComposer(state, onIntent, onClose, panelToggle, column)
        }
    }
}

/** Session and question of the conversation, quiet above the transcript; the studio header names the mode. */
@Composable
private fun ResearchContextLine(state: ResearchScreenState, modifier: Modifier = Modifier) {
    val session = state.sessionTitle.ifBlank { stringResource(Res.string.research_new_session) }
    val question = state.questionTitle.ifBlank { stringResource(Res.string.research_new_question) }
    // The first question usually names its session; repeating the same text reads as a glitch.
    val context = if (session == question) question else "$session · $question"
    HbText(
        context,
        modifier.testTag("research-context"),
        style = HbTheme.typography.caption,
        color = HbTheme.colors.textSecondary,
        maxLines = 1,
        isOverflowTooltipEnabled = true,
    )
}

@Composable
private fun ResearchComposer(
    state: ResearchScreenState,
    onIntent: (ResearchScreenIntent) -> Unit,
    onClose: () -> Unit,
    panelToggle: (@Composable () -> Unit)?,
    modifier: Modifier = Modifier,
) {
    HbChatComposer(
        value = state.draft,
        onValueChange = { onIntent(ResearchScreenIntent.DraftChanged(it)) },
        onSend = { onIntent(ResearchScreenIntent.Submit) },
        onStop = { onIntent(ResearchScreenIntent.Stop) },
        sendLabel = stringResource(Res.string.research_send),
        stopLabel = stringResource(Res.string.research_stop),
        modifier = modifier.testTag("research-composer"),
        layout = HbComposerLayout.Panel,
        inputMaxHeight = HbTheme.studioDimensions.editorMaxHeight,
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
            HbComposerToggle(
                label = stringResource(Res.string.research_mode),
                isChecked = true,
                onCheckedChange = { if (!it) onClose() },
                modifier = Modifier.testTag("research-mode"),
                icon = HbIcons.Library,
            )
        },
        trailingContent = { panelToggle?.invoke() },
    )
}

@Composable
private fun ResearchEmptyConversation(modifier: Modifier = Modifier) {
    Box(modifier.hbVerticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        HbColumn(
            Modifier.padding(HbTheme.spacing.xxl).widthIn(max = HbTheme.studioDimensions.composerMaxWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HbIcon(HbIcons.Library, contentDescription = null, tint = HbTheme.colors.primary)
            HbText(stringResource(Res.string.research_empty_chat), style = HbTheme.typography.display)
            HbText(stringResource(Res.string.research_empty_hint), color = HbTheme.colors.textSecondary)
        }
    }
}
