package io.aequicor.heartbeat.feature.researchchat.impl.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchMessageUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchPhase
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchQuestionUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchResourceUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenIntent
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchScreenState
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResearchSessionUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResourceKindUi
import io.aequicor.heartbeat.feature.researchchat.impl.presentation.store.ResourceScopeUi
import kotlinx.collections.immutable.persistentListOf
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ResearchUiTest {
    @Test
    fun `wide research shows sessions questions transcript and selected shared sources`() =
        runSkikoComposeUiTest(size = Size(1440f, 900f)) {
            val intents = mutableListOf<ResearchScreenIntent>()
            setContent { HbTheme(darkTheme = false) { ResearchScreenContent(researchUiSample(), intents::add, {}) } }
            onNodeWithTag("research-sessions").assertIsDisplayed()
            onNodeWithTag("research-questions").assertIsDisplayed()
            onNodeWithTag("research-transcript").assertIsDisplayed()
            onNodeWithTag("research-source-shared").assertIsDisplayed()
            onNodeWithTag("research-source-local").assertDoesNotExist()
            onNodeWithTag("research-source-selected-shared").performClick()
            assertEquals(
                listOf<ResearchScreenIntent>(ResearchScreenIntent.SetResourceSelected("shared", false)),
                intents,
            )
            save("research-wide-light", captureToImage().toAwtImage())
        }

    @Test
    fun `question source can be promoted to the whole session`() = runSkikoComposeUiTest(size = Size(1440f, 900f)) {
        val intents = mutableListOf<ResearchScreenIntent>()
        var state by mutableStateOf(researchUiSample())
        setContent {
            HbTheme(darkTheme = true) {
                ResearchScreenContent(state, {
                    intents += it
                    if (it is ResearchScreenIntent.SelectSourceScope) state = state.copy(selectedSourceScope = it.scope)
                }, {})
            }
        }
        onNodeWithTag("research-question-sources").performClick()
        onNodeWithTag("research-source-shared").assertDoesNotExist()
        onNodeWithTag("research-source-local").assertIsDisplayed()
        onNodeWithTag("research-share-source-local").performClick()
        assertEquals(
            listOf(
                ResearchScreenIntent.SelectSourceScope(ResourceScopeUi.Question),
                ResearchScreenIntent.ShareResource("local"),
            ),
            intents,
        )
        save("research-wide-dark", captureToImage().toAwtImage())
    }

    @Test
    fun `compact layout keeps source management and questions reachable`() = runSkikoComposeUiTest(
        size = Size(390f, 844f),
    ) {
        val intents = mutableListOf<ResearchScreenIntent>()
        setContent { HbTheme(darkTheme = false) { ResearchScreenContent(researchUiSample(), intents::add, {}) } }
        onNodeWithTag("research-conversation").assertIsDisplayed()
        onNodeWithTag("research-tab-Sources").performClick()
        onNodeWithTag("research-source-shared").assertIsDisplayed()
        onNodeWithTag("research-add-source").performClick()
        onNodeWithTag("research-tab-Questions").performClick()
        onNodeWithTag("research-question-second").performClick()
        onNodeWithTag("research-conversation").assertIsDisplayed()
        assertEquals(
            listOf(ResearchScreenIntent.ShowResourceDialog(true), ResearchScreenIntent.SelectQuestion("second")),
            intents,
        )
        save("research-compact", captureToImage().toAwtImage())
    }

    @Test
    fun `imported binary source hides encoded data and preserves explicit add action`() =
        runSkikoComposeUiTest(size = Size(800f, 800f)) {
            val intents = mutableListOf<ResearchScreenIntent>()
            val state = researchUiSample().copy(
                isResourceDialogOpen = true,
                resourceKind = ResourceKindUi.Image,
                resourceTitle = "Diagram.png",
                resourceValue = "data:image/png;base64,encoded-binary",
                resourceMediaType = "image/png",
                isFileImportAvailable = true,
            )
            setContent { HbTheme(darkTheme = false) { ResearchScreenContent(state, intents::add, {}) } }
            onNodeWithTag("research-file-attached").assertIsDisplayed()
            onNodeWithTag("research-source-value").assertDoesNotExist()
            onNodeWithTag("research-confirm-source").performClick()
            assertEquals(listOf<ResearchScreenIntent>(ResearchScreenIntent.AddResource), intents)
            save("research-source-dialog", captureToImage().toAwtImage())
        }

    @Test
    fun `error banner can be dismissed without losing the question`() = runSkikoComposeUiTest(
        size = Size(390f, 844f),
    ) {
        val intents = mutableListOf<ResearchScreenIntent>()
        setContent {
            HbTheme(darkTheme = false) {
                ResearchScreenContent(researchUiSample().copy(hasError = true), intents::add, {})
            }
        }
        onNodeWithTag("research-error").assertIsDisplayed()
        onNodeWithTag("research-dismiss-error").performClick()
        assertEquals(listOf<ResearchScreenIntent>(ResearchScreenIntent.DismissError), intents)
        onNodeWithTag("research-conversation").assertIsDisplayed()
    }

    @Test
    fun `prior response failure is shown without a dismissible action error`() = runSkikoComposeUiTest(
        size = Size(390f, 844f),
    ) {
        setContent {
            HbTheme(darkTheme = false) {
                ResearchScreenContent(researchUiSample().copy(hasQuestionFailed = true), {}, {})
            }
        }
        onNodeWithTag("research-previous-run-failed").assertIsDisplayed()
        onNodeWithTag("research-error").assertDoesNotExist()
    }

    private fun save(name: String, image: java.awt.image.BufferedImage) {
        val file = File("build/reports/research-chat/$name.png")
        file.parentFile.mkdirs()
        ImageIO.write(image, "png", file)
    }
}

private fun researchUiSample(): ResearchScreenState = ResearchScreenState(
    phase = ResearchPhase.Ready,
    sessions = persistentListOf(ResearchSessionUi("session", "Compose design systems", true)),
    questions = persistentListOf(
        ResearchQuestionUi("first", "Which systems support desktop?", false, false),
        ResearchQuestionUi("second", "How do they handle native controls?", true, false),
    ),
    resources = persistentListOf(
        ResearchResourceUi("shared", "Compose Multiplatform", "jetbrains.com", ResourceKindUi.Website, true, true),
        ResearchResourceUi("local", "Design notes", "Team research notes", ResourceKindUi.Document, false, true),
    ),
    messages = persistentListOf(
        ResearchMessageUi("user", true, "How do they handle native controls?"),
        ResearchMessageUi(
            "assistant",
            false,
            "## Native controls\n\nCompare each library’s platform adapters and accessibility support. " +
                "The shared documentation and the question’s design notes provide the context for this answer.",
        ),
    ),
    sessionTitle = "Compose design systems",
    questionTitle = "How do they handle native controls?",
    questionId = "second",
    isEditable = true,
)
