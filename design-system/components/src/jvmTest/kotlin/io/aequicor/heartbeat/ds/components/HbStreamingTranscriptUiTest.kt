package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class HbStreamingTranscriptUiTest {
    @Test
    fun `streamed tool results can grow and shrink while following the latest reply`() = runSkikoComposeUiTest(
        size = Size(900f, 700f),
    ) {
        val section = HbChatSection("session", "")
        val reply = HbChatMessage("reply", "Agent", "", status = HbMessageStatus.Streaming)
        var timeline by mutableStateOf(
            HbChatTimeline.Empty.append(section, HbChatMessage("prompt", "You", "try again"))
                .append(section, reply),
        )
        setContent {
            HbTheme {
                HbChatTranscript(timeline, modifier = Modifier.fillMaxSize(), showSectionHeaders = false)
            }
        }
        repeat(30) { revision ->
            runOnIdle {
                timeline = timeline.replaceLatest(
                    reply.copy(
                        parts = persistentListOf(
                            HbMessagePart.Text("intro", "Retrying the interface operation."),
                            HbMessagePart.Tool(HbToolCall("get", "ui_get", status = HbToolStatus.Complete)),
                            HbMessagePart.Text("detail", "Preparing the interface.\n\nRevision $revision"),
                            HbMessagePart.Tool(
                                HbToolCall(
                                    "put",
                                    "ui_put",
                                    status = if (revision % 2 == 0) HbToolStatus.Running else HbToolStatus.Error,
                                ),
                            ),
                        ),
                    ),
                )
            }
            runOnIdle {
                timeline = timeline.replaceLatest(
                    reply.copy(
                        parts = persistentListOf(
                            HbMessagePart.Text("intro", "Retrying the interface operation."),
                            HbMessagePart.Tool(HbToolCall("get", "ui_get", status = HbToolStatus.Complete)),
                            HbMessagePart.Tool(HbToolCall("put", "ui_put", status = HbToolStatus.Error)),
                            HbMessagePart.Text("result", "The operation was rejected."),
                        ),
                    ),
                )
            }
        }
        onNodeWithText("The operation was rejected.").assertIsDisplayed()
    }
}
