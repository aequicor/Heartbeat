package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import io.aequicor.heartbeat.ds.theme.HbTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class HbSecretFieldUiTest {
    @Test
    fun `secret field reports the typed value but shows only bullets`() = runSkikoComposeUiTest {
        var value by mutableStateOf("")
        setContent {
            HbTheme {
                HbTextField(value, { value = it }, Modifier.testTag("secret"), isSecret = true)
            }
        }
        val field = onNodeWithTag("secret")
        field.performTextInput("sk-123")
        runOnIdle { assertEquals("sk-123", value) }
        val config = field.fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in config)
        // Semantics keep the real text behind the Password flag; what is drawn is obfuscated.
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("••••••", layouts.single().layoutInput.text.text)
    }

    @Test
    fun `secret field offers no copy or cut`() = runSkikoComposeUiTest {
        var value by mutableStateOf("sk-123")
        setContent {
            HbTheme {
                HbTextField(value, { value = it }, Modifier.testTag("secret"), isSecret = true)
            }
        }
        val config = onNodeWithTag("secret").fetchSemanticsNode().config
        assertFalse(SemanticsActions.CopyText in config)
        assertFalse(SemanticsActions.CutText in config)
    }

    @Test
    fun `plain field shows its text and is not a password`() = runSkikoComposeUiTest {
        var value by mutableStateOf("")
        setContent {
            HbTheme {
                HbTextField(value, { value = it }, Modifier.testTag("plain"))
            }
        }
        val field = onNodeWithTag("plain")
        field.performTextInput("hello")
        runOnIdle { assertEquals("hello", value) }
        val config = field.fetchSemanticsNode().config
        assertFalse(SemanticsProperties.Password in config)
        assertEquals("hello", config[SemanticsProperties.EditableText].text)
    }
}
