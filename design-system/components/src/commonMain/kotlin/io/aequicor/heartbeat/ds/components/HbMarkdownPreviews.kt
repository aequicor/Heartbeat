package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf

@Preview(name = "Markdown · light", widthDp = 560, heightDp = 600)
@Composable
private fun MarkdownLightPreview() {
    MarkdownPreview(isDark = false)
}

@Preview(name = "Markdown · dark", widthDp = 560, heightDp = 600)
@Composable
private fun MarkdownDarkPreview() {
    MarkdownPreview(isDark = true)
}

@Composable
private fun MarkdownPreview(isDark: Boolean) {
    HbTheme(darkTheme = isDark) {
        HbColumn(modifier = Modifier.background(HbTheme.colors.background).padding(HbTheme.spacing.xl)) {
            HbMarkdown(
                "## A thoughtful answer\n\n**Rich text**, *quiet emphasis* and `precise code`.\n\n" +
                    "```kotlin\n// A calm workspace\nval title = \"Studio\"\nStudioConversation(title, 24)\n```",
            )
            HbToolCallView(
                toolCall = HbToolCall(
                    id = "preview-tool",
                    title = "Review changes",
                    summary = "One small improvement, ready to review.",
                    blocks = persistentListOf(HbToolBlock.Diff("diff", "@@ -1 +1 @@\n-old idea\n+new idea")),
                ),
                isExpanded = true,
            )
        }
    }
}
