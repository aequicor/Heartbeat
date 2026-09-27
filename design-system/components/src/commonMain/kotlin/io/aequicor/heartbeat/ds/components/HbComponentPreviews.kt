package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.layouts.HbFlowRow
import io.aequicor.heartbeat.ds.theme.HbTheme

@Preview
@Composable
private fun LightComponentsPreview() {
    HbTheme(darkTheme = false) { ComponentPreviewContent() }
}

@Preview
@Composable
private fun DarkComponentsPreview() {
    HbTheme(darkTheme = true) { ComponentPreviewContent() }
}

@Composable
private fun ComponentPreviewContent() {
    HbColumn(modifier = Modifier.padding(HbTheme.spacing.l)) {
        HbCard {
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
            ),
            streamingLabel = "Generating…",
        )
        HbChatMessageBubble(
            message = HbChatMessage(
                "preview-user",
                "You",
                "Build a flexible studio.",
                role = HbChatRole.User,
                appearance = HbMessageAppearance(tone = HbTone.Brand),
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
            placeholder = "Message the agent",
        )
    }
}
