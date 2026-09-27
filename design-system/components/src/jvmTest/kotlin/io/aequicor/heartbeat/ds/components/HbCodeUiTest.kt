package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import io.aequicor.heartbeat.ds.theme.HbTheme
import io.aequicor.heartbeat.ds.tokens.HbColors
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

@OptIn(ExperimentalTestApi::class)
class HbCodeUiTest {
    @Test
    fun `prepared syntax follows theme changes without changing accessible text`() = runSkikoComposeUiTest {
        val source = "val title = \"Ready\""
        val block = parseHbMarkdown("```kotlin\n$source\n```").single()
        val isDark = mutableStateOf(false)
        setContent {
            HbTheme(darkTheme = isDark.value) { HbMarkdownBlockContent(block) }
        }
        val light = onNodeWithText(source).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(source, light.text)
        assertEquals(HbColors.Light.syntaxKeyword, light.spanStyles.first().item.color)
        runOnIdle { isDark.value = true }
        val dark = onNodeWithText(source).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertEquals(source, dark.text)
        assertEquals(HbColors.Dark.syntaxKeyword, dark.spanStyles.first().item.color)
        assertNotEquals(light.spanStyles, dark.spanStyles)
    }
}
