package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlinx.collections.immutable.persistentListOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbCodeSpacingUiTest {
    @Test
    fun `only overflowing code reserves space below its last line and remains horizontally scrollable`() =
        runSkikoComposeUiTest(size = Size(320f, 200f)) {
            val shortCode = "val count = 1\ncount + 2"
            val longCode = "val count = 1\n" + "argument ".repeat(80)
            var source by mutableStateOf(shortCode)
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    HbTheme(darkTheme = false) {
                        HbScrollableCode(
                            text = source,
                            spans = persistentListOf(),
                            modifier = Modifier.width(240.dp).testTag("code"),
                        )
                    }
                }
            }
            val code = onNodeWithTag("code")
            assertEquals(0f, code.bottomGutter(), "Fitting code must retain its compact text height")
            runOnIdle { source = longCode }
            assertEquals(8f, code.bottomGutter(), "The scrollbar must have its own space below the final code line")
            code.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(180f, 0f) }
            val scrolled = code.fetchSemanticsNode().config
            assertTrue(scrolled[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
            assertEquals(longCode, scrolled[SemanticsProperties.Text].single().text)
            assertEquals(8f, code.bottomGutter(), "Scrolling must not shift the code baseline or collapse its gutter")
            runOnIdle { source = shortCode }
            assertEquals(0f, code.bottomGutter(), "Removing overflow must also remove the reserved space")
            assertEquals(0f, code.fetchSemanticsNode().config[SemanticsProperties.HorizontalScrollAxisRange].maxValue())
        }
}

private fun SemanticsNodeInteraction.bottomGutter(): Float {
    val layouts = mutableListOf<TextLayoutResult>()
    performSemanticsAction(SemanticsActions.GetTextLayoutResult) { getLayout ->
        assertTrue(getLayout(layouts))
    }
    return fetchSemanticsNode().boundsInRoot.height - layouts.single().size.height
}
