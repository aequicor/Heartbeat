package io.aequicor.heartbeat.ds.catalog

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.adaptive.PlatformUi
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.theme.HbVisualStyle
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class SandboxUiTest {
    @Test
    fun `desktop catalog switches language and navigates without losing input`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        setContent { UIKitSandboxApp(initialDarkTheme = true) }
        onNodeWithText("EN").performClick()
        onNodeWithText("Start fresh").performClick()
        onNodeWithText("Chat playground").assertIsDisplayed()
        savePreview("desktop-chat-dark", captureToImage().toAwtImage())
        onNodeWithText("Components").performClick()
        onNode(hasSetTextAction()).performTextInput("Aequicor workspace")
        onNodeWithText("Create something").performClick()
        onNodeWithText("Action received").assertIsDisplayed()
        onNodeWithText("RU").performClick()
        onNodeWithText("Aequicor workspace").assertIsDisplayed()
        onNodeWithText("EN").performClick()
        onNodeWithText("Dark").performClick()
        onNodeWithText("System").performClick()
        onNodeWithText("Foundation").performClick()
        onNodeWithText("A softer spectrum").assertIsDisplayed()
        savePreview("desktop-foundation-light", captureToImage().toAwtImage())
    }

    @Test
    fun `pastel workspace renders with soft surfaces in light theme`() = runSkikoComposeUiTest(
        size = Size(1280f, 900f),
    ) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        onNodeWithText("EN").performClick()
        onNodeWithText("Start fresh").performClick()
        onNodeWithText("Chat playground").assertIsDisplayed()
        onNodeWithContentDescription("Send").assertIsDisplayed()
        savePreview("desktop-chat-light", captureToImage().toAwtImage())
        onNode(hasSetTextAction()).performTextInput("Sketch a calm workspace")
        onNodeWithContentDescription("Send").performClick()
        onNodeWithContentDescription("Stop").assertIsDisplayed().performClick()
        onNodeWithContentDescription("Send").assertIsDisplayed()
        onNodeWithText("Sketch a calm workspace").assertIsDisplayed()
        onNodeWithText("RU").performClick()
        onNodeWithText("Начать заново").performClick()
        savePreview("desktop-chat-ru", captureToImage().toAwtImage())
    }

    @Test
    fun `outline icon catalog renders in both themes`() {
        listOf(false, true).forEach { isDark ->
            runSkikoComposeUiTest(size = Size(1280f, 2300f)) {
                setContent {
                    HbTheme(darkTheme = isDark) {
                        Box(
                            modifier = Modifier.fillMaxSize().background(HbTheme.colors.background)
                                .padding(HbTheme.spacing.l),
                        ) {
                            IconsCatalog(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
                onNodeWithText("HomeFilled").assertIsDisplayed()
                onNodeWithText("ChevronDown").assertIsDisplayed()
                savePreview("icons-${if (isDark) "dark" else "light"}", captureToImage().toAwtImage())
            }
        }
    }

    @Test
    fun `compact chat keeps composer accessible`() = runSkikoComposeUiTest(size = Size(390f, 844f)) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        if (onAllNodesWithText("EN").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("EN").performClick()
        onNode(hasSetTextAction()).assertIsDisplayed()
        onNode(hasSetTextAction()).performTextInput("A compact conversation")
        onNodeWithContentDescription("Send").assertIsDisplayed()
        savePreview("compact-chat-light", captureToImage().toAwtImage())
    }

    @Test
    fun `short viewport and long draft keep send action visible`() = runSkikoComposeUiTest(size = Size(390f, 480f)) {
        setContent { UIKitSandboxApp(initialDarkTheme = false) }
        if (onAllNodesWithText("EN").fetchSemanticsNodes().isNotEmpty()) onNodeWithText("EN").performClick()
        onNode(hasSetTextAction()).performTextInput("A longer thought\n".repeat(20))
        onNode(hasScrollToIndexAction()).assertIsDisplayed()
        onNodeWithContentDescription("Send").assertIsDisplayed()
        savePreview("compact-short-viewport", captureToImage().toAwtImage())
    }

    @Test
    fun `native desktop kits render the same catalog`() {
        PlatformUi.entries.forEach { platform ->
            runSkikoComposeUiTest(size = Size(1280f, 900f)) {
                setContent {
                    UIKitSandboxApp(
                        initialPlatformUi = platform,
                        initialDarkTheme = false,
                        initialVisualStyle = HbVisualStyle.Platform,
                    )
                }
                onNodeWithText("EN").performClick()
                onNodeWithText("Components").performClick()
                onNodeWithText("Create something").performClick()
                onNodeWithText("Action received").assertIsDisplayed()
                savePreview("desktop-${platform.name.lowercase()}", captureToImage().toAwtImage())
            }
        }
    }

    private fun savePreview(name: String, image: java.awt.image.BufferedImage) {
        val directory = File("build/previews")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
        check(ImageIO.write(image, "png", File(directory, "$name.png"))) { "PNG encoder is unavailable" }
    }
}
