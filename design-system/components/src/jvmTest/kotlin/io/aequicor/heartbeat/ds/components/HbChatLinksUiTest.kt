package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HbChatLinksUiTest {
    @Test
    fun `chat links open through the platform without a supplied callback`() {
        for (sectioned in listOf(false, true)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(900f, 700f)) {
                    val opened = mutableListOf<String>()
                    val handler = object : UriHandler {
                        override fun openUri(uri: String) {
                            opened += uri
                        }
                    }
                    val messages = persistentListOf(
                        HbChatMessage(
                            "answer",
                            "Assistant",
                            "[Website](https://example.test/page)",
                            kind = HbMessageKind.Markdown,
                            appearance = HbMessageAppearance(isUnified = sectioned),
                        ),
                    )
                    val timeline = HbChatTimeline.from(HbChatSection("chat", ""), messages)
                    setContent {
                        CompositionLocalProvider(LocalUriHandler provides handler) {
                            HbTheme(darkTheme = dark) {
                                if (sectioned) {
                                    HbChatTranscript(timeline, Modifier.fillMaxSize())
                                } else {
                                    HbChatTranscript(messages, Modifier.fillMaxSize())
                                }
                            }
                        }
                    }
                    onNodeWithText("Website").clickLinkText()
                    runOnIdle { assertEquals(listOf("https://example.test/page"), opened) }
                }
            }
        }
    }

    @Test
    fun `explicit callback receives local links instead of the platform`() =
        runSkikoComposeUiTest(size = Size(900f, 700f)) {
            val opened = mutableListOf<String>()
            val message = HbChatMessage(
                "answer",
                "Assistant",
                "[Source](/project/main.kt:12)",
                kind = HbMessageKind.Markdown,
            )
            val timeline = HbChatTimeline.from(HbChatSection("chat", ""), persistentListOf(message))
            setContent {
                HbTheme {
                    HbChatTranscript(timeline, Modifier.fillMaxSize(), onLinkClick = opened::add)
                }
            }
            onNodeWithText("Source").clickLinkText()
            runOnIdle { assertEquals(listOf("/project/main.kt:12"), opened) }
        }

    private fun SemanticsNodeInteraction.clickLinkText() {
        val layouts = mutableListOf<TextLayoutResult>()
        performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val position = layouts.single().getBoundingBox(1).center
        performMouseInput { click(position) }
    }
}
