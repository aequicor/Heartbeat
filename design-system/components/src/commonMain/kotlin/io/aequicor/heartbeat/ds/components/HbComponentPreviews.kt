package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbDimensions

@Preview
@Composable
private fun LightComponentsPreview() {
    HbTheme(darkTheme = false, dimensions = HbDimensions.Desktop) {
        ComponentPreviewContent()
    }
}

@Preview
@Composable
private fun DarkComponentsPreview() {
    HbTheme(darkTheme = true, dimensions = HbDimensions.Desktop) {
        ComponentPreviewContent()
    }
}

@Preview
@Composable
private fun MobileComponentsPreview() {
    HbTheme(dimensions = HbDimensions.Mobile) {
        ComponentPreviewContent()
    }
}

@Composable
private fun ComponentPreviewContent() {
    HbColumn(
        modifier = Modifier.background(HbTheme.colors.background).padding(HbTheme.spacing.xl),
        gap = HbTheme.spacing.xl,
    ) {
        HbColumn {
            HbText("Heartbeat", style = HbTheme.typography.title)
            HbFlowRow {
                HbButton("Primary", onClick = {})
                HbButton("Secondary", onClick = {}, style = HbButtonStyle.Secondary)
                HbBadge("Ready", tone = HbTone.Success)
            }
            HbTextField("", onValueChange = {}, placeholder = "Prompt")
        }
        HbChatMessageBubble(
            message = HbChatMessage(
                "preview-agent",
                "Agent",
                "A response arrives incrementally.",
                status = HbMessageStatus.Streaming,
                appearance = HbMessageAppearance(isUnified = true, widthFraction = 1f),
            ),
            streamingLabel = "Generating…",
        )
        HbChatMessageBubble(
            message = HbChatMessage(
                "preview-user",
                "You",
                "Build a flexible studio.",
                role = HbChatRole.User,
                appearance = HbMessageAppearance(
                    isContentWidth = true,
                    isAuthorVisible = false,
                    background = HbTheme.surfaces.outgoing,
                ),
            ),
        )
        HbChatComposer(
            value = "",
            onValueChange = {},
            onSend = {},
            onStop = {},
            sendLabel = "Send",
            stopLabel = "Stop",
            modifier = Modifier.fillMaxWidth(),
            layout = HbComposerLayout.Panel,
            inputMaxHeight = HbTheme.dimensions.editorMaxHeight,
            placeholder = "Message the agent",
            leadingContent = {
                HbComposerIconButton(HbIcons.Plus, "Prompt templates", {})
                HbComposerToggle("Research", isChecked = true, onCheckedChange = {}, icon = HbIcons.Library)
            },
        )
    }
}
