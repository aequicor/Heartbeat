package io.aequicor.heartbeat.ds.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.aequicor.heartbeat.ds.layouts.HbLazyColumn
import io.aequicor.heartbeat.ds.layouts.hbStickyHeader
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbMotion
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbHeaderInputUiTest {
    @Test
    fun `the pinned header gutter blocks clicks on masked rows while visible rows stay interactive`() =
        runSkikoComposeUiTest(size = Size(320f, 240f)) {
            val state = LazyListState(firstVisibleItemIndex = 10)
            var clicks = 0
            setContent { HeaderInputFixture(state, onRowClick = { clicks++ }) }
            val heading = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
                .fetchSemanticsNode().boundsInRoot
            val gutterPoint = Offset(heading.center.x, heading.bottom + 4f)
            val maskedRow = onNodeWithTag("row-9").fetchSemanticsNode().boundsInRoot
            assertTrue(maskedRow.contains(gutterPoint), "The tested gutter must overlap a real clickable row")
            runOnIdle {
                val pinned = checkNotNull(state.pinnedHeader("section:"))
                assertEquals(8f, pinned.top + pinned.size - heading.bottom)
            }
            onNodeWithTag("header-host").performMouseInput {
                moveTo(gutterPoint)
                press()
                release()
            }
            runOnIdle { assertEquals(0, clicks, "Masked content must not activate through the header gutter") }
            onNodeWithTag("row-11").performMouseInput {
                moveTo(center)
                press()
                release()
            }
            runOnIdle { assertEquals(1, clicks, "The header blocker must not cover visible content") }
        }

    @Test
    fun `wheel input over the header gutter keeps the same scroll speed as visible rows`() = runSkikoComposeUiTest(
        size = Size(320f, 240f),
    ) {
        val state = LazyListState(firstVisibleItemIndex = 10)
        setContent { HeaderInputFixture(state, onRowClick = {}) }
        val heading = onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .fetchSemanticsNode().boundsInRoot
        val before = runOnIdle { state.firstVisibleItemIndex * 48 + state.firstVisibleItemScrollOffset }
        onNodeWithTag("header-host").performMouseInput {
            moveTo(Offset(heading.center.x, heading.bottom + 4f))
            scroll(3f)
        }
        waitForIdle()
        val gutterDistance = runOnIdle {
            state.firstVisibleItemIndex * 48 + state.firstVisibleItemScrollOffset - before
        }
        runOnIdle { state.requestScrollToItem(10) }
        waitForIdle()
        onNodeWithTag("header-host").performMouseInput {
            moveTo(center)
            scroll(3f)
        }
        waitForIdle()
        val rowDistance = runOnIdle {
            state.firstVisibleItemIndex * 48 + state.firstVisibleItemScrollOffset - before
        }
        assertTrue(gutterDistance > 0, "Blocking taps must preserve wheel scrolling over the gutter")
        assertTrue(abs(gutterDistance - rowDistance) <= 2, "Gutter and rows must use the same viewport scroll speed")
    }
}

@Composable
private fun HeaderInputFixture(state: LazyListState, onRowClick: () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(1f)) {
        HbTheme(darkTheme = false, motion = HbMotion(isReducedMotion = true)) {
            HbStickyHeaderHost(
                state = state,
                stickyHeaderKeyPrefix = "section:",
                header = { _, modifier -> HbTranscriptSectionHeader(HbChatSection("test", "Session"), modifier) },
                modifier = Modifier.fillMaxSize().testTag("header-host"),
            ) { headerContent ->
                HbLazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = state,
                    gap = 0.dp,
                    contentPadding = PaddingValues(12.dp),
                    showScrollbar = false,
                ) {
                    hbStickyHeader("section:test") { headerContent("section:test") }
                    items(40, key = { "row-$it" }) { index ->
                        Box(
                            modifier = Modifier.fillMaxWidth().height(48.dp).testTag("row-$index")
                                .clickable(onClick = onRowClick),
                        ) {
                            HbText("Interactive row $index")
                        }
                    }
                }
            }
        }
    }
}
