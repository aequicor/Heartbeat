package io.aequicor.heartbeat.ds.catalog

import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatRole
import io.aequicor.heartbeat.ds.components.HbChatSection
import io.aequicor.heartbeat.ds.components.HbChatTimeline
import io.aequicor.heartbeat.ds.components.HbMessageAppearance
import io.aequicor.heartbeat.ds.components.HbMessageKind
import io.aequicor.heartbeat.ds.components.HbTone
import io.aequicor.heartbeat.ds.components.HbToolBlock
import io.aequicor.heartbeat.ds.components.HbToolCall
import kotlinx.collections.immutable.persistentListOf

internal fun seedTimeline(copy: ChatDemoCopy): HbChatTimeline {
    val section = HbChatSection("current", copy.section)
    return HbChatTimeline.Empty
        .append(
            section,
            HbChatMessage(
                id = "sample-user",
                author = copy.user,
                text = copy.prompt,
                role = HbChatRole.User,
                appearance = HbMessageAppearance(tone = HbTone.Brand),
            ),
        )
        .append(section, markdownExample(copy, "sample-agent"))
        .append(section, toolExample(copy, "sample-tool"))
        .let { timeline ->
            if (copy.diagramSample.isBlank()) {
                timeline
            } else {
                timeline.append(section, diagramExample(copy, "sample-diagram"))
            }
        }
}

internal fun diagramExample(copy: ChatDemoCopy, id: String): HbChatMessage = HbChatMessage(
    id = id,
    author = copy.agent,
    text = copy.diagramSample,
    kind = HbMessageKind.Markdown,
)

internal fun markdownExample(copy: ChatDemoCopy, id: String): HbChatMessage = HbChatMessage(
    id = id,
    author = copy.agent,
    text = copy.reply,
    kind = HbMessageKind.Markdown,
)

internal fun toolExample(copy: ChatDemoCopy, id: String): HbChatMessage = HbChatMessage(
    id = id,
    author = copy.tool,
    text = "",
    role = HbChatRole.Tool,
    kind = HbMessageKind.Tool,
    appearance = HbMessageAppearance(widthFraction = 1f),
    toolCalls = persistentListOf(
        HbToolCall(
            id = "$id-inspect",
            title = "workspace.inspect",
            summary = copy.toolResult,
            blocks = persistentListOf(
                HbToolBlock.Markdown("summary", "### ${copy.codeLabel}\n\n${copy.notice}\n\n- **${copy.toolResult}**"),
                HbToolBlock.Console(
                    "console",
                    "$ ./gradlew :design-system:components:jvmTest\n" +
                        "> Task :design-system:components:jvmTest\n" +
                        "BUILD SUCCESSFUL\nexit code: 0",
                ),
                HbToolBlock.Diff(
                    "diff",
                    STUDIO_DIFF_SAMPLE,
                ),
            ),
        ),
    ),
)

internal const val STUDIO_DIFF_SAMPLE = "--- a/Studio.kt\n+++ b/Studio.kt\n@@ -1,3 +1,4 @@\n HbTheme {\n" +
    "-    PlainConversation()\n+    GlassConversation()\n+    StreamingTools()\n }"
