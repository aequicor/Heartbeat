package io.aequicor.heartbeat.feature.checklist.screenui

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
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
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
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistPhaseUi
import io.aequicor.heartbeat.feature.checklist.screenmodel.ChecklistScreenIntent
import io.aequicor.heartbeat.feature.checklist.screenmodel.predictDraft
import kotlinx.collections.immutable.toImmutableList
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun `single choice options expose a selectable group independently from checkboxes`() = runSkikoComposeUiTest {
        setContent { HbTheme { ChecklistScreen(checklistSample(), {}) } }
        onNodeWithTag("checklist-choices-result")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.SelectableGroup))
        onNodeWithTag("checklist-choices-check")
            .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.SelectableGroup))
    }

    @Test
    fun `card emits keyboard and text actions and completed state freezes input`() = runSkikoComposeUiTest {
        var state by mutableStateOf(checklistSample())
        val intents = mutableListOf<ChecklistScreenIntent>()
        setContent { HbTheme { ChecklistScreen(state, intents::add) } }
        onNodeWithTag("checklist").performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("checklist-choice-check-light").assertIsFocused().performKeyInput { pressKey(Key.Tab) }
        onNodeWithTag("checklist-choice-check-dark").assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        runOnIdle {
            assertEquals(listOf<ChecklistScreenIntent>(ChecklistScreenIntent.Choice("check", "dark")), intents)
            intents.clear()
        }
        onNodeWithTag("checklist-text-text").performTextInput(" Additional notes")
        assertTrue(intents.any { it is ChecklistScreenIntent.Text })
        runOnIdle { state = state.copy(phase = ChecklistPhaseUi.Completed) }
        onNodeWithTag("checklist-choice-check-dark").assertIsNotEnabled()
        onNodeWithTag("checklist-text-text").assertIsNotEnabled()
        onNodeWithTag("checklist-complete").assertDoesNotExist()
    }

    @Test
    fun `fast typing survives late and stale owner answers`() = runSkikoComposeUiTest {
        val typed = "the quick brown fox"
        val initial = checklistSample().let { sample ->
            sample.copy(fields = sample.fields.map { if (it.isText) it.copy(text = "") else it }.toImmutableList())
        }
        var state by mutableStateOf(initial)
        val pending = mutableListOf<ChecklistScreenIntent>()
        setContent { HbTheme { ChecklistScreen(state, pending::add) } }
        val field = onNodeWithTag("checklist-text-text")
        // A slow owner (durable journal) answers nothing while keys arrive; frames still pass between keys.
        typed.forEach { char ->
            field.performTextInput(char.toString())
            waitForIdle()
        }
        field.assertTextEquals(typed, includeEditableText = true)
        // The owner then publishes its backlog in order, followed by a stale echo of an older draft.
        val answers = pending.toList()
        pending.clear()
        val published = answers.map { intent -> state.predictDraft(intent).also { state = it } }
        runOnIdle { state = published[2] }
        runOnIdle { state = published.last() }
        field.assertTextEquals(typed, includeEditableText = true)
        assertEquals(ChecklistScreenIntent.Text("text", typed), answers.last())
        assertEquals(typed, state.fields.first { it.isText }.text)
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
