package io.aequicor.heartbeat.feature.checklist.impl.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.components.HbChatMessage
import io.aequicor.heartbeat.ds.components.HbChatSection
import io.aequicor.heartbeat.ds.components.HbChatTimeline
import io.aequicor.heartbeat.ds.components.HbChatTranscript
import io.aequicor.heartbeat.ds.components.HbMessageStatus
import io.aequicor.heartbeat.ds.components.HbText
import io.aequicor.heartbeat.ds.components.HbTextField
import io.aequicor.heartbeat.ds.layouts.HbColumn
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistPhaseUi
import io.aequicor.heartbeat.feature.checklist.impl.presentation.store.ChecklistScreenIntent
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ChecklistUiTest {
    @Test
    fun `mixed checklist fits compact and desktop in both themes`() {
        for (width in listOf(360, 720)) {
            for (dark in listOf(false, true)) {
                runSkikoComposeUiTest(size = Size(width.toFloat(), 1000f)) {
                    setContent {
                        CompositionLocalProvider(LocalDensity provides Density(1f)) {
                            HbTheme(darkTheme = dark) {
                                Box(Modifier.fillMaxSize().background(HbTheme.colors.background)) {
                                    ChecklistScreen(checklistSample(), {})
                                }
                            }
                        }
                    }
                    onNodeWithTag("checklist-complete").assertIsDisplayed()
                    val card = onNodeWithTag("checklist").fetchSemanticsNode().boundsInRoot
                    val choice = onNodeWithTag("checklist-choice-check-dark").fetchSemanticsNode().boundsInRoot
                    assertTrue(choice.right <= card.right && choice.left >= card.left)
                    val file = File("build/reports/snapshots/checklist-$width-$dark.png")
                    file.parentFile.mkdirs()
                    check(ImageIO.write(captureToImage().toAwtImage(), "png", file))
                }
            }
        }
    }

    @Test
    fun `card emits keyboard and text actions and completed state freezes input`() = runSkikoComposeUiTest {
        var state by mutableStateOf(checklistSample())
        val intents = mutableListOf<ChecklistScreenIntent>()
        setContent { HbTheme { ChecklistScreen(state, intents::add) } }
        onNodeWithTag("checklist-choice-check-dark").performClick()
        onNodeWithTag("checklist-choice-check-dark").performKeyInput { pressKey(Key.Spacebar) }
        onNodeWithTag("checklist-text-text").performTextInput(" Additional notes")
        assertTrue(intents.any { it is ChecklistScreenIntent.Choice })
        assertTrue(intents.any { it is ChecklistScreenIntent.Text })
        runOnIdle { state = state.copy(phase = ChecklistPhaseUi.Completed) }
        onNodeWithTag("checklist-choice-check-dark").assertIsNotEnabled()
        onNodeWithTag("checklist-text-text").assertIsNotEnabled()
        onNodeWithTag("checklist-complete").assertDoesNotExist()
    }

    @Test
    fun `embedded card is below message body and retains text focus across streaming chunks`() =
        runSkikoComposeUiTest(size = Size(600f, 1000f)) {
            var message by mutableStateOf(HbChatMessage("answer", "Agent", "Before", hasEmbeddedContent = true))
            var draft by mutableStateOf("")
            setContent {
                HbTheme {
                    HbChatTranscript(
                        HbChatTimeline.Empty.append(HbChatSection("today", "Today"), message),
                        messageEmbeddedContent = {
                            HbColumn {
                                HbText("Card title")
                                HbTextField(draft, { draft = it }, accessibleLabel = "Draft")
                            }
                        },
                    )
                }
            }
            val textBottom = onNodeWithText("Before").fetchSemanticsNode().boundsInRoot.bottom
            val cardTop = onNodeWithText("Card title").fetchSemanticsNode().boundsInRoot.top
            assertTrue(cardTop >= textBottom)
            onNode(hasSetTextAction()).performClick().performTextInput("Saved draft")
            runOnIdle { message = message.copy(text = "Before\n\nNew paragraph", status = HbMessageStatus.Streaming) }
            onNode(hasSetTextAction()).assertIsFocused().assertTextContains("Saved draft")
        }
}
