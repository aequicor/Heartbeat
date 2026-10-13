package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.aequicor.heartbeat.ds.theme.HbTheme
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbMarkdownTableUiTest {
    @Test
    fun `table rows share height and continuous backgrounds in both themes and viewport sizes`() {
        for (isDark in listOf(false, true)) {
            for (width in listOf(420f, 1280f)) verifyGrid(width, isDark)
        }
    }

    private fun verifyGrid(width: Float, isDark: Boolean) = runSkikoComposeUiTest(size = Size(width, 600f)) {
        val block = parseHbMarkdown(TABLE_SAMPLE).single()
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme(darkTheme = isDark) {
                    Box(Modifier.fillMaxSize().background(HbTheme.colors.background).padding(HbTheme.spacing.xl)) {
                        HbMarkdownBlockContent(block)
                    }
                }
            }
        }
        val number = onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        val description = onNodeWithText(LONG_DESCRIPTION).fetchSemanticsNode().boundsInRoot
        assertEquals(description.top, number.top, 0.5f)
        assertEquals(description.bottom, number.bottom, 0.5f)
        if (width < 500f) assertTrue(number.height > 30f, "Wrapping must grow every cell in its row")
        val second = onNodeWithText("2").fetchSemanticsNode().boundsInRoot
        assertEquals(16f, second.top - number.bottom, 0.5f, "Only cell padding separates text areas")
        val image = captureToImage().toAwtImage()
        val y = number.bottom.roundToInt() + 3
        assertEquals(image.getRGB(number.left.roundToInt() + 2, y), image.getRGB(description.left.roundToInt() + 2, y))
        val directory = File("build/previews").apply { mkdirs() }
        check(ImageIO.write(image, "png", File(directory, "table-${width.toInt()}-$isDark.png")))
    }

    @Test
    fun `one horizontal scroll moves header and body together and survives streaming`() = runSkikoComposeUiTest(
        size = Size(420f, 600f),
    ) {
        val source = mutableStateOf(TABLE_SAMPLE)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme {
                    HbMarkdownBlockContent(parseHbMarkdown(source.value).single())
                }
            }
        }
        val horizontal = SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange)
        onAllNodes(horizontal).assertCountEquals(1)
        val headerBefore = onNodeWithText("ПР").fetchSemanticsNode().boundsInRoot.left
        val rowBefore = onNodeWithText(LONG_DESCRIPTION).fetchSemanticsNode().boundsInRoot.left
        onNode(horizontal).performSemanticsAction(SemanticsActions.ScrollBy) { it(40f, 0f) }
        waitForIdle()
        val headerAfter = onNodeWithText("ПР").fetchSemanticsNode().boundsInRoot.left
        val rowAfter = onNodeWithText(LONG_DESCRIPTION).fetchSemanticsNode().boundsInRoot.left
        assertTrue(headerBefore > headerAfter)
        assertEquals(headerBefore - headerAfter, rowBefore - rowAfter, 0.5f)
        runOnIdle { source.value += "\n| 4 | More work | Passed | 1 of 1 |" }
        assertEquals(headerAfter, onNodeWithText("ПР").fetchSemanticsNode().boundsInRoot.left, 0.5f)
    }

    @Test
    fun `large tables virtualize rows and retain shared scrolling`() = runSkikoComposeUiTest(size = Size(420f, 600f)) {
        val source = "| Name | Details | Status | Approvals |\n| --- | --- | --- | --- |\n" +
            (1..1000).joinToString("\n") {
                "| Entry $it | ${"Long description ".repeat(8)} | Passed | Waiting for review |"
            }
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                HbTheme { HbMarkdownBlockContent(parseHbMarkdown(source).single()) }
            }
        }
        onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
        ).assertCountEquals(1)
        onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(1)
        val entries = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.CollectionItemInfo))
            .fetchSemanticsNodes()
        assertTrue(entries.size < 100, "A thousand-row table must only compose its visible cells")
        val vertical = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
        assertTrue(vertical.fetchSemanticsNode().boundsInRoot.height <= 480f)
        val horizontal = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange))
        horizontal.performSemanticsAction(SemanticsActions.ScrollBy) { it(40f, 0f) }
        waitForIdle()
        val horizontalRange = horizontal.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange]
        assertTrue(horizontalRange.value() > 0f, "The wide table must scroll horizontally")
        vertical.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 500f) }
        waitForIdle()
        val verticalRange = vertical.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(verticalRange.value() > 0f, "The large table must scroll vertically")
    }

    @Test
    fun `RTL draws the divider between the first and second columns`() = runSkikoComposeUiTest(
        size = Size(1280f, 600f),
    ) {
        setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalLayoutDirection provides LayoutDirection.Rtl,
            ) {
                HbTheme { HbMarkdownBlockContent(parseHbMarkdown(TABLE_SAMPLE).single()) }
            }
        }
        val first = onNodeWithText("1").fetchSemanticsNode().boundsInRoot
        val image = captureToImage().toAwtImage()
        val x = first.left.roundToInt() - 8
        val y = first.bottom.roundToInt() + 3
        assertNotEquals(image.getRGB(x + 2, y), image.getRGB(x, y), "The internal divider must remain visible in RTL")
    }

    @Test
    fun `agent emphasis is styled and links are still actionable inside cells`() = runSkikoComposeUiTest {
        var clicked: String? = null
        setContent {
            HbTheme {
                HbMarkdownBlockContent(
                    parseHbMarkdown(
                        "| Result | Link |\n| --- | --- |\n| **Important** | [Open](https://example.com) |",
                    ).single(),
                    onLinkClick = { clicked = it },
                )
            }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        onNodeWithText("Important").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue(layouts.single().layoutInput.text.spanStyles.any { it.item.fontWeight == FontWeight.SemiBold })
        onRoot().performKeyInput {
            pressKey(Key.Tab)
            pressKey(Key.Enter)
        }
        assertEquals("https://example.com", clicked, "Table links must be keyboard accessible")
        clicked = null
        onNode(hasClickAction()).performClick()
        assertEquals("https://example.com", clicked)
    }
}

private const val LONG_DESCRIPTION = "ANDR-112453: Нижняя шторка на втором шаге корзины с подробным описанием изменений"
private const val TABLE_SAMPLE = "| # | ПР | Пайплайн | Аппрувы |\n" +
    "| --- | --- | --- | --- |\n" +
    "| 1 | **ANDR-112453:** Нижняя шторка на втором шаге корзины " +
    "с подробным описанием изменений | Passed | 0 of 1 |\n" +
    "| 2 | Рефакторинг аналитики | **Failed** | 0 of 2 |\n" +
    "| 3 | *Проверить перед релизом* | `Warning` | 4 of 6 |"
