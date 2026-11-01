package io.aequicor.heartbeat.ds.components

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.v2.runSkikoComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
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
        assertEquals("••••••", config[SemanticsProperties.EditableText].text)
        assertEquals("••••••", config[SemanticsProperties.InputText].text)
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("••••••", layouts.single().layoutInput.text.text)
    }

    @Test
    fun `revealed secret shows its value without losing secure field protections`() = runSkikoComposeUiTest {
        var value by mutableStateOf("sk-123")
        setContent {
            HbTheme {
                HbTextField(
                    value,
                    { value = it },
                    Modifier.testTag("secret"),
                    isSecret = true,
                    isSecretVisible = true,
                )
            }
        }
        val config = onNodeWithTag("secret").fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in config)
        assertEquals("sk-123", config[SemanticsProperties.EditableText].text)
        assertEquals("sk-123", config[SemanticsProperties.InputText].text)
        assertFalse(SemanticsActions.CopyText in config)
        assertFalse(SemanticsActions.CutText in config)
        val layouts = mutableListOf<TextLayoutResult>()
        config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertEquals("sk-123", layouts.single().layoutInput.text.text)
    }

    @Test
    fun `masked accessibility text does not replace the underlying secret during edits`() = runSkikoComposeUiTest {
        var value by mutableStateOf("sk-123")
        setContent {
            HbTheme {
                HbTextField(value, { value = it }, Modifier.testTag("secret"), isSecret = true)
            }
        }
        val field = onNodeWithTag("secret")
        field.performTextInputSelection(TextRange(3, 6))
        field.performTextInput("4567")
        runOnIdle { assertEquals("sk-4567", value) }
        assertEquals("•••••••", field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
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
    fun `hidden nonempty secret is not restored with saved state`() = assertSecretNotRestored(isSecretVisible = false)

    @Test
    fun `revealed nonempty secret is not restored with saved state`() = assertSecretNotRestored(isSecretVisible = true)

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

    @Test
    fun `plain editor text enters the real saveable registry`() = runSkikoComposeUiTest {
        val registry = SaveableStateRegistry(null) { true }
        setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                HbTheme { HbTextField("ordinary-text", {}, Modifier.testTag("plain")) }
            }
        }
        runOnIdle { assertTrue(registry.performSave().toString().contains("ordinary-text")) }
    }

    private fun assertSecretNotRestored(isSecretVisible: Boolean) = runSkikoComposeUiTest {
        val secret = "sk-nonempty"
        var value by mutableStateOf(secret)
        var registry by mutableStateOf(SaveableStateRegistry(null) { true })
        var isShown by mutableStateOf(true)
        setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                if (isShown) {
                    HbTheme {
                        DisposableEffect(Unit) {
                            onDispose { value = "" }
                        }
                        HbTextField(
                            value,
                            { value = it },
                            Modifier.testTag("secret"),
                            isSecret = true,
                            isSecretVisible = isSecretVisible,
                        )
                    }
                }
            }
        }
        val field = onNodeWithTag("secret")
        val displayedText = if (isSecretVisible) secret else "•".repeat(secret.length)
        assertEquals(displayedText, field.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        runOnIdle { assertEquals(secret, value) }

        // Skiko's StateRestorationTester codec is unimplemented; exercise the real saveable registry instead.
        var saved: Map<String, List<Any?>> = emptyMap()
        runOnIdle {
            saved = registry.performSave()
            assertFalse(saved.toString().contains(secret))
            isShown = false
        }
        runOnIdle {
            assertEquals("", value)
            registry = SaveableStateRegistry(saved) { true }
            isShown = true
        }

        runOnIdle { assertEquals("", value) }
        val restoredConfig = field.fetchSemanticsNode().config
        assertTrue(SemanticsProperties.Password in restoredConfig)
        assertEquals("", restoredConfig[SemanticsProperties.EditableText].text)
    }
}
