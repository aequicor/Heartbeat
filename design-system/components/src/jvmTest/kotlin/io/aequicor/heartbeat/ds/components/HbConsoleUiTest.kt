package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbConsoleUiTest {
    @Test
    fun `long console lines scroll horizontally while preserving highlighted accessible output in both themes`() =
        runSkikoComposeUiTest(size = Size(320f, 200f)) {
            val source = "$ command " + "argument ".repeat(80) + "\nBUILD SUCCESSFUL\nexit code: 0"
            val chunk = chunkHbConsole(source).single()
            val darkState = mutableStateOf(false)
            setContent {
                HbTheme(darkTheme = darkState.value) { HbConsoleContent(chunk) }
            }
            val node = onNodeWithText(source)
            val light = node.fetchSemanticsNode().config
            assertEquals(
                HbColors.Light.consoleCommand,
                light[SemanticsProperties.Text].single().spanStyles.first().item.color,
            )
            node.performSemanticsAction(SemanticsActions.ScrollBy) { scroll -> scroll(180f, 0f) }
            val scrolled = node.fetchSemanticsNode().config
            assertTrue(scrolled[SemanticsProperties.HorizontalScrollAxisRange].value() > 0f)
            assertEquals(source, scrolled[SemanticsProperties.Text].single().text)
            runOnIdle { darkState.value = true }
            val dark = node.fetchSemanticsNode().config
            assertEquals(source, dark[SemanticsProperties.Text].single().text)
            assertEquals(
                HbColors.Dark.consoleSuccess,
                dark[SemanticsProperties.Text].single().spanStyles.last().item.color,
            )
        }
}
