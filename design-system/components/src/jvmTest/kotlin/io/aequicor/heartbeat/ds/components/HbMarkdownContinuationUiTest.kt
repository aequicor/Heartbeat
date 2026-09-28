package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals

/** A source paragraph may be lazy, but must not gain blank lines or paragraph gaps at chunk boundaries. */
@OptIn(ExperimentalTestApi::class)
class HbMarkdownContinuationUiTest {
    @Test
    fun `hard break prose retains forty text lines and no blank seam between its lazy segments`() =
        runSkikoComposeUiTest(size = Size(900f, 1400f)) {
            val source = (1..40).joinToString("  \n") { "Readable line $it" }
            val blocks = parseHbMarkdown(source)
            assertEquals(2, blocks.size)
            val message = HbChatMessage(
                "paragraph",
                "Heartbeat",
                source,
                kind = HbMessageKind.Markdown,
                appearance = HbMessageAppearance(isUnified = true, widthFraction = 1f),
            )
            val timeline = HbChatTimeline.from(HbChatSection("all", ""), persistentListOf(message))
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(darkTheme = false) {
                        HbChatTranscript(timeline, Modifier.fillMaxSize())
                    }
                }
            }
            val first = onNodeWithText(blocks.first().content.withoutTrailingLineBreak().text)
            val second = onNodeWithText(blocks.last().content.text)
            val layouts = mutableListOf<TextLayoutResult>()
            first.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            second.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(40, layouts.sumOf { it.lineCount })
            assertEquals(
                first.fetchSemanticsNode().boundsInRoot.bottom,
                second.fetchSemanticsNode().boundsInRoot.top,
                0.5f,
            )
        }
}
