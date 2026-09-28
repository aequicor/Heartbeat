package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import java.awt.datatransfer.DataFlavor
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbDiffUiTest {
    @Test
    fun `keyboard copies the complete ellipsized Unicode path and feedback returns to idle`() = runSkikoComposeUiTest(
        size = Size(320f, 240f),
    ) {
        val path = "src/папка с пробелами/".repeat(4) + "Изменения 🙂.kt"
        val clipboard = DiffTestClipboard()
        val motion = HbMotion(isReducedMotion = true)
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                HbTheme(motion = motion) { HbDiffView(diffSource(path)) }
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText(path).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        // SkiaParagraph.isLineEllipsized always returns false in Compose 1.12.1.
        assertEquals(1, layout.layoutInput.maxLines)
        assertEquals(
            TextOverflow.Clip,
            layout.layoutInput.overflow,
            "Single-line text fades instead of ending with an ellipsis",
        )
        assertTrue(layout.hasVisualOverflow && layout.size.width < 320, "The full path must exceed its bounded header")
        saveDiffPathPreview(captureToImage().toAwtImage())
        val copy = onNodeWithContentDescription("Copy file path: $path")
        copy.performSemanticsAction(SemanticsActions.RequestFocus)
        copy.assertIsFocused()
        mainClock.autoAdvance = false
        copy.performKeyInput { pressKey(Key.Enter) }
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        runOnIdle {
            assertEquals(path, clipboard.text())
            assertEquals(1, clipboard.writes)
        }
        copy.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Path copied"))
        mainClock.advanceTimeBy(motion.copyFeedbackMillis.toLong() + 100)
        copy.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Copy file path"))
        runOnIdle { assertEquals(path, clipboard.text(), "Clearing feedback must preserve the clipboard") }
    }

    @Test
    fun `replacing the file clears copied feedback and copies the new path`() = runSkikoComposeUiTest(
        size = Size(320f, 240f),
    ) {
        val path = mutableStateOf("src/first file.kt")
        val clipboard = DiffTestClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                HbTheme(motion = HbMotion(isReducedMotion = true)) { HbDiffView(diffSource(path.value)) }
            }
        }
        mainClock.autoAdvance = false
        onNodeWithContentDescription("Copy file path: ${path.value}").performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        onNodeWithContentDescription("Copy file path: ${path.value}").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Path copied"),
        )
        runOnIdle { path.value = "src/другой путь/second.kt" }
        mainClock.advanceTimeByFrame()
        val second = onNodeWithContentDescription("Copy file path: ${path.value}")
        second.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Copy file path"))
        second.performClick()
        mainClock.advanceTimeByFrame()
        mainClock.advanceTimeByFrame()
        runOnIdle {
            assertEquals(path.value, clipboard.text())
            assertEquals(2, clipboard.writes)
        }
    }

    @Test
    fun `a diff without file metadata has no misleading copy action`() = runSkikoComposeUiTest(
        size = Size(320f, 240f),
    ) {
        val clipboard = DiffTestClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                HbTheme { HbDiffView("-before\n+after") }
            }
        }
        onNodeWithText("Code changes").assertExists()
        onAllNodes(hasContentDescription("Copy file path", substring = true)).assertCountEquals(0)
        runOnIdle { assertEquals(0, clipboard.writes) }
    }

    @Test
    fun `long diff lines keep full accessible text and horizontal scrolling in both themes`() = runSkikoComposeUiTest(
        size = Size(320f, 240f),
    ) {
        val isDark = mutableStateOf(false)
        val added = "+val result = \"" + "argument ".repeat(60) + "\""
        setContent { HbTheme(darkTheme = isDark.value) { HbDiffView(diffSource("src/Result.kt", added)) } }
        val horizontal = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        onAllNodes(horizontal).assertCountEquals(1)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
        val scroll = onNode(horizontal)
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue() > 0f)
        scroll.performSemanticsAction(SemanticsActions.ScrollBy) { it(180f, 0f) }
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        assertEquals(added, onNodeWithText(added).fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
        runOnIdle { isDark.value = true }
        assertTrue(scroll.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
        assertEquals(added, onNodeWithText(added).fetchSemanticsNode().config[SemanticsProperties.Text].single().text)
        onNodeWithContentDescription("Copy file path: src/Result.kt").assertExists()
    }
}

private fun saveDiffPathPreview(image: java.awt.image.BufferedImage) {
    val directory = File("build/previews")
    check(directory.isDirectory || directory.mkdirs()) { "Cannot create preview directory" }
    check(ImageIO.write(image, "png", File(directory, "diff-long-path.png"))) { "PNG encoder is unavailable" }
}

private fun diffSource(path: String, added: String = "+val value = 2"): String =
    "--- \"a/$path\"\n+++ \"b/$path\"\n@@ -1 +1 @@\n-val value = 1\n$added"

private class DiffTestClipboard : Clipboard {
    private var entry: ClipEntry? = null
    var writes: Int = 0
        private set

    override suspend fun getClipEntry(): ClipEntry? = entry

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        entry = clipEntry
        writes++
    }

    @OptIn(ExperimentalComposeUiApi::class)
    fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
}
