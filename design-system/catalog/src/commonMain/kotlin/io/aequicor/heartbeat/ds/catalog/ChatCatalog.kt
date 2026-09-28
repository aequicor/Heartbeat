package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbChatTranscript
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbMessageAlignment
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbRow
import io.aequicor.heartbeat.ds.layouts.hbHorizontalScroll
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

@Composable
internal fun ChatCatalog(state: DemoChatState, copy: ChatDemoCopy, isCompact: Boolean, modifier: Modifier = Modifier) {
    HbColumn(modifier = modifier, gap = HbTheme.spacing.m) {
        ConversationPanel(
            state = state,
            copy = copy,
            isCompact = isCompact,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        SandboxComposer(
            state = state,
            copy = copy,
            isCompact = isCompact,
            modifier = Modifier.testTag("chat-composer"),
        )
    }
}

@Composable
private fun ConversationPanel(
    state: DemoChatState,
    copy: ChatDemoCopy,
    isCompact: Boolean,
    modifier: Modifier = Modifier,
) {
    HbPanel(modifier = modifier.testTag("chat-panel")) {
        HbColumn(modifier = Modifier.fillMaxSize(), gap = HbTheme.elevation.none) {
            ChatToolbar(
                state = state,
                copy = copy,
                isCompact = isCompact,
                modifier = Modifier.padding(horizontal = HbTheme.spacing.l, vertical = HbTheme.spacing.m)
                    .testTag("chat-toolbar"),
            )
            if (state.areControlsExpanded) {
                MessageControls(
                    state = state,
                    modifier = Modifier.padding(
                        start = HbTheme.spacing.l,
                        end = HbTheme.spacing.l,
                        bottom = HbTheme.spacing.m,
                    ).testTag("chat-controls"),
                )
            }
            HbDivider()
            HbChatTranscript(
                timeline = state.timeline,
                modifier = Modifier.weight(1f).fillMaxWidth().testTag("chat-transcript"),
                streamingLabel = hbString(HbString.Streaming),
                jumpToLatestLabel = hbString(HbString.JumpToLatest),
                messageAppearance = { message ->
                    if (message.role == HbChatRole.Assistant && message.kind != HbMessageKind.Tool) {
                        state.appearance
                    } else {
                        message.appearance
                    }
                },
                toolLabels = catalogToolLabels(),
            )
        }
    }
}

@Composable
private fun ChatToolbar(state: DemoChatState, copy: ChatDemoCopy, isCompact: Boolean, modifier: Modifier = Modifier) {
    HbRow(modifier = modifier.fillMaxWidth(), gap = HbTheme.spacing.s) {
        ChatStatusBadge(isStreaming = state.isStreaming || state.isLoadingHistory)
        Spacer(modifier = Modifier.weight(1f))
        if (!isCompact) {
            HbButton(
                text = hbString(HbString.LoadEarlier),
                onClick = { state.loadEarlier(copy) },
                enabled = !state.isLoadingHistory,
                style = HbButtonStyle.Ghost,
            )
        }
        HbButton(
            text = hbString(HbString.MessageStyle),
            onClick = state::toggleControls,
            style = HbButtonStyle.Ghost,
        )
        HbButton(hbString(HbString.Reset), { state.reset(copy) }, style = HbButtonStyle.Ghost)
    }
}

@Composable
private fun ChatStatusBadge(isStreaming: Boolean, modifier: Modifier = Modifier) {
    HbBadge(
        text = hbString(if (isStreaming) HbString.Working else HbString.Ready),
        modifier = modifier.testTag("chat-status").semantics { liveRegion = LiveRegionMode.Polite },
        tone = if (isStreaming) HbTone.Brand else HbTone.Success,
    )
}

@Composable
private fun MessageControls(state: DemoChatState, modifier: Modifier = Modifier) {
    val scrollState = rememberScrollState()
    val gutter = if (scrollState.maxValue in 1 until Int.MAX_VALUE) HbTheme.spacing.m else HbTheme.elevation.none
    HbRow(
        modifier = modifier.fillMaxWidth().hbHorizontalScroll(scrollState).padding(bottom = gutter),
        gap = HbTheme.spacing.m,
    ) {
        ControlGroup(HbString.Tone) {
            ChoiceButton(hbString(HbString.Neutral), state.appearance.tone == HbTone.Neutral, {
                state.updateAppearance(state.appearance.copy(tone = HbTone.Neutral))
            })
            ChoiceButton(hbString(HbString.Brand), state.appearance.tone == HbTone.Brand, {
                state.updateAppearance(state.appearance.copy(tone = HbTone.Brand))
            })
            ChoiceButton(hbString(HbString.Warning), state.appearance.tone == HbTone.Warning, {
                state.updateAppearance(state.appearance.copy(tone = HbTone.Warning))
            })
        }
        ControlGroup(HbString.Alignment) {
            ChoiceButton(hbString(HbString.Start), state.appearance.alignment == HbMessageAlignment.Start, {
                state.updateAppearance(state.appearance.copy(alignment = HbMessageAlignment.Start))
            })
            ChoiceButton(hbString(HbString.Center), state.appearance.alignment == HbMessageAlignment.Center, {
                state.updateAppearance(state.appearance.copy(alignment = HbMessageAlignment.Center))
            })
            ChoiceButton(hbString(HbString.End), state.appearance.alignment == HbMessageAlignment.End, {
                state.updateAppearance(state.appearance.copy(alignment = HbMessageAlignment.End))
            })
        }
        ControlGroup(HbString.Width) {
            ChoiceButton(hbString(HbString.Compact), state.appearance.widthFraction == COMPACT_WIDTH, {
                state.updateAppearance(state.appearance.copy(widthFraction = COMPACT_WIDTH))
            })
            ChoiceButton(hbString(HbString.Comfortable), state.appearance.widthFraction == COMFORTABLE_WIDTH, {
                state.updateAppearance(state.appearance.copy(widthFraction = COMFORTABLE_WIDTH))
            })
            ChoiceButton(hbString(HbString.Full), state.appearance.widthFraction == 1f, {
                state.updateAppearance(state.appearance.copy(widthFraction = 1f))
            })
        }
    }
}

@Composable
private fun ControlGroup(title: HbString, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    HbColumn(modifier = modifier, gap = HbTheme.spacing.xs) {
        HbText(hbString(title), style = HbTheme.typography.caption, color = HbTheme.colors.textSecondary)
        HbRow(gap = HbTheme.spacing.xs) { content() }
    }
}

private const val COMPACT_WIDTH = 0.6f
private const val COMFORTABLE_WIDTH = 0.86f
