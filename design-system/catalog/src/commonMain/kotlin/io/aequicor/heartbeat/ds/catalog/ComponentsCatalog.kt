package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.aequicor.heartbeat.ds.components.HbBadge
import io.aequicor.heartbeat.ds.components.HbButton
import io.aequicor.heartbeat.ds.components.HbButtonStyle
import io.aequicor.heartbeat.ds.components.HbCard
import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatMessageBubble
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbDiffView
import io.aequicor.heartbeat.ds.components.HbDivider
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbPanel
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.resources.HbString
import io.aequicor.heartbeat.ds.resources.hbString
import io.aequicor.heartbeat.ds.theme.HbTheme

@Composable
internal fun ComponentsCatalog(state: SandboxState, modifier: Modifier = Modifier) {
    HbLazyColumn(modifier = modifier) {
        item { CatalogHeading(HbString.ComponentsTitle, HbString.ComponentsDescription) }
        item { CinematicExample() }
        item { PanelExample(modifier = Modifier.fillMaxWidth()) }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                HbText(hbString(HbString.Actions), style = HbTheme.typography.title)
                HbFlowRow {
                    HbButton(hbString(HbString.Create), state::showActionFeedback)
                    HbButton(
                        hbString(HbString.SecondaryAction),
                        state::showActionFeedback,
                        style = HbButtonStyle.Secondary,
                    )
                    HbButton(hbString(HbString.Disabled), state::showActionFeedback, enabled = false)
                    HbButton(
                        hbString(HbString.QuietAction),
                        state::showActionFeedback,
                        style = HbButtonStyle.Quiet,
                    )
                }
                if (state.hasActionFeedback) HbBadge(hbString(HbString.ActionFeedback), tone = HbTone.Success)
            }
        }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                HbText(hbString(HbString.Inputs), style = HbTheme.typography.title)
                HbTextField(
                    value = state.input,
                    onValueChange = state::updateInput,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = hbString(HbString.InputPlaceholder),
                )
                HbText(
                    hbString(HbString.InputHint),
                    style = HbTheme.typography.caption,
                    color = HbTheme.colors.textSecondary,
                )
            }
        }
        item {
            HbCard(modifier = Modifier.fillMaxWidth()) {
                HbText(hbString(HbString.Statuses), style = HbTheme.typography.title)
                HbFlowRow {
                    HbBadge(hbString(HbString.Neutral))
                    HbBadge(hbString(HbString.Working), tone = HbTone.Brand)
                    HbBadge(hbString(HbString.Ready), tone = HbTone.Success)
                    HbBadge(hbString(HbString.NeedsAttention), tone = HbTone.Warning)
                    HbBadge(hbString(HbString.Failed), tone = HbTone.Danger)
                }
            }
        }
        item { DiffExample(modifier = Modifier.fillMaxWidth()) }
        item { MessageExamples(modifier = Modifier.fillMaxWidth()) }
        item { NavigationExample(modifier = Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun PanelExample(modifier: Modifier = Modifier) {
    HbPanel(modifier = modifier) {
        HbColumn(modifier = Modifier.fillMaxWidth(), gap = HbTheme.elevation.none) {
            HbText(
                hbString(HbString.Chat),
                modifier = Modifier.padding(HbTheme.spacing.m),
                style = HbTheme.typography.label,
            )
            HbDivider()
            HbText(hbString(HbString.SeedPrompt), modifier = Modifier.padding(HbTheme.spacing.l))
        }
    }
}

@Composable
private fun DiffExample(modifier: Modifier = Modifier) {
    HbCard(modifier = modifier) {
        HbText(hbString(HbString.ToolDiff), style = HbTheme.typography.title)
        HbDiffView(text = STUDIO_DIFF_SAMPLE, labels = catalogToolLabels())
    }
}

@Composable
private fun MessageExamples(modifier: Modifier = Modifier) {
    HbCard(modifier = modifier) {
        HbText(hbString(HbString.Chat), style = HbTheme.typography.title)
        HbChatMessageBubble(
            message = HbChatMessage(
                id = "example-code",
                author = hbString(HbString.Agent),
                text = hbString(HbString.CodeSample),
                kind = HbMessageKind.Code,
                codeLanguage = "kotlin",
                label = hbString(HbString.CodeLabel),
            ),
        )
        HbChatMessageBubble(
            message = HbChatMessage(
                id = "example-notice",
                author = hbString(HbString.SystemAuthor),
                text = hbString(HbString.NoticeText),
                kind = HbMessageKind.Notice,
                role = HbChatRole.System,
            ),
        )
        HbChatMessageBubble(
            message = HbChatMessage(
                id = "example-error",
                author = hbString(HbString.Tool),
                text = hbString(HbString.NeedsAttention),
                kind = HbMessageKind.Tool,
                role = HbChatRole.Tool,
                status = HbMessageStatus.Error,
                label = hbString(HbString.Failed),
                appearance = HbMessageAppearance(tone = HbTone.Danger),
            ),
        )
    }
}
